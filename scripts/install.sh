#!/usr/bin/env bash
# Installs and sets up Guardian on a vivo phone plugged in over USB (macOS / Linux).
# Usage: ./install.sh [path/to/Guardian-v12.apk]
set -euo pipefail
PKG=com.phili.notifguard
SVC=$PKG/$PKG.GuardService
DIR="$(cd "$(dirname "$0")" && pwd)"

APK="${1:-$(ls "$DIR"/Guardian*.apk "$DIR"/../Guardian*.apk 2>/dev/null | tail -1 || true)}"
[ -n "$APK" ] && [ -f "$APK" ] || { echo "APK not found: ./install.sh path/to/Guardian-v12.apk"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No phone detected (USB debugging on? computer fingerprint accepted?)"; exit 1; }

echo "[1/7] Installing $APK — ACCEPT THE INSTALL ON THE PHONE (the vivo prompt expires quickly)"
adb install -r "$APK"
echo "[2/7] Permission \"list of installed apps\" (GET_INSTALLED_APPS)"
adb shell pm grant $PKG com.android.permission.GET_INSTALLED_APPS
echo "[3/7] Restricted settings allowed"
adb shell cmd appops set $PKG ACCESS_RESTRICTED_SETTINGS allow
echo "[4/7] Permissions never removed if the app is not opened for a long time"
adb shell cmd appops set $PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
echo "[5/7] Exemption from Android battery optimisation"
adb shell cmd deviceidle whitelist +$PKG
echo "[6/7] Turning on the accessibility service (other active services are kept)"
adb shell "cur=\$(settings get secure enabled_accessibility_services); case \${cur:-null} in *$SVC*) ;; null) settings put secure enabled_accessibility_services $SVC ;; *) settings put secure enabled_accessibility_services \$cur:$SVC ;; esac; settings put secure accessibility_enabled 1"
echo "[7/7] GmsCore (ReVanced / Morphe microG), if installed"
adb shell "pm path app.revanced.android.gms >/dev/null 2>&1 && cmd deviceidle whitelist +app.revanced.android.gms || echo '      GmsCore not installed: nothing to do'"

adb shell am start -n $PKG/.MainActivity >/dev/null
cat <<'EOF'

Guardian is open on the phone. To do ONCE, on the phone:
  1. "Lock in recent apps" button, then follow the 3 steps (vivo lock).
  2. Check "✅ Service active" at the top of Guardian's screen.
     Otherwise: "Accessibility settings" → Guardian → turn on → Home button,
     then come back and check after 1 minute.
EOF
