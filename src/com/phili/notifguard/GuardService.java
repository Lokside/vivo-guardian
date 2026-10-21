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
 * vivo (ROM chinoise) arrete de force une app quand le doigt se pose sur son icone sans l'ouvrir
 * (pre-demarrage du lanceur). Une app arretee de force ne recoit plus ses notifications Google
 * tant qu'on ne la rouvre pas. Ce service la debloque aussitot en envoyant un signal a l'un de ses
 * recepteurs exportes (ce qui leve l'etat "stopped"), sans afficher l'app.
 *
 * Apps surveillees : toutes les apps installees par l'utilisateur (et apps Google mises a jour)
 * qui ont le droit d'afficher des notifications, hors apps systeme vivo / Android.
 */
public class GuardService extends AccessibilityService {

    private static final String WAKE_ACTION = "com.phili.notifguard.WAKE";
    // Actions dont les recepteurs sont presque toujours exportes et sans permission.
    private static final String[] ENTRY_ACTIONS = {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
    };
    // Apps systeme jamais reveillees (services chinois desactives, socle Android).
    private static final String[] EXCLUDED_PREFIXES = {
            "android", "com.android.", "com.vivo.", "com.bbk.", "com.iqoo.", "vivo.", "com.mediatek.",
    };
    private static final String SETTINGS_PKG = "com.android.settings";
    // GmsCore (microG de ReVanced) : c'est lui qui recoit les notifications de YouTube Morphe. vivo le tue
    // (memoire) sans l'arreter de force, et il ne se reconnecte pas toujours. Un signal "doux" a son
    // TriggerReceiver le reconnecte s'il est deconnecte, sinon il ne fait qu'un ping (connexion conservee).
    private static final ComponentName GMSCORE_TRIGGER = new ComponentName(
            "app.revanced.android.gms", "org.microg.gms.gcm.TriggerReceiver");
    private static final long SWEEP_PERIOD_MS = 5 * 60 * 1000;
    private static final long EVENT_SWEEP_MIN_GAP_MS = 20 * 1000;
    private static final long REWAKE_MIN_GAP_MS = 10 * 1000;
    private static final int MAX_ATTEMPTS = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, List<ComponentName>> entryCache = new HashMap<>();
    private final Map<String, Long> lastWake = new HashMap<>();
    // Apps a ne plus toucher tant que leur etat ne change pas (rouvertes, mises a jour ou arretees a nouveau) :
    // arretees par l'utilisateur, sans point d'entree, ou dont le deblocage a echoue.
    private final Set<String> leftAlone = new HashSet<>();
    // Liste memorisee des apps surveillees : recalculee (parcours de toutes les apps installees) seulement
    // a l'installation / mise a jour / suppression d'une app, a l'allumage de l'ecran et toutes les 5 min.
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
                    Journal.write(context, label(context, pkg) + " arrêté depuis les Paramètres : laissé arrêté");
                    leftAlone.add(pkg);
                    return;
                }
                leftAlone.remove(pkg); // nouvel arret : on retente une fois
                Journal.write(context, "arrêt forcé détecté : " + label(context, pkg));
                // Laisse le systeme finir l'arret avant de reveiller l'app.
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
                sweep("écran allumé", true);
                pokeGmsCore();
            }
        }
    };

    private final Runnable periodicSweep = new Runnable() {
        @Override
        public void run() {
            sweep("contrôle périodique", true);
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

        Journal.write(this, "service démarré (" + watched(true).size() + " apps surveillées sur "
                + getPackageManager().getInstalledApplications(0).size() + " installées visibles)");
        sweep("démarrage du service", false);
        if (pokeGmsCore()) {
            Journal.write(this, "GmsCore : maintien de la connexion actif (allumage d'écran + toutes les 5 min)");
        }
        handler.postDelayed(periodicSweep, SWEEP_PERIOD_MS);
    }

    /** Envoie le signal doux a GmsCore s'il est installe. Renvoie false s'il est absent. */
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
        // Chaque changement de fenetre (retour a l'accueil, ouverture d'une app...) sert de rappel.
        if (SystemClock.elapsedRealtime() - lastSweep > EVENT_SWEEP_MIN_GAP_MS) {
            sweep(null, false); // controle leger : seulement les apps deja surveillees
        }
    }

    /** Un "Forcer l'arret" fait par l'utilisateur vient forcement de l'app Parametres au premier plan. */
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
        Journal.write(this, "service arrêté");
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
                lastWake.remove(pkg); // l'app a ete rouverte : de nouveau eligible
                leftAlone.remove(pkg);
                continue;
            }
            if (leftAlone.contains(pkg)) continue;
            Long last = lastWake.get(pkg);
            if (last != null && SystemClock.elapsedRealtime() - last < REWAKE_MIN_GAP_MS) continue;
            Journal.write(this, label(this, pkg) + " trouvé bloqué"
                    + (reason == null ? "" : " (" + reason + ")"));
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
            Journal.write(this, label(this, pkg) + " : aucun point d'entrée, impossible de le débloquer"
                    + " (laissé tranquille jusqu'à sa prochaine ouverture)");
            return;
        }
        ComponentName target = entries.get(attempt % entries.size());
        Intent intent = new Intent(WAKE_ACTION)
                .setComponent(target)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(intent);

        handler.postDelayed(() -> {
            if (!isStopped(this, pkg)) {
                Journal.write(this, label(this, pkg) + " débloqué ✓");
            } else if (attempt + 1 < MAX_ATTEMPTS) {
                wake(pkg, attempt + 1);
            } else {
                leftAlone.add(pkg);
                Journal.write(this, label(this, pkg) + " : échec du déblocage (vivo bloque peut-être le réveil ; "
                        + "autoriser son démarrage associé) — laissé tranquille jusqu'à sa prochaine ouverture");
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
        // A defaut (ex. Doctolib), n'importe quel recepteur exporte et sans permission fait l'affaire.
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

    /** Apps de l'utilisateur (et apps systeme mises a jour hors vivo/Android) autorisees a notifier. */
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
