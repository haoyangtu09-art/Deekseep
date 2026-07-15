#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

source ../scripts/android-tools.sh
OUT="build/thinking-test"
JSON_JAR="$OUT/lib/json-20240303.jar"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/lib"

curl -fsSL \
    https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar \
    -o "$JSON_JAR"

javac -source 8 -target 8 -cp "$JSON_JAR:$ANDROID_JAR:build/classes" \
    -d "$OUT/classes" tests/com/dsmod/probe/ChatEditorThinkingRegressionTest.java

java -cp "$JSON_JAR:$ANDROID_JAR:build/classes:$OUT/classes" \
    com.dsmod.probe.ChatEditorThinkingRegressionTest
