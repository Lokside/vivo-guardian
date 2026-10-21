package com.phili.notifguard;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Ecran temporaire, le seul de l'app visible dans les apps recentes : il sert a poser une fois le cadenas
 * vivo sur la carte du Gardien. vivo memorise ce cadenas par app (meme apres redemarrage) ; sans lui,
 * "Tout effacer" arrete de force le Gardien et Android desactive son service d'accessibilite.
 * "Termine" retire la carte sans l'arreter (un balayage de la carte, lui, l'arreterait de force).
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
        steps.setText("Verrouiller le Gardien dans les apps récentes (une seule fois)\n\n"
                + "1. Ouvrez les apps récentes (glisser depuis le bas de l'écran et maintenir).\n\n"
                + "2. Posez le cadenas sur la carte « Gardien notifs » "
                + "(selon la version : glisser la carte vers le bas, ou appui long puis Verrouiller).\n\n"
                + "3. Touchez la carte pour revenir ici, puis appuyez sur « Terminé ».\n\n"
                + "⚠️ Ne fermez jamais cette carte en la balayant vers le haut : vivo arrêterait le Gardien "
                + "de force et il faudrait réactiver son service d'accessibilité.\n\n"
                + "Le cadenas reste mémorisé après « Terminé », après un redémarrage et après une mise à jour. "
                + "Il ne se perd qu'en désinstallant l'app.");
        root.addView(steps);

        Button done = new Button(this);
        done.setText("Terminé : retirer la carte");
        done.setOnClickListener(v -> finishAndRemoveTask());
        root.addView(done);

        setContentView(root);
    }
}
