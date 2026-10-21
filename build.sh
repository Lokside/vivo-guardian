#!/usr/bin/env bash
# Compile NotifGuard.apk sans Gradle (aapt2 + javac + d8 + apksigner).
# Prerequis : JDK 17+, Android SDK (platform 36, build-tools 36.0.0).
# Signature : cle de publication si KEYSTORE est defini (KEYSTORE_PASS, KEY_ALIAS), sinon cle de debug.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=11
VERSION_NAME=11.0

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
JAR="$SDK/platforms/android-36/android.jar"
OUT=build
JDK="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.home = //p' | tr -d '\r')}"
EXE=; BAT=
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=.exe; BAT=.bat ;; esac

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

"$BT/aapt2$EXE" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2$EXE" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$OUT/unsigned.apk" "$OUT/res.zip"

javac --release 17 -cp "$JAR" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8$BAT" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$JDK/bin/jar" --update --no-manifest --file "$OUT/unsigned.apk" -C "$OUT/dex" classes.dex

"$BT/zipalign$EXE" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ -n "${KEYSTORE:-}" ]; then
  KS="$KEYSTORE"; KS_PASS="${KEYSTORE_PASS:?KEYSTORE_PASS manquant}"; ALIAS="${KEY_ALIAS:-notifguard}"
else
  # Cle de debug locale (convention Android : mot de passe "android").
  KS="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"; KS_PASS=android; ALIAS=androiddebugkey
  [ -f "$KS" ] || { mkdir -p "$(dirname "$KS")"; "$JDK/bin/keytool" -genkeypair -keystore "$KS" \
    -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1; }
fi

"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$ALIAS" \
  --out NotifGuard.apk "$OUT/aligned.apk"
echo "OK -> $(pwd)/NotifGuard.apk (version $VERSION_NAME)"
