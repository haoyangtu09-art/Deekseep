#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

API_JAR="libs/api.jar"
source ../scripts/android-tools.sh

OUT=build
rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex

echo "[0/6] generate BuildInfo.java (api version + build date)"
API_VER=$(grep -oE 'targetApiVersion=[0-9]+' xposed/module.prop | cut -d= -f2)
MODULE_VER=$(grep -oE 'android:versionName="[^"]+"' AndroidManifest.xml | head -n1 | cut -d'"' -f2)
cat > src/com/dsmod/probe/BuildInfo.java <<EOF
package com.dsmod.probe;
public final class BuildInfo {
    public static final String API_VERSION = "${API_VER:-102}";
    public static final String MODULE_VERSION = "${MODULE_VER:-unknown}";
    public static final String BUILD_DATE = "$(date '+%Y-%m-%d %H:%M')";
    private BuildInfo() {}
}
EOF

echo "[1/6] javac (module against libxposed api, compileOnly)"
find src -name "*.java" > $OUT/sources.txt
if ! javac -source 8 -target 8 -cp "$ANDROID_JAR:$API_JAR" -d $OUT/classes @$OUT/sources.txt 2> $OUT/javac.err; then
  cat $OUT/javac.err
  exit 1
fi
grep -v "warning:" $OUT/javac.err || true

echo "[2/6] d8 (only module classes -> dex; api provided by framework)"
# Package only module classes; the framework provides libxposed at runtime.
MODCLASSES=$(find $OUT/classes/com/dsmod -name "*.class")
$D8 --min-api 24 --output $OUT/dex $MODCLASSES --lib "$ANDROID_JAR"

echo "[3/6] aapt2 link (manifest + res -> base.apk)"
$AAPT2 compile --dir res -o $OUT/res.zip
$AAPT2 link -o $OUT/base.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R $OUT/res.zip \
    --auto-add-overlay

echo "[4/6] add dex + META-INF/xposed into apk"
cp $OUT/base.apk $OUT/unsigned.apk
( cd $OUT/dex && zip -q ../unsigned.apk classes.dex )
mkdir -p $OUT/xstage/META-INF/xposed
cp xposed/java_init.list $OUT/xstage/META-INF/xposed/java_init.list
cp xposed/module.prop    $OUT/xstage/META-INF/xposed/module.prop
cp xposed/scope.list     $OUT/xstage/META-INF/xposed/scope.list
( cd $OUT/xstage && zip -q -r ../unsigned.apk META-INF )

echo "[5/6] zipalign"
$ZIPALIGN -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "[6/6] sign"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi
$APKSIGNER sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out ds-probe.apk $OUT/aligned.apk

echo "DONE -> $(pwd)/ds-probe.apk"

# Make a best-effort shared-storage copy for direct installation on a device.
for PUB in /storage/emulated/0 /sdcard; do
  if [ -d "$PUB" ] && cp -f ds-probe.apk "$PUB/ds-probe.apk" 2>/dev/null; then
    echo "COPIED -> $PUB/ds-probe.apk"
    break
  fi
done
