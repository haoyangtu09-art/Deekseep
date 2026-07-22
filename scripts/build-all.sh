#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
source "$ROOT/scripts/android-tools.sh"

rm -rf "$DIST"
mkdir -p "$DIST"

for variant in module module-legacy; do
    echo
    echo "=== Building $variant ==="
    (cd "$ROOT/$variant" && bash build.sh)
done

echo
echo "=== Running chat-editor regression test ==="
(cd "$ROOT/module" && bash test-thinking-regression.sh)

echo
echo "=== Running expert relay multi-turn regression test ==="
(cd "$ROOT/module" && bash test-expert-relay-regression.sh)

echo
echo "=== Running traditional-Xposed adapter regression test ==="
(cd "$ROOT/module-legacy" && bash test-adapter-regression.sh)

echo
echo "=== Verifying stable-core parity and APK signatures ==="
for class_name in AccountManager DeekseepUi LocalApiGateway OpenAiToolBridge ResponsePreserver; do
    test -f "$ROOT/module/build/classes/com/dsmod/probe/${class_name}.class"
    test -f "$ROOT/module-legacy/build/classes/com/dsmod/probe/${class_name}.class"
done
if grep -q '^import io\.github\.libxposed' \
        "$ROOT/module-legacy/build/generated-src/com/dsmod/probe/Main.java"; then
    echo "Generated legacy entry still imports modern libxposed APIs" >&2
    exit 1
fi
"$APKSIGNER" verify "$ROOT/module/ds-probe.apk"
"$APKSIGNER" verify "$ROOT/module-legacy/ds-probe-legacy.apk"

cp "$ROOT/module/ds-probe.apk" \
    "$DIST/deekseep-stable-api102-v1.7.2.apk"
cp "$ROOT/module-legacy/ds-probe-legacy.apk" \
    "$DIST/deekseep-stable-legacy-v1.7.2.apk"

if find "$DIST" -maxdepth 1 -type f \( -name '*test*' -o -name '*probe*' \) | grep -q .; then
    echo "Refusing to publish retired test/diagnostic APKs in the 1.7.2 release" >&2
    exit 1
fi

for manifest in "$ROOT/module/AndroidManifest.xml" "$ROOT/module-legacy/AndroidManifest.xml"; do
    if ! grep -q 'android:versionName="1.7.2"' "$manifest"; then
        echo "Release manifest is not version 1.7.2: $manifest" >&2
        exit 1
    fi
done

unzip -l "$DIST/deekseep-stable-api102-v1.7.2.apk" | grep -q 'META-INF/xposed/java_init.list'
unzip -l "$DIST/deekseep-stable-legacy-v1.7.2.apk" | grep -q 'assets/xposed_init'

(cd "$DIST" && sha256sum *.apk > SHA256SUMS.txt)
echo
echo "The two stable 1.7.2 release files are in $DIST"
