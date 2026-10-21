package com.phili.notifguard;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
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
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

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
 */
public class GuardService extends AccessibilityService {

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
    private static final long EVENT_SWEEP_MIN_GAP_MS = 20 * 1000;
    private static final long REWAKE_MIN_GAP_MS = 10 * 1000;
    private static final int MAX_ATTEMPTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, List<ComponentName>> entryCache = new HashMap<>();
    private final Map<String, Long> lastWake = new HashMap<>();
    // Apps left alone until their state changes (reopened, updated or force-stopped again):
    // stopped by the user, without entry point, or whose unblocking failed.
    private final Set<String> leftAlone = new HashSet<>();
    // Cached list of watched apps: rebuilt (scan of all installed apps) only when an app is installed /
    // updated / removed, when the screen turns on and every 5 min.
    private List<String> watchedCache;
    private long lastSweep;
    private String foregroundPkg = "";

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
            } else {
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

    @Override
    protected void onServiceConnected() {
        IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_RESTARTED);
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_USER_PRESENT);
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            foregroundPkg = pkg.toString();
        }
        // Every window change (back to home, opening an app...) serves as a reminder.
        if (SystemClock.elapsedRealtime() - lastSweep > EVENT_SWEEP_MIN_GAP_MS) {
            sweep(null, false); // light check: only the apps already watched
        }
    }

    /** A "Force stop" made by the user necessarily comes from the Settings app in the foreground. */
    private boolean stoppedByUser() {
        return foregroundPkg.startsWith(SETTINGS_PKG);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        Journal.write(this, getString(R.string.j_service_stopped));
        super.onDestroy();
    }

    private List<String> watched(boolean refresh) {
        if (refresh || watchedCache == null) {
            watchedCache = watchedPackages(this);
        }
        return watchedCache;
    }

    private void sweep(String reason, boolean refreshList) {
        lastSweep = SystemClock.elapsedRealtime();
        for (String pkg : watched(refreshList)) {
            if (!isStopped(this, pkg)) {
                lastWake.remove(pkg); // the app was reopened: eligible again
                leftAlone.remove(pkg);
                continue;
            }
            if (leftAlone.contains(pkg)) continue;
            Long last = lastWake.get(pkg);
            if (last != null && SystemClock.elapsedRealtime() - last < REWAKE_MIN_GAP_MS) continue;
            Journal.write(this, reason == null
                    ? getString(R.string.j_found_blocked, label(this, pkg))
                    : getString(R.string.j_found_blocked_reason, label(this, pkg), reason));
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
