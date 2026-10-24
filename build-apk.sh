#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
WORKSPACE_DIR="$(CDPATH= cd -- "$PROJECT_DIR/../.." && pwd)"
SOURCE_DIR="$PROJECT_DIR/app/src/main"
BUILD_ROOT="$WORKSPACE_DIR/work/android-build"
SIGNING_ROOT="$WORKSPACE_DIR/work/signing"
OUTPUT_APK="$WORKSPACE_DIR/outputs/简记-2.0.0.apk"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x /usr/libexec/java_home ]; then
        export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    else
        printf '请将 JAVA_HOME 设置为本机 JDK 17 目录。\n' >&2
        exit 1
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
BUILD_TOOLS="$SDK_ROOT/build-tools/36.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
DEPS_ROOT="$WORKSPACE_DIR/work/android-deps"
ZXING_JAR="$DEPS_ROOT/zxing-core-3.5.3.jar"
mkdir -p "$DEPS_ROOT"
if [ ! -f "$ZXING_JAR" ]; then
    curl -fL --retry 2 'https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar' -o "$ZXING_JAR"
fi
ZXING_HASH="$(shasum -a 256 "$ZXING_JAR")"
if [ "${ZXING_HASH%% *}" != '8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82' ]; then
    printf '扫码依赖校验失败：%s\n' "$ZXING_JAR" >&2
    exit 1
fi

for required in "$JAVA_HOME/bin/javac" "$JAVA_HOME/bin/keytool" \
    "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" \
    "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner" \
    "$ANDROID_JAR" "$SOURCE_DIR/AndroidManifest.xml"; do
    if [ ! -f "$required" ]; then
        printf '缺少构建文件：%s\n' "$required" >&2
        exit 1
    fi
done

mkdir -p "$BUILD_ROOT" "$SIGNING_ROOT" "$(dirname -- "$OUTPUT_APK")"
BUILD_DIR="$(mktemp -d "$BUILD_ROOT/build.XXXXXX")"
trap 'rm -rf -- "$BUILD_DIR"' EXIT
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

# Gradle supplies namespace from app/build.gradle; standalone aapt2 needs it in its input.
sed 's/<manifest /<manifest package="com.xiaotu.notes" /' \
    "$SOURCE_DIR/AndroidManifest.xml" > "$BUILD_DIR/AndroidManifest.xml"
"$BUILD_TOOLS/aapt2" compile --dir "$SOURCE_DIR/res" -o "$BUILD_DIR/resources.zip"
LINK_ARGS=(
    link -o "$BUILD_DIR/resources.apk"
    --manifest "$BUILD_DIR/AndroidManifest.xml"
    -I "$ANDROID_JAR"
    --java "$BUILD_DIR/generated"
    --min-sdk-version 26 --target-sdk-version 35
    --version-code 12 --version-name 2.0.0
    --auto-add-overlay
)
if [ -d "$SOURCE_DIR/assets" ]; then
    LINK_ARGS+=(-A "$SOURCE_DIR/assets")
fi
"$BUILD_TOOLS/aapt2" "${LINK_ARGS[@]}" "$BUILD_DIR/resources.zip"

JAVA_SOURCES=()
while IFS= read -r -d '' source; do
    JAVA_SOURCES+=("$source")
done < <(find "$SOURCE_DIR/java" "$BUILD_DIR/generated" -name '*.java' -type f -print0)
if [ "${#JAVA_SOURCES[@]}" -eq 0 ]; then
    printf '未找到 Java 源码。\n' >&2
    exit 1
fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 \
    -classpath "$ANDROID_JAR:$ZXING_JAR" -d "$BUILD_DIR/classes" "${JAVA_SOURCES[@]}"

"$JAVA_HOME/bin/jar" cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar" "$ZXING_JAR"
cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/unsigned.apk"
(
    cd "$BUILD_DIR/dex"
    zip -q -0 "$BUILD_DIR/unsigned.apk" classes*.dex
)
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

# A persistent development key lets later local builds update the installed APK.
KEYSTORE="$SIGNING_ROOT/xiaotu-notes-development.keystore"
export XIAOTU_STORE_PASSWORD="${XIAOTU_STORE_PASSWORD:-android}"
export XIAOTU_KEY_PASSWORD="${XIAOTU_KEY_PASSWORD:-$XIAOTU_STORE_PASSWORD}"
if [ ! -f "$KEYSTORE" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$KEYSTORE" \
        -storetype JKS -storepass:env XIAOTU_STORE_PASSWORD \
        -keypass:env XIAOTU_KEY_PASSWORD -alias xiaotu-notes \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Xiaotu Notes Development, O=Xiaotu Notes, C=CN' >/dev/null
    chmod 600 "$KEYSTORE"
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias xiaotu-notes \
    --ks-pass env:XIAOTU_STORE_PASSWORD --key-pass env:XIAOTU_KEY_PASSWORD \
    --out "$BUILD_DIR/signed.apk" "$BUILD_DIR/aligned.apk"
mv -f "$BUILD_DIR/signed.apk" "$OUTPUT_APK"
printf 'APK 已生成：%s\n' "$OUTPUT_APK"
