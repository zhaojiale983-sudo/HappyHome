#!/usr/bin/env bash
# ============================================================
# HappyHome 原生版编译脚本
# 同时适用于：GitHub Actions（Ubuntu 自带 Android SDK）和 Termux 本地
#
# 依赖：aapt2 / javac / d8(或 dx) / apksigner / keytool / zip
# 签名：优先用 keystore/debug.keystore；没有就用 keystore/debug.keystore.b64 还原；
#       再没有就现场生成一个（注意：现场生成每次签名都不同）
# ============================================================
set -e
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
APP_DIR="app"

# ---------- 定位工具 ----------
find_tool() {
    # $1 = 工具名；找不到就返回空
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

[ -n "$AAPT2" ] || { echo "❌ 找不到 aapt2（装 build-tools，或 pkg install aapt2）"; exit 1; }
[ -n "$APKSIGNER" ] || { echo "❌ 找不到 apksigner（pkg install apksigner）"; exit 1; }

# ---------- 定位 android.jar ----------
ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ]; then
    # 优先 android-33，没有就用机器上最高的那个平台
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
[ -n "$ANDROID_JAR" ] || { echo "❌ 找不到 android.jar（装 platforms;android-33）"; exit 1; }
echo "▶ android.jar = $ANDROID_JAR"

# ---------- 选 d8 还是 dx ----------
USE_D8=0
if [ -n "$D8" ] && [ -x "$D8" ]; then USE_D8=1;
elif [ -n "$DX" ] && [ -x "$DX" ]; then USE_D8=0;
else echo "❌ 找不到 d8 / dx"; exit 1; fi

# ---------- 签名文件 ----------
KS="keystore/debug.keystore"
if [ ! -f "$KS" ] && [ -f "keystore/debug.keystore.b64" ]; then
    echo "▶ 用 keystore/debug.keystore.b64 还原签名文件"
    base64 -d keystore/debug.keystore.b64 > "$KS" 2>/dev/null || \
    base64 --decode keystore/debug.keystore.b64 > "$KS"
fi
if [ ! -f "$KS" ]; then
    echo "▶ （没有签名文件，现场生成一个）"
    mkdir -p keystore
    "$KEYTOOL" -genkeypair -v -keystore "$KS" \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

# ---------- assets 有就带上，没有就跳过 ----------
ASSETS_FLAG=""
if [ -d "$APP_DIR/assets" ]; then ASSETS_FLAG="-A $APP_DIR/assets"; fi

rm -rf out
mkdir -p out/gen out/classes out/dex

echo "▶ 1/5 编译资源"
"$AAPT2" compile --dir "$APP_DIR/res" -o out/res.zip

echo "▶ 2/5 链接清单，生成 R.java"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$APP_DIR/AndroidManifest.xml" \
    -R out/res.zip $ASSETS_FLAG -o out/base.apk --java out/gen --auto-add-overlay

echo "▶ 3/5 编译 Java（用 -cp，不要用 -bootclasspath）"
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d out/classes \
    $(find "$APP_DIR/src" out/gen -name '*.java')

echo "▶ 4/5 生成 classes.dex"
if [ "$USE_D8" = "1" ]; then
    "$D8" --min-api 21 --lib "$ANDROID_JAR" --output out/dex $(find out/classes -name '*.class')
else
    "$DX" --dex --output=out/dex/classes.dex $(find out/classes -name '*.class')
fi
( cd out/dex && zip -q -u ../base.apk classes.dex )

echo "▶ 5/5 对齐 + 签名"
if [ -n "$ZIPALIGN" ] && [ -x "$ZIPALIGN" ]; then
    "$ZIPALIGN" -f 4 out/base.apk out/aligned.apk
else
    cp out/base.apk out/aligned.apk
fi

"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out HappyHome-Native.apk out/aligned.apk

echo "✅ 完成：$ROOT/HappyHome-Native.apk"
