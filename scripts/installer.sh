#!/usr/bin/env bash
# Installe et configure le Gardien notifs sur un telephone vivo branche en USB (macOS / Linux).
# Usage : ./installer.sh [chemin/vers/GardienNotifs-v11.apk]
set -euo pipefail
PKG=com.phili.notifguard
SVC=$PKG/$PKG.GuardService
DIR="$(cd "$(dirname "$0")" && pwd)"

APK="${1:-$(ls "$DIR"/GardienNotifs*.apk "$DIR"/../GardienNotifs*.apk "$DIR"/../NotifGuard.apk 2>/dev/null | tail -1 || true)}"
[ -n "$APK" ] && [ -f "$APK" ] || { echo "APK introuvable : ./installer.sh chemin/vers/GardienNotifs-v11.apk"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "Aucun telephone detecte (debogage USB active ? empreinte du PC acceptee ?)"; exit 1; }

echo "[1/7] Installation de $APK — ACCEPTEZ L'INSTALLATION SUR LE TELEPHONE (la fenetre vivo expire vite)"
adb install -r "$APK"
echo "[2/7] Autorisation « liste des apps installées » (GET_INSTALLED_APPS)"
adb shell pm grant $PKG com.android.permission.GET_INSTALLED_APPS
echo "[3/7] Paramètres restreints autorisés"
adb shell cmd appops set $PKG ACCESS_RESTRICTED_SETTINGS allow
echo "[4/7] Autorisations jamais retirées si l'app reste longtemps sans être ouverte"
adb shell cmd appops set $PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
echo "[5/7] Exemption de l'optimisation batterie Android"
adb shell cmd deviceidle whitelist +$PKG
echo "[6/7] Activation du service d'accessibilité (les autres services actifs sont conservés)"
adb shell "cur=\$(settings get secure enabled_accessibility_services); case \${cur:-null} in *$SVC*) ;; null) settings put secure enabled_accessibility_services $SVC ;; *) settings put secure enabled_accessibility_services \$cur:$SVC ;; esac; settings put secure accessibility_enabled 1"
echo "[7/7] GmsCore (microG de ReVanced / Morphe), s'il est installé"
adb shell "pm path app.revanced.android.gms >/dev/null 2>&1 && cmd deviceidle whitelist +app.revanced.android.gms || echo '      GmsCore absent : rien à faire'"

adb shell am start -n $PKG/.MainActivity >/dev/null
cat <<'EOF'

Le Gardien est ouvert sur le téléphone. À faire UNE FOIS, sur le téléphone :
  1. Bouton « Verrouiller dans les apps récentes » et suivre les 3 étapes (cadenas vivo).
  2. Vérifier « ✅ Service actif » en haut de l'écran du Gardien.
     Sinon : « Réglages d'accessibilité » → Gardien notifs → activer → bouton Accueil,
     puis revenir vérifier après 1 minute.
EOF
