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

## Clone and Build Everything

```bash
git clone https://github.com/haoyangtu09-art/Deekseep.git
cd Deekseep
bash scripts/build-all.sh
```

The final `dist/` directory contains:

```text
deekseep-stable-api102-v1.7.apk
deekseep-test-api102-v1.7.apk
deekseep-stable-legacy-v1.7.apk
deekseep-test-legacy-v1.7.apk
deekseep-api102-load-probe-v0.1.apk
SHA256SUMS.txt
```

The all-variant build also runs the stable chat-editor regression test.

## Build One Variant

```bash
(cd module && bash build.sh)
(cd module-inject && bash build.sh)
(cd module-legacy && bash build.sh)
(cd module-inject-legacy && bash build.sh)
(cd module-mtest && bash build.sh)
```

The unrenamed outputs remain in their project directories:

| Project | Output |
|---|---|
| `module/` | `ds-probe.apk` |
| `module-inject/` | `ds-inject.apk` |
| `module-legacy/` | `ds-probe-legacy.apk` |
| `module-inject-legacy/` | `ds-inject-legacy.apk` |
| `module-mtest/` | `ds-mtest.apk` |

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

The modern projects include `libs/api.jar`, extracted from the official
libxposed API 102 AAR. It is a compile-only dependency:

- `javac` uses it to resolve `io.github.libxposed.api` symbols;
- D8 receives only classes under `com/dsmod`;
- the final APK must not package libxposed API classes;
- the framework supplies those classes at runtime.

The three tracked copies are identical so each project can be built
independently.

## Legacy Compile Stubs

Legacy projects include small source declarations under
`src/de/robv/android/xposed/`. They expose only the signatures required by this
project. D8 packages only `com/dsmod`, so the stubs do not shadow framework
classes at runtime.

## Generated Files and Signing

Every build generates `BuildInfo.java` with the API version and build time.
Build directories, BuildInfo files, APKs, signature sidecars, and keystores are
ignored by Git.

If no local keystore exists, a build script creates a development key. These
keys are not release-grade identity keys. CI creates fresh temporary keys, and
APK signatures can therefore differ between machines. Android may require an
uninstall before installing a build signed elsewhere.

## Regression Test

The v1.7 reasoning fix has a JVM regression test:

```bash
cd module
bash build.sh
bash test-thinking-regression.sh
```

It verifies that adding reasoning:

- creates a numeric, unique fragment ID;
- places the reasoning fragment before the response;
- keeps the response ID and content unchanged;
- repairs an old reasoning fragment without an ID;
- is idempotent on the second repair pass.

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
builds all variants, runs the regression test, and uploads `dist/` as a workflow
artifact on pushes, pull requests, and manual dispatches.
