package com.phili.notifguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Temporary screen, the only one of the app shown in recent apps: it is used to put the vivo lock on
 * Guardian's card once. vivo remembers this lock per app (even after a reboot); without it, "Clear all"
 * force-stops Guardian.
 * "Done" (or Back) removes the card without stopping the app (swiping the card away would force-stop it).
 */
public class LockActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MainActivity.fitSystemBars(root, pad);

        TextView steps = new TextView(this);
        steps.setTextSize(16);
        steps.setText(R.string.lock_steps);
        root.addView(steps);

        Button done = new Button(this);
        done.setText(R.string.lock_done);
        done.setOnClickListener(v -> finish());
        root.addView(done);

        setContentView(root);
    }

    /**
     * "Done" and Back both go back to the main screen and remove the card: a plain finish() would leave
     * an empty card in recent apps, and whatever was under this screen (often the home screen, after a
     * detour through recent apps) would show instead of Guardian.
     */
    @Override
    public void finish() {
        // Same intent as the launcher icon: brings the main screen back instead of stacking a second one.
        Intent main = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (main != null) startActivity(main);
        finishAndRemoveTask();
    }
}
