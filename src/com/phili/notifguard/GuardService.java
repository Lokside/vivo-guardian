package com.phili.notifguard;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * vivo (Chinese ROM) force-stops an app when a finger lands on its icon without opening it (launcher
 * pre-start), and also from its cleaner and resource manager. A force-stopped app no longer receives its
 * Google notifications until it is opened again. This service unblocks it right away by sending a signal
 * to one of its exported receivers (which clears the "stopped" state), without showing the app.
 *
 * Watched apps: every app installed by the user (and updated Google apps) that is allowed to post
 * notifications, except vivo / Android system apps.
 *
 * Keep-alive: this is a notification listener, NOT an accessibility service (banking apps refuse to run
 * next to third-party accessibility services). Android keeps an enabled listener bound exactly like an
 * accessibility service. The listener role is only an anchor: every notification type is disabled in the
 * manifest, so the system delivers no notification to this service and it reads none.
 */
public class GuardService extends NotificationListenerService {

    private static final String WAKE_ACTION = "com.phili.notifguard.WAKE";
    // Actions whose receivers are almost always exported and without permission.
    private static final String[] ENTRY_ACTIONS = {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
    };
    // System apps never woken up (Chinese services, Android core).
    private static final String[] EXCLUDED_PREFIXES = {
            "android", "com.android.", "com.vivo.", "com.bbk.", "com.iqoo.", "vivo.", "com.mediatek.",
    };
    private static final String SETTINGS_PKG = "com.android.settings";
    // GmsCore (ReVanced's microG) receives the notifications of patched YouTube. vivo kills it (memory)
    // without force-stopping it, and it does not always reconnect. A "soft" signal to its TriggerReceiver
    // reconnects it if it is disconnected, otherwise it only sends a heartbeat (connection kept).
    private static final ComponentName GMSCORE_TRIGGER = new ComponentName(
            "app.revanced.android.gms", "org.microg.gms.gcm.TriggerReceiver");
    private static final long SWEEP_PERIOD_MS = 5 * 60 * 1000;
    private static final long REWAKE_MIN_GAP_MS = 10 * 1000;
    // vivo's resource manager force-stops apps 1 to 2 s after the screen turns off: check once after it.
    private static final long SCREEN_OFF_SWEEP_DELAY_MS = 4 * 1000;
    private static final long FOREGROUND_LOOKBACK_MS = 30 * 60 * 1000;
    private static final int MAX_ATTEMPTS = 3;

