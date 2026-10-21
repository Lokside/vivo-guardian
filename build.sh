#!/usr/bin/env bash
# Builds Guardian.apk without Gradle (aapt2 + javac + d8 + apksigner).
# Requirements: JDK 17+, Android SDK (platform 36, build-tools 36.0.0).
# Signing: release key if KEYSTORE is set (KEYSTORE_PASS, KEY_ALIAS), otherwise the debug key.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=12
VERSION_NAME=12.0

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
JAR="$SDK/platforms/android-36/android.jar"
OUT=build
JDK="${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.home = //p' | tr -d '\r')}"
EXE=; BAT=
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=.exe; BAT=.bat ;; esac

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2$EXE" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2$EXE" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"

javac --release 17 -cp "$JAR" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
"$BT/d8$BAT" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$JDK/bin/jar" --update --no-manifest --file "$OUT/unsigned.apk" -C "$OUT/dex" classes.dex

"$BT/zipalign$EXE" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ -n "${KEYSTORE:-}" ]; then
  KS="$KEYSTORE"; KS_PASS="${KEYSTORE_PASS:?KEYSTORE_PASS missing}"; ALIAS="${KEY_ALIAS:-notifguard}"
else
  # Local debug key (Android convention: password "android").
  KS="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"; KS_PASS=android; ALIAS=androiddebugkey
  [ -f "$KS" ] || { mkdir -p "$(dirname "$KS")"; "$JDK/bin/keytool" -genkeypair -keystore "$KS" \
    -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1; }
fi

"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$ALIAS" \
  --out Guardian.apk "$OUT/aligned.apk"
echo "OK -> $(pwd)/Guardian.apk (version $VERSION_NAME)"
