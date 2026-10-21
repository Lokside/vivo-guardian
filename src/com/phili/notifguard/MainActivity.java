package com.phili.notifguard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Etat du service, des apps surveillees et journal des deblocages. */
public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        Button settings = new Button(this);
        settings.setText("Réglages d'accessibilité");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(settings);

        Button lock = new Button(this);
        lock.setText("Verrouiller dans les apps récentes (une fois)");
        lock.setOnClickListener(v -> startActivity(new Intent(this, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        root.addView(lock);

        Button refresh = new Button(this);
        refresh.setText("Rafraîchir");
        refresh.setOnClickListener(v -> render());
        root.addView(refresh);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(GET_INSTALLED_APPS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{GET_INSTALLED_APPS}, 1);
        }
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Ne laisser aucune carte dans les apps recentes.
        if (!isChangingConfigurations()) {
            finishAndRemoveTask();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        render();
    }

    private static final String GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS";

    private void render() {
        StringBuilder sb = new StringBuilder();
        sb.append(serviceEnabled() ? "✅ Service actif\n\n"
                : "❌ Service désactivé : Réglages d'accessibilité → Gardien notifs → activer\n\n");
        List<String> watched = GuardService.watchedPackages(this);
        StringBuilder blocked = new StringBuilder();
        StringBuilder noEntry = new StringBuilder();
        for (String pkg : watched) {
            String name = GuardService.label(this, pkg);
            if (GuardService.isStopped(this, pkg)) blocked.append("  ⛔ ").append(name).append('\n');
            if (GuardService.findEntries(getPackageManager(), pkg).isEmpty()) {
                noEntry.append("  • ").append(name).append('\n');
            }
        }
        sb.append(watched.size()).append(" apps surveillées (autorisées à notifier).\n\n");
        sb.append("Bloquées en ce moment :\n").append(blocked.length() == 0 ? "  aucune ✓\n" : blocked);
        sb.append("\nImpossibles à débloquer (aucun point d'entrée) :\n")
                .append(noEntry.length() == 0 ? "  aucune\n" : noEntry);
        sb.append("\nJournal (récent en haut) :\n");
        List<String> lines = Journal.newestFirst(this);
        if (lines.isEmpty()) {
            sb.append("  (vide)\n");
        } else {
            for (String l : lines) sb.append(l).append('\n');
        }
        status.setText(sb);
    }

    private boolean serviceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(this, GuardService.class).flattenToString();
        String meShort = new ComponentName(this, GuardService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }
}
