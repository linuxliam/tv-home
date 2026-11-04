#!/usr/bin/env bash
# Builds launcher/build/home.apk with the Android command-line tools (no Gradle).
#   ./build.sh            build
#   ./build.sh install    build, then adb install to the TV (ADB_TARGET=ip:port)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
if [ ! -x "$BT/aapt2" ] || [ ! -f "$JAR" ]; then
  echo "Android SDK incomplete. Need build-tools/34.0.0 and platforms/android-34 under:" >&2
  echo "  $SDK" >&2
  echo "Set ANDROID_HOME (or ANDROID_SDK_ROOT) to your SDK root." >&2
  exit 1
fi
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT/classes"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 "$OUT/res.zip"

javac --release 8 -Xlint:-options -cp "$JAR" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 26 --output "$OUT" $(find "$OUT/classes" -name '*.class')
(cd "$OUT" && zip -q base.apk classes.dex)

[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Home Launcher" >/dev/null 2>&1
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$OUT/home.apk" "$OUT/aligned.apk"
echo "Built $OUT/home.apk"

if [ "${1:-}" = "install" ]; then
  adb ${ADB_TARGET:+-s "$ADB_TARGET"} install -r "$OUT/home.apk"
fi
