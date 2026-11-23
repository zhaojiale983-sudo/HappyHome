#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
APP_DIR="app"

find_tool() {
    if command -v "$1" >/dev/null 2>&1; then command -v "$1"; return; fi
    if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
        local hit
        hit=$(ls -1 "$ANDROID_HOME/build-tools"/*/"$1" 2>/dev/null | sort -r | head -1)
        if [ -n "$hit" ]; then echo "$hit"; return; fi
    fi
    for base in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /usr/local/lib/android/sdk; do
        if [ -d "$base/build-tools" ]; then
            local hit2
            hit2=$(ls -1 "$base/build-tools"/*/"$1" 2>/dev/null | sort -r | head -1)
            if [ -n "$hit2" ]; then echo "$hit2"; return; fi
        fi
    done
}

AAPT2="${AAPT2:-$(find_tool aapt2)}"
JAVAC="${JAVAC:-$(find_tool javac)}"
D8="${D8:-$(find_tool d8)}"
DX="${DX:-$(find_tool dx)}"
ZIPALIGN="${ZIPALIGN:-$(find_tool zipalign)}"
APKSIGNER="${APKSIGNER:-$(find_tool apksigner)}"
KEYTOOL="${KEYTOOL:-$(find_tool keytool)}"

[ -n "$JAVAC" ] || JAVAC="javac"
[ -n "$KEYTOOL" ] || KEYTOOL="keytool"
[ -n "$AAPT2" ] || { echo "找不到 aapt2"; exit 1; }
[ -n "$APKSIGNER" ] || { echo "找不到 apksigner"; exit 1; }

ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ]; then
    for cand in \
        "$ANDROID_HOME/platforms/android-33/android.jar" \
        "$HOME/Android/Sdk/platforms/android-33/android.jar" \
        "$HOME/Library/Android/sdk/platforms/android-33/android.jar" \
        /usr/local/lib/android/sdk/platforms/android-33/android.jar \
        "$ROOT/android.jar"; do
        if [ -f "$cand" ]; then ANDROID_JAR="$cand"; break; fi
    done
fi
if [ -z "$ANDROID_JAR" ]; then
    for base in "$ANDROID_HOME" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /usr/local/lib/android/sdk; do
        [ -d "$base/platforms" ] || continue
        cand=$(ls -1 "$base"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)
        if [ -n "$cand" ]; then ANDROID_JAR="$cand"; break; fi
    done
fi


rm -rf out
mkdir -p out/gen out/classes out/dex

echo "1/5 编译资源"
"$AAPT2" compile --dir "$APP_DIR/res" -o out/res.zip

echo "2/5 链接清单，生成 R.java"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$APP_DIR/AndroidManifest.xml" \
    -R out/res.zip -A "$APP_DIR/assets" -o out/base.apk --java out/gen --auto-add-overlay

echo "3/5 编译 Java"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d out/classes \
    $(find "$APP_DIR/src" out/gen -name '*.java')

echo "4/5 生成 classes.dex"
if [ "$USE_D8" = "1" ]; then
    "$D8" --min-api 21 --lib "$ANDROID_JAR" --output out/dex $(find out/classes -name '*.class')
else
    "$DX" --dex --output=out/dex/classes.dex $(find out/classes -name '*.class')
fi
( cd out/dex && zip -q -u ../base.apk classes.dex )

echo "5/5 对齐 + 签名"
if [ -n "$ZIPALIGN" ] && [ -x "$ZIPALIGN" ]; then
    "$ZIPALIGN" -f 4 out/base.apk out/aligned.apk
else
    cp out/base.apk out/aligned.apk
fi

KS="keystore/debug.keystore"
if [ ! -f "$KS" ]; then
    mkdir -p keystore
    "$KEYTOOL" -genkeypair -v -keystore "$KS" \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out HappyHome-Native.apk out/aligned.apk
"$APKSIGNER" verify HappyHome-Native.apk
echo "完成：$ROOT/HappyHome-Native.apk"