    /** True while Android has this listener bound (read by the status screen, same process). */
    static volatile boolean connected;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, List<ComponentName>> entryCache = new HashMap<>();
    private final Map<String, Long> lastWake = new HashMap<>();
    // Apps left alone until their state changes (reopened, updated or force-stopped again):
    // stopped by the user, without entry point, or whose unblocking failed.
    private final Set<String> leftAlone = new HashSet<>();
    // Cached list of watched apps: rebuilt (scan of all installed apps) only when an app is installed /
    // updated / removed, when the screen turns on and every 5 min.
    private List<String> watchedCache;
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String pkg = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
            if (Intent.ACTION_PACKAGE_RESTARTED.equals(action)) {
                if (!isWatched(context, pkg)) return;
                if (stoppedByUser()) {
                    Journal.write(context, context.getString(R.string.j_stopped_by_user, label(context, pkg)));
                    leftAlone.add(pkg);
                    return;
                }
                leftAlone.remove(pkg); // new stop: try again once
                Journal.write(context, context.getString(R.string.j_force_stop, label(context, pkg)));
                // Let the system finish the stop before waking the app.
                handler.postDelayed(() -> wake(pkg, 0), 300);
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REPLACED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                if (pkg != null) {
                    entryCache.remove(pkg);
                    lastWake.remove(pkg);
                    leftAlone.remove(pkg);
                }
                watchedCache = null;
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                handler.removeCallbacks(screenOffSweep);
                handler.postDelayed(screenOffSweep, SCREEN_OFF_SWEEP_DELAY_MS);
            } else {
                handler.removeCallbacks(screenOffSweep);
                sweep(getString(R.string.reason_screen_on), true);
                pokeGmsCore();
            }
        }
    };

    private final Runnable periodicSweep = new Runnable() {
        @Override
        public void run() {
            sweep(getString(R.string.reason_periodic), true);
            pokeGmsCore();
            handler.postDelayed(this, SWEEP_PERIOD_MS);
        }
    };

    // Light check (apps already watched): catches a force-stop whose broadcast would have been missed.
    private final Runnable screenOffSweep = () -> sweep(getString(R.string.reason_screen_off), false);

    @Override
    public void onListenerConnected() {
        connected = true;
        if (started) return;
        started = true;

        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_RESTARTED);
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        register(packages);
        register(screen);

        Journal.write(this, getString(R.string.j_service_started, watched(true).size(),
                getPackageManager().getInstalledApplications(0).size()));
        sweep(getString(R.string.reason_start), false);
        if (pokeGmsCore()) {
            Journal.write(this, getString(R.string.j_gmscore));
        }
        handler.postDelayed(periodicSweep, SWEEP_PERIOD_MS);
    }

    @Override
    public void onListenerDisconnected() {
        stop();
        // Android unbound the listener while notification access is still granted (it happens after
        // some system events): ask to be bound again. No effect if the user turned the access off.
        requestRebind(new ComponentName(this, GuardService.class));
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        connected = false;
        if (!started) return;
        started = false;
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        Journal.write(this, getString(R.string.j_service_stopped));
    }

    /** Sends the soft signal to GmsCore if it is installed. Returns false if it is absent. */
    private boolean pokeGmsCore() {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(GMSCORE_TRIGGER.getPackageName(), 0);
            if (!ai.enabled) return false;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        sendBroadcast(new Intent(WAKE_ACTION)
                .setComponent(GMSCORE_TRIGGER)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND));
        return true;
    }

    private void register(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    /**
     * A "Force stop" made by the user necessarily comes from the Settings app in the foreground, with
     * the screen on (vivo's resource manager strikes after the screen turns off). Knowing the foreground
     * app requires the optional "usage access"; without it every force-stop is undone.
     */
    private boolean stoppedByUser() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || !pm.isInteractive()) return false;
        String foreground = foregroundPackage();
        return foreground != null && foreground.startsWith(SETTINGS_PKG);
    }

    /** Package of the last activity brought to the screen, or null if unknown (no usage access). */
    private String foregroundPackage() {
        if (!hasUsageAccess(this)) return null;
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - FOREGROUND_LOOKBACK_MS, now);
        if (events == null) return null;
        UsageEvents.Event event = new UsageEvents.Event();
        String foreground = null;
        while (events.getNextEvent(event)) {
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) { // = ACTIVITY_RESUMED
                foreground = event.getPackageName();
            }
        }
        return foreground;
    }

    @SuppressWarnings("deprecation") // checkOpNoThrow: unsafeCheckOpNoThrow only exists from API 29
    static boolean hasUsageAccess(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private List<String> watched(boolean refresh) {
        if (refresh || watchedCache == null) {
            watchedCache = watchedPackages(this);
        }
        return watchedCache;
    }

    private void sweep(String reason, boolean refreshList) {
        for (String pkg : watched(refreshList)) {
            if (!isStopped(this, pkg)) {
                lastWake.remove(pkg); // the app was reopened: eligible again
                leftAlone.remove(pkg);
                continue;
            }
            if (leftAlone.contains(pkg)) continue;
            Long last = lastWake.get(pkg);
            if (last != null && SystemClock.elapsedRealtime() - last < REWAKE_MIN_GAP_MS) continue;
            Journal.write(this, getString(R.string.j_found_blocked_reason, label(this, pkg), reason));
            wake(pkg, 0);
        }
    }

    private void wake(String pkg, int attempt) {
        if (!isStopped(this, pkg)) {
            return;
        }
        if (attempt == 0) {
            Long last = lastWake.get(pkg);
            if (last != null && SystemClock.elapsedRealtime() - last < REWAKE_MIN_GAP_MS) return;
            lastWake.put(pkg, SystemClock.elapsedRealtime());
        }
        List<ComponentName> entries = entries(pkg);
        if (entries.isEmpty()) {
            leftAlone.add(pkg);
            Journal.write(this, getString(R.string.j_no_entry, label(this, pkg)));
            return;
        }
        ComponentName target = entries.get(attempt % entries.size());
        Intent intent = new Intent(WAKE_ACTION)
                .setComponent(target)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(intent);

        handler.postDelayed(() -> {
            if (!isStopped(this, pkg)) {
                Journal.write(this, getString(R.string.j_unblocked, label(this, pkg)));
            } else if (attempt + 1 < MAX_ATTEMPTS) {
                wake(pkg, attempt + 1);
            } else {
                leftAlone.add(pkg);
                Journal.write(this, getString(R.string.j_failed, label(this, pkg)));
            }
        }, 1500);
    }

    private List<ComponentName> entries(String pkg) {
        List<ComponentName> cached = entryCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        List<ComponentName> found = findEntries(getPackageManager(), pkg);
        entryCache.put(pkg, found);
        return found;
    }

    static List<ComponentName> findEntries(PackageManager pm, String pkg) {
        List<ComponentName> found = new ArrayList<>();
        for (String action : ENTRY_ACTIONS) {
            Intent query = new Intent(action).setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            for (ResolveInfo ri : pm.queryBroadcastReceivers(query, 0)) {
                addIfUsable(found, ri.activityInfo);
            }
        }
        // Otherwise (e.g. Doctolib), any exported receiver without permission will do.
        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_RECEIVERS);
            if (info.receivers != null) {
                for (ActivityInfo ai : info.receivers) {
                    addIfUsable(found, ai);
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return found;
    }

    private static void addIfUsable(List<ComponentName> found, ActivityInfo ai) {
        if (ai == null || !ai.exported || !ai.enabled || ai.permission != null) {
            return;
        }
        ComponentName cn = new ComponentName(ai.packageName, ai.name);
        if (!found.contains(cn)) {
            found.add(cn);
        }
    }

    /** User apps (and updated system apps outside vivo/Android) allowed to post notifications. */
    static List<String> watchedPackages(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> result = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (isEligible(context, ai)) {
                result.add(ai.packageName);
            }
        }
        return result;
    }

    static boolean isWatched(Context context, String pkg) {
        if (pkg == null) return false;
        try {
            return isEligible(context, context.getPackageManager().getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean isEligible(Context context, ApplicationInfo ai) {
        String pkg = ai.packageName;
        if (pkg.equals(context.getPackageName()) || !ai.enabled) return false;
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        if (system && !updatedSystem) return false;
        for (String prefix : EXCLUDED_PREFIXES) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix)) return false;
        }
        return context.getPackageManager().checkPermission(Manifest.permission.POST_NOTIFICATIONS, pkg)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isStopped(Context context, String pkg) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
