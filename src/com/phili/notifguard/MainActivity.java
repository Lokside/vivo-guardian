package com.phili.notifguard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Service state, watched apps and unblocking log. */
public class MainActivity extends Activity {
    // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is hidden from the SDK.
    private static final String ENABLED_LISTENERS = "enabled_notification_listeners";
    private static final String GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS";

    private TextView status;
    // A screen opened from here is on top: this screen is kept under it, so that Back returns here.
    private boolean childOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        fitSystemBars(root, pad);

        Button settings = new Button(this);
        settings.setText(R.string.btn_notification_access);
        settings.setOnClickListener(v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(settings);

        Button usage = new Button(this);
        usage.setText(R.string.btn_usage_access);
        usage.setOnClickListener(v -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        root.addView(usage);

        Button appInfo = new Button(this);
        appInfo.setText(R.string.btn_app_info);
        appInfo.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        root.addView(appInfo);

        Button lock = new Button(this);
        lock.setText(R.string.btn_lock);
        lock.setOnClickListener(v -> {
            childOpen = true;
            startActivity(new Intent(this, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        root.addView(lock);

        Button refresh = new Button(this);
        refresh.setText(R.string.btn_refresh);
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

    /**
     * Opens a settings screen as the only page of the Settings task (not in Guardian's task, which must
     * never get a card in recent apps): Back then returns here, not to a Settings page left open before.
     */
    private void open(Intent settings) {
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
        childOpen = true;
        try {
            startActivity(settings.addFlags(flags));
        } catch (ActivityNotFoundException | SecurityException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(flags));
        }
    }

    /**
     * Since Android 15 the content is drawn behind the status and navigation bars: keep it clear of them.
     */
    @SuppressWarnings("deprecation") // getSystemWindowInset*: the replacement only exists from API 30
    static void fitSystemBars(View root, int pad) {
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    /**
     * Notification access is granted but Android has not bound the listener: its bind was refused when
     * the app was not running (vivo blocks the start of an app without "autostart") and Android does not
     * try again on its own. Unbind + rebind makes it bind now, while this screen keeps the app running.
     */
    private void rebind() {
        ComponentName me = new ComponentName(this, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                NotificationListenerService.requestUnbind(me);
            }
            NotificationListenerService.requestRebind(me);
        } catch (RuntimeException e) {
            Journal.write(this, "rebind: " + e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        childOpen = false;
        if (checkSelfPermission(GET_INSTALLED_APPS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{GET_INSTALLED_APPS}, 1);
        }
        if (accessGranted() && !GuardService.connected) {
            rebind();
            status.postDelayed(this::render, 1500);
            status.postDelayed(this::render, 4000);
        }
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Never leave a card in recent apps. Under a screen opened from here the task is kept (it is
        // excluded from recent apps anyway) and removed the next time this screen is left.
        if (!childOpen && !isChangingConfigurations()) {
            finishAndRemoveTask();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        render();
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        int state = !accessGranted() ? R.string.status_off
                : GuardService.connected ? R.string.status_on : R.string.status_waiting;
        sb.append(getString(state)).append('\n');
        sb.append(getString(GuardService.hasUsageAccess(this) ? R.string.usage_on : R.string.usage_off))
                .append("\n\n");
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
        sb.append(getString(R.string.watched_count, watched.size())).append("\n\n");
        sb.append(getString(R.string.blocked_now)).append('\n')
                .append(blocked.length() == 0 ? "  " + getString(R.string.none_ok) + "\n" : blocked);
        sb.append('\n').append(getString(R.string.cannot_unblock)).append('\n')
                .append(noEntry.length() == 0 ? "  " + getString(R.string.none) + "\n" : noEntry);
        sb.append('\n').append(getString(R.string.journal_header)).append('\n');
        List<String> lines = Journal.newestFirst(this);
        if (lines.isEmpty()) {
            sb.append("  ").append(getString(R.string.journal_empty)).append('\n');
        } else {
            for (String l : lines) sb.append(l).append('\n');
        }
        status.setText(sb);
    }

    /** Notification access granted to the service in the system settings. */
    private boolean accessGranted() {
        String enabled = Settings.Secure.getString(getContentResolver(), ENABLED_LISTENERS);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(this, GuardService.class).flattenToString();
        String meShort = new ComponentName(this, GuardService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }
}
