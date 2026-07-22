#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

source ../scripts/android-tools.sh
OUT="build/thinking-test"
JSON_JAR="$OUT/lib/json-20240303.jar"
DEPS_DIR="build/test-deps"
JSON_CACHE="$DEPS_DIR/json-20240303.jar"
JSON_SHA256="3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/lib" "$DEPS_DIR"

if [[ ! -f "$JSON_CACHE" ]] || ! printf '%s  %s\n' "$JSON_SHA256" "$JSON_CACHE" \
        | sha256sum -c - >/dev/null 2>&1; then
    tmp="$JSON_CACHE.tmp"
    rm -f "$tmp"
    downloaded=false
    for base in \
        https://repo.maven.apache.org/maven2 \
        https://repo1.maven.org/maven2 \
        https://maven.aliyun.com/repository/public; do
        if curl -fsSL --connect-timeout 15 --max-time 60 \
                "$base/org/json/json/20240303/json-20240303.jar" -o "$tmp" \
                && printf '%s  %s\n' "$JSON_SHA256" "$tmp" \
                    | sha256sum -c - >/dev/null 2>&1; then
            mv "$tmp" "$JSON_CACHE"
            downloaded=true
            break
        fi
        rm -f "$tmp"
    done
    if [[ "$downloaded" != true ]]; then
        echo "Could not download a verified org.json test dependency" >&2
        exit 1
    fi
fi
cp "$JSON_CACHE" "$JSON_JAR"

javac -source 8 -target 8 -cp "$JSON_JAR:$ANDROID_JAR:libs/api.jar:build/classes" \
    -d "$OUT/classes" \
    tests/com/dsmod/probe/ChatEditorThinkingRegressionTest.java \
    tests/com/dsmod/probe/ChatEditorHistoryImageRegressionTest.java \
    tests/com/dsmod/probe/HistoryBridgeRegressionTest.java \
    tests/com/dsmod/probe/NativeSessionDeleteRegressionTest.java \
    tests/com/dsmod/probe/NativeSessionRefreshRegressionTest.java \
    tests/com/dsmod/probe/ResponsePreserverRegressionTest.java \
    tests/com/dsmod/probe/AccountCredentialCodecRegressionTest.java \
    tests/com/dsmod/probe/AccountServerValidationRegressionTest.java \
    tests/com/dsmod/probe/GoogleLoginUnlockRegressionTest.java \
    tests/com/dsmod/probe/OpenAiToolBridgeRegressionTest.java \
    tests/com/dsmod/probe/LocalApiGatewayProtocolRegressionTest.java \
    tests/com/dsmod/probe/LocalApiCoroutineCancellationRegressionTest.java \
    tests/com/dsmod/probe/NativeApiPatchDecoderRegressionTest.java \
    src/com/dsmod/probe/LocalApiGateway.java \
    src/com/dsmod/probe/OpenAiToolBridge.java \
    src/com/dsmod/probe/OmniRouteToolBridge.java \
    src/com/dsmod/probe/NativeApiPatchDecoder.java \
    src/com/dsmod/probe/AccountCredentialCodec.java \
    src/com/dsmod/probe/AccountManager.java \
    src/com/dsmod/probe/GoogleLoginUnlock.java \
    src/com/dsmod/probe/ResponsePreserver.java \
    tests/tp.java \
    tests/h61.java \
    tests/sl8.java \
    tests/kv.java \
    tests/hv.java \
    tests/x94.java \
    tests/n02.java \
    tests/p64.java \
    tests/c74.java

# Main is large and already compiled by build.sh. Recompile it only when this regression targets
# reflected private helpers added after the last APK build.
javac -source 8 -target 8 -cp "$JSON_JAR:$ANDROID_JAR:libs/api.jar:$OUT/classes:build/classes" \
    -d "$OUT/classes" src/com/dsmod/probe/Main.java

# The output directory comes first because LocalApiGateway, OpenAiToolBridge, and
# ResponsePreserver are compiled from current source as part of this regression run;
# build/classes may still contain the previous APK build.
TEST_CP="$JSON_JAR:$ANDROID_JAR:libs/api.jar:$OUT/classes:build/classes"

java -cp "$TEST_CP" \
    com.dsmod.probe.ChatEditorThinkingRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.ChatEditorHistoryImageRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.HistoryBridgeRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.NativeSessionDeleteRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.NativeSessionRefreshRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.ResponsePreserverRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.AccountCredentialCodecRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.AccountServerValidationRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.GoogleLoginUnlockRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.OpenAiToolBridgeRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.LocalApiGatewayProtocolRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.LocalApiCoroutineCancellationRegressionTest

java -cp "$TEST_CP" \
    com.dsmod.probe.NativeApiPatchDecoderRegressionTest
