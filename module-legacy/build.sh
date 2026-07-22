#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

source ../scripts/android-tools.sh

OUT=build
rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex $OUT/generated-src/com/dsmod/probe

echo "[0/7] generate legacy adapter sources from the stable canonical core"
API_VER=$(grep -oE 'xposedminversion" android:value="[0-9]+' AndroidManifest.xml | grep -oE '[0-9]+$')
MODULE_VER=$(grep -oE 'android:versionName="[^"]+"' AndroidManifest.xml | head -n1 | cut -d'"' -f2)
cat > $OUT/generated-src/com/dsmod/probe/BuildInfo.java <<EOF
package com.dsmod.probe;
public final class BuildInfo {
    public static final String API_VERSION = "${API_VER:-82} (legacy)";
    public static final String MODULE_VERSION = "${MODULE_VER:-unknown}";
    public static final String BUILD_DATE = "$(date '+%Y-%m-%d %H:%M')";
    private BuildInfo() {}
}
EOF

sed \
  -e 's#import io.github.libxposed.api.XposedModule;#import de.robv.android.xposed.IXposedHookLoadPackage;#' \
  -e 's#import io.github.libxposed.api.XposedInterface.Chain;#import com.dsmod.probe.LegacyXposedModule.Chain;#' \
  -e 's#import io.github.libxposed.api.XposedInterface.Hooker;#import com.dsmod.probe.LegacyXposedModule.Hooker;#' \
  -e 's#import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;#import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;#' \
  -e 's#public class Main extends XposedModule#public class Main extends LegacyXposedModule implements IXposedHookLoadPackage#' \
  -e 's#public void onPackageLoaded(PackageLoadedParam param)#public void handleLoadPackage(LoadPackageParam param)#' \
  -e 's#param.getDefaultClassLoader()#param.classLoader#g' \
  -e 's#param.getPackageName()#param.packageName#g' \
  -e 's#module loaded (modern)#module loaded (legacy)#g' \
  ../module/src/com/dsmod/probe/Main.java \
  > $OUT/generated-src/com/dsmod/probe/Main.java

echo "[1/7] collect canonical stable sources plus traditional Xposed adapter"
find ../module/src/com/dsmod/probe -maxdepth 1 -name "*.java" \
  ! -name Main.java ! -name BuildInfo.java > $OUT/sources.txt
find ../module/src/com/dsmod/relay -name "*.java" >> $OUT/sources.txt
find compat -name "*.java" >> $OUT/sources.txt
find src/de -name "*.java" >> $OUT/sources.txt
find $OUT/generated-src -name "*.java" >> $OUT/sources.txt

echo "[2/7] javac (canonical stable core + traditional Xposed adapter)"
if ! javac -source 8 -target 8 -cp "$ANDROID_JAR" -d $OUT/classes @$OUT/sources.txt 2> $OUT/javac.err; then
  cat $OUT/javac.err
  exit 1
fi
grep -v "warning:" $OUT/javac.err || true

echo "[3/7] d8 (only module classes -> dex; de.robv provided by framework)"
# Package only module classes; the framework provides Xposed at runtime.
MODCLASSES=$(find $OUT/classes/com/dsmod -name "*.class")
$D8 --min-api 24 --output $OUT/dex $MODCLASSES --lib "$ANDROID_JAR"

echo "[4/7] aapt2 link (manifest + res -> base.apk)"
$AAPT2 compile --dir res -o $OUT/res.zip
$AAPT2 link -o $OUT/base.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R $OUT/res.zip \
    --auto-add-overlay

echo "[5/7] add dex + assets/xposed_init into apk"
cp $OUT/base.apk $OUT/unsigned.apk
( cd $OUT/dex && zip -q ../unsigned.apk classes.dex )
mkdir -p $OUT/xstage/assets
cp assets/xposed_init $OUT/xstage/assets/xposed_init
( cd $OUT/xstage && zip -q -r ../unsigned.apk assets )

echo "[6/7] zipalign"
$ZIPALIGN -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

echo "[7/7] sign (legacy keystore, distinct from modern builds)"
if [ ! -f legacy.keystore ]; then
  keytool -genkeypair -keystore legacy.keystore -storepass deekseep -keypass deekseep \
    -alias deekseeplegacy -dname "CN=Deekseep Legacy,O=Deekseep,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi
$APKSIGNER sign --ks legacy.keystore --ks-pass pass:deekseep --key-pass pass:deekseep \
    --out ds-probe-legacy.apk $OUT/aligned.apk

echo "DONE -> $(pwd)/ds-probe-legacy.apk"

# Make a best-effort shared-storage copy for direct installation on a device.
for PUB in /storage/emulated/0 /sdcard; do
  if [ -d "$PUB" ] && cp -f ds-probe-legacy.apk "$PUB/ds-probe-legacy.apk" 2>/dev/null; then
    echo "COPIED -> $PUB/ds-probe-legacy.apk"
    break
  fi
done
