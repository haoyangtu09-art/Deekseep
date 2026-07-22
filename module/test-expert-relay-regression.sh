#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

OUT="build/expert-relay-test"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

cmp src/com/dsmod/relay/ExpertRelayGate.java \
    ../module-inject-legacy/src/com/dsmod/relay/ExpertRelayGate.java

javac -source 8 -target 8 -d "$OUT/classes" \
    src/com/dsmod/relay/ExpertRelayGate.java \
    tests/com/dsmod/relay/ExpertRelayGateRegressionTest.java

java -cp "$OUT/classes" com.dsmod.relay.ExpertRelayGateRegressionTest
