#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

source ../scripts/android-tools.sh

OUT=build
rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex

echo "[0/6] generate BuildInfo.java (xposed api version + build date)"
API_VER=$(grep -oE 'xposedminversion" android:value="[0-9]+' AndroidManifest.xml | grep -oE '[0-9]+$')
cat > src/com/dsmod/inject/BuildInfo.java <<EOF
package com.dsmod.inject;
public final class BuildInfo {
    public static final String API_VERSION = "${API_VER:-82} (legacy)";
    public static final String BUILD_DATE = "$(date '+%Y-%m-%d %H:%M')";
    private BuildInfo() {}
}
EOF

echo "[1/6] javac (module + de.robv legacy stubs, compileOnly)"
find src -name "*.java" > $OUT/sources.txt
if ! javac -source 8 -target 8 -cp "$ANDROID_JAR" -d $OUT/classes @$OUT/sources.txt 2> $OUT/javac.err; then
  cat $OUT/javac.err
  exit 1
fi
grep -v "warning:" $OUT/javac.err || true

echo "[2/6] d8 (only module classes -> dex; de.robv provided by framework)"
# Package only module classes; the framework provides Xposed at runtime.
MODCLASSES=$(find $OUT/classes/com/dsmod -name "*.class")
$D8 --min-api 24 --output $OUT/dex $MODCLASSES --lib "$ANDROID_JAR"

echo "[3/6] aapt2 link (manifest + res -> base.apk)"
$AAPT2 compile --dir res -o $OUT/res.zip
$AAPT2 link -o $OUT/base.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R $OUT/res.zip \
    --auto-add-overlay

echo "[4/6] add dex + assets/xposed_init into apk"
cp $OUT/base.apk $OUT/unsigned.apk
( cd $OUT/dex && zip -q ../unsigned.apk classes.dex )
mkdir -p $OUT/xstage/assets
cp assets/xposed_init $OUT/xstage/assets/xposed_init
( cd $OUT/xstage && zip -q -r ../unsigned.apk assets )

echo "[5/6] zipalign"
$ZIPALIGN -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "[6/6] sign (new inject-legacy keystore, distinct from modern inject build)"
if [ ! -f injectlegacy.keystore ]; then
  keytool -genkeypair -keystore injectlegacy.keystore -storepass deekseepx -keypass deekseepx \
    -alias deekseepxlegacy -dname "CN=DeekseepX Legacy,O=Deekseep,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi
$APKSIGNER sign --ks injectlegacy.keystore --ks-pass pass:deekseepx --key-pass pass:deekseepx \
    --out ds-inject-legacy.apk $OUT/aligned.apk

echo "DONE -> $(pwd)/ds-inject-legacy.apk"

# Make a best-effort shared-storage copy for direct installation on a device.
for PUB in /storage/emulated/0 /sdcard; do
  if [ -d "$PUB" ] && cp -f ds-inject-legacy.apk "$PUB/ds-inject-legacy.apk" 2>/dev/null; then
    echo "COPIED -> $PUB/ds-inject-legacy.apk"
    break
  fi
done
