#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"

rm -rf "$DIST"
mkdir -p "$DIST"

for variant in module module-inject module-legacy module-inject-legacy module-mtest; do
    echo
    echo "=== Building $variant ==="
    (cd "$ROOT/$variant" && bash build.sh)
done

echo
echo "=== Running chat-editor regression test ==="
(cd "$ROOT/module" && bash test-thinking-regression.sh)

cp "$ROOT/module/ds-probe.apk" \
    "$DIST/deekseep-stable-api102-v1.7.apk"
cp "$ROOT/module-inject/ds-inject.apk" \
    "$DIST/deekseep-test-api102-v1.7.apk"
cp "$ROOT/module-legacy/ds-probe-legacy.apk" \
    "$DIST/deekseep-stable-legacy-v1.7.apk"
cp "$ROOT/module-inject-legacy/ds-inject-legacy.apk" \
    "$DIST/deekseep-test-legacy-v1.7.apk"
cp "$ROOT/module-mtest/ds-mtest.apk" \
    "$DIST/deekseep-api102-load-probe-v0.1.apk"

(cd "$DIST" && sha256sum *.apk > SHA256SUMS.txt)
echo
echo "All release files are in $DIST"
