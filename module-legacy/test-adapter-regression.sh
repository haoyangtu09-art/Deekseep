#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

OUT=build/adapter-test
rm -rf "$OUT"
mkdir -p "$OUT"

javac -source 8 -target 8 -d "$OUT" \
    src/de/robv/android/xposed/XC_MethodHook.java \
    src/de/robv/android/xposed/XposedBridge.java \
    compat/com/dsmod/probe/LegacyXposedModule.java \
    tests/com/dsmod/probe/LegacyXposedModuleRegressionTest.java

java -cp "$OUT" com.dsmod.probe.LegacyXposedModuleRegressionTest
