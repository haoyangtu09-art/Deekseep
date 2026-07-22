# Building from Source

The repository uses small shell-based Android builds instead of Gradle. Each
variant compiles Java, converts project classes with D8, links resources with
AAPT2, packages the appropriate Xposed metadata, aligns the APK, and signs it
with a local development key.

## Requirements

- Git
- Bash
- JDK 17 or newer
- Android SDK Platform 35
- Android Build Tools containing `aapt2`, `d8`, `zipalign`, and `apksigner`
- `zip`
- `curl` for the JSON regression test

Set either `ANDROID_SDK_ROOT` or `ANDROID_HOME`:

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
```

`scripts/android-tools.sh` selects Android Platform 35 when available, otherwise
the newest installed platform. It searches Termux's `$PREFIX/bin` first, then
the current `PATH`, Android command-line tools, and Android Build Tools.

## Clone and Build the Stable Release

```bash
git clone https://github.com/haoyangtu09-art/Deekseep.git
cd Deekseep
bash scripts/build-all.sh
```

The final `dist/` directory contains only the supported 1.7.1 assets:

```text
deekseep-stable-api102-v1.7.1.apk
deekseep-stable-legacy-v1.7.1.apk
SHA256SUMS.txt
```

The release build runs the stable protocol, account, editor and expert-relay
regressions, verifies both manifests are version 1.7.1, checks the two Xposed
metadata layouts, and refuses test/probe APKs in `dist/`.

The old test and load-probe projects are intentionally not release targets.
See [Build Variants](VARIANTS.md).

## Build One Variant

```bash
(cd module && bash build.sh)
(cd module-legacy && bash build.sh)
```

The unrenamed outputs remain in their project directories:

| Project | Output |
|---|---|
| `module/` | `ds-probe.apk` |
| `module-legacy/` | `ds-probe-legacy.apk` |

## Termux

The scripts were originally developed on Termux/ARM. Android SDK desktop tools
are commonly x86 binaries and cannot run natively there, so tool discovery
prefers Termux-native `aapt2`, `zipalign`, and `apksigner` under `$PREFIX/bin`.
D8 and `android.jar` are loaded from the configured Android SDK.

Run the same root command:

```bash
cd ~/deepseek
bash scripts/build-all.sh
```

When shared storage is available, individual build scripts make a best-effort
copy of their APK to `/storage/emulated/0/`. A failed optional copy does not fail
the build.

## Modern API Dependency

The modern project includes `libs/api.jar`, extracted from the official
libxposed API 102 AAR. It is a compile-only dependency:

- `javac` uses it to resolve `io.github.libxposed.api` symbols;
- D8 receives only classes under `com/dsmod`;
- the final APK must not package libxposed API classes;
- the framework supplies those classes at runtime.

## Shared Stable Core and Legacy Adapter

`module/src/com/dsmod/probe` is the canonical 1.7.1 feature core. The legacy
build generates its entry from the same `Main.java` and compiles the same
feature classes through `module-legacy/compat/LegacyXposedModule.java`. This
prevents the traditional package from drifting several releases behind the
modern package.

The legacy project includes small declarations under
`module-legacy/src/de/robv/android/xposed/`. They expose only compile-time
signatures. D8 packages only `com/dsmod`, so these stubs do not shadow framework
classes at runtime. `module-legacy/src/com/dsmod/probe` is retained historical
source and is deliberately excluded by the build script.

## Generated Files and Signing

Every build generates `BuildInfo.java` with the API version and build time.
Build directories, BuildInfo files, APKs, signature sidecars, and keystores are
ignored by Git.

If no local keystore exists, a build script creates a development key. These
keys are not release-grade identity keys. CI creates fresh temporary keys, and
APK signatures can therefore differ between machines. Android may require an
uninstall before installing a build signed elsewhere.

## Regression Tests

The stable build includes JVM regressions for chat editing, account credentials,
regional login policy, expert relay, response preservation, native-session
refresh/delete behavior, and the local API protocol/tool bridge:

```bash
cd module
bash build.sh
bash test-thinking-regression.sh
bash test-expert-relay-regression.sh
(cd ../module-legacy && bash test-adapter-regression.sh)
```

`test-thinking-regression.sh` runs the Java regression classes, including
`OpenAiToolBridgeRegressionTest` and
`LocalApiGatewayProtocolRegressionTest`. The latter checks Chat and Responses
tool objects, SSE frames, namespace calls, and `previous_response_id` tool-result
continuation with a fake native backend. Run `build.sh` first because the test
classpath includes the freshly compiled production classes.

The legacy adapter regression verifies that several canonical interceptors on
the same reflected member register one traditional callback, preserve replaced
arguments through the complete `chain.proceed()` order, and use the lowest
callback priority so other modules' before/after hooks are not suppressed.

It verifies that adding reasoning:

- creates a numeric, unique fragment ID;
- places the reasoning fragment before the response;
- keeps the response ID and content unchanged;
- repairs an old reasoning fragment without an ID;
- is idempotent on the second repair pass.

The relay test verifies that an explicit first-turn expert request and a
later-turn request with a send-point-captured expert model both enter the relay,
while missing or explicit non-expert model contexts remain untouched. It also
checks that the modern and legacy relay gate implementations are identical.

## Package Verification

```bash
apksigner verify --verbose module/ds-probe.apk
unzip -l module/ds-probe.apk | grep 'META-INF/xposed'
unzip -l module-legacy/ds-probe-legacy.apk | grep 'assets/xposed_init'
sha256sum dist/*.apk
```

A modern APK must contain `META-INF/xposed` and a legacy APK must contain
`assets/xposed_init`. Neither should contain compiled API stub classes.

## Continuous Integration

`.github/workflows/build.yml` installs Android Platform and Build Tools 35,
builds and tests the two stable interfaces, and uploads the exact 1.7.1 `dist/`
contents as a workflow artifact on pushes, pull requests, and manual dispatches.
Test editions are not built or uploaded.
