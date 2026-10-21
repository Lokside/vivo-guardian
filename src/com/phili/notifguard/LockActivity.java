package com.phili.notifguard;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Temporary screen, the only one of the app shown in recent apps: it is used to put the vivo lock on
 * Guardian's card once. vivo remembers this lock per app (even after a reboot); without it, "Clear all"
 * force-stops Guardian and Android turns its accessibility service off.
 * "Done" removes the card without stopping the app (swiping the card away would force-stop it).
 */
public class LockActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 3, pad, pad);

        TextView steps = new TextView(this);
        steps.setTextSize(16);
        steps.setText(R.string.lock_steps);
        root.addView(steps);

        Button done = new Button(this);
        done.setText(R.string.lock_done);
        done.setOnClickListener(v -> finishAndRemoveTask());
        root.addView(done);

        setContentView(root);
    }
}
