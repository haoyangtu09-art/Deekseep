# Build Variants

Deekseep 1.7.2 publishes three channel-labelled APKs. The two mainland builds
compile the same canonical feature core and differ only in the Xposed entry
interface and packaging. The Google Play build is compiled from the separate
`google-play` branch because its R8 symbol map differs.

## Selection Guide

| Source | 1.7.2 release asset | DeepSeek target | Framework target |
|---|---|---|---|
| `main:module/` | `deekseep-stable-api102-v1.7.2.apk` | Mainland 2.2.2 (`233`) | Current LSPosed with libxposed API 102 |
| `main:module-legacy/` | `deekseep-stable-legacy-v1.7.2.apk` | Mainland 2.2.2 (`233`) | Traditional Xposed API 82+, compatible FPA/older LSPosed |
| `google-play:module/` | `deekseep-google-play-2.2.2-v1.7.2.apk` | Google Play 2.2.2 (`236`) | Current LSPosed with libxposed API 102 |

Use the API 102 build on a current LSPosed installation. Use the legacy build
only when the framework cannot load modern libxposed metadata.

## 1.7.2 Feature Parity

The two mainland APKs are built from `module/src/com/dsmod/probe`. The Google
Play branch carries the same maintained features with its own mapped host
symbols. All three include:

- settings entry, prompt injection, response preservation and diagnostics;
- account import/export with strict server validation;
- refreshed cross-account chat editor, search, export, statistics and backup;
- local conversation/image persistence, native navigation and deletion;
- regional native-login restoration controls;
- OpenAI Chat/Responses and Anthropic Messages local gateway;
- Codex and Claude Code tool-result loops and conversation isolation;
- the gated **Experimental Features** page, five-second first-entry disclosure
  and separate experimental help.

Features in the Experimental Features page remain off by default and are not a
stability guarantee. See [Experimental Features](EXPERIMENTAL_FEATURES.md).

## Test Editions Are Discontinued

Starting with 1.7.1, the former `module-inject/` and
`module-inject-legacy/` test editions are discontinued and receive no GitHub
Release APKs. Their direct Compose settings injection and host long-press menu
experiments were too dependent on obfuscated UI internals to maintain as public
parallel products. The separate `module-mtest/` load probe is also excluded
from the 1.7.1 release.

The historical source directories remain for archaeology and comparison, but
they are not built by `scripts/build-all.sh`, not covered by the 1.7.1 release
tests, and must not be presented as supported downloads. Experimental end-user
features that remain maintained now live behind the dedicated page in the two
stable APKs.

## Interface Packaging

The modern APK:

- extends `io.github.libxposed.api.XposedModule`;
- receives packages through `onPackageLoaded`;
- declares its entry in `META-INF/xposed/`;
- compiles against libxposed API 102 without packaging the API classes.

The legacy APK:

- implements `IXposedHookLoadPackage` through a generated compatibility entry;
- receives packages through `handleLoadPackage`;
- declares `assets/xposed_init` and traditional manifest metadata;
- compiles the canonical stable core through the traditional Xposed callback
  adapter under `module-legacy/compat`;
- does not package the compile-only `de.robv.android.xposed` stubs.

## Signature and Switching Rules

Both stable APKs use `com.dsmod.probe`, but the local modern and legacy build
scripts use different development keys. Android can reject an in-place switch
because the signatures differ. Disable and uninstall the current module APK
before installing the other interface; this does not uninstall DeepSeek.

Enable only one Deekseep implementation for `com.deepseek.chat`. Duplicate
hooks can rewrite the same request or database row twice and are unsupported.
Release keys are local and excluded from Git, so a build made on another
machine may likewise require uninstalling the previous APK.
