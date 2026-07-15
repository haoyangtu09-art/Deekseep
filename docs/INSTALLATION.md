# Installation

## Prerequisites

- Android 7.0 or newer.
- The official DeepSeek Android client installed as `com.deepseek.chat`.
- One supported injection environment:
  - a current LSPosed build with modern libxposed API support; or
  - FPA / an older traditional Xposed-compatible environment.
- A current backup of important conversations.

This repository does not distribute the DeepSeek APK, patched target APKs, or
framework installers.

## Choose One APK

For current LSPosed, install:

```text
deekseep-stable-api102-v1.7.apk
```

For FPA or an older framework that cannot load modern modules, install:

```text
deekseep-stable-legacy-v1.7.apk
```

Use a test build only when you explicitly need Compose injection, the host
long-press edit experiment, or the FPA/legacy expert relay track. The FPA test
APK is `deekseep-test-legacy-v1.7.apk` on the same v1.7 release page. Use the
load probe only to diagnose modern API loading.

See [Build Variants](VARIANTS.md) for the full comparison.

## Install on Current LSPosed

1. Download the stable API 102 asset and verify its SHA-256 value against
   `SHA256SUMS.txt`.
2. Install the APK.
3. Enable Deekseep in LSPosed.
4. Select `com.deepseek.chat` and `com.dsmod.probe` in the module scope.
5. Force-stop DeepSeek.
6. Start DeepSeek and accept the first-run disclosure.
7. Open DeepSeek Settings. The Deekseep entry should appear on the settings
   screen.

The module's own launcher should report activation after the framework has
injected the matching package process.

## Install with FPA or Traditional Xposed

1. Download the matching legacy asset.
2. Follow the injector's normal patch/install process for a traditional Xposed
   module.
3. Scope the module to DeepSeek and, where supported, to the module package.
4. Restart the target process.
5. Open the module launcher once so its activation handshake can complete.
6. Open DeepSeek Settings and verify the Deekseep entry.

FPA behavior varies by version. The traditional APK includes manifest Xposed
metadata and `assets/xposed_init`; the modern APK does not.

## Switching Modern and Legacy Interfaces

The modern and legacy stable APKs both use `com.dsmod.probe` but are signed by
different development keys. Android will report an incompatible package or
signature if one is installed over the other.

1. Disable the old module in the framework.
2. Uninstall the old module APK. This does not uninstall DeepSeek.
3. Install the new interface variant.
4. Enable only the new module and reselect scope.
5. Force-stop and restart DeepSeek.

The same rule applies to the two `com.dsmod.inject` test builds.

## First Safe Configuration

1. Leave response and protocol diagnostics disabled.
2. Use **Back up chat database now** before opening the editor.
3. Import a small test prompt.
4. Enable prompt injection and test it in a disposable conversation.
5. Enable response preservation, expert mode, or relay features separately so a
   failure can be attributed to one feature.

## Upgrading from the Broken Reasoning Writer

Version 1.7 r2 does this in all four complete variants: stable/test API 102 and
stable/FPA-test traditional Xposed. Each scans local assistant rows for a
`THINK` fragment without a numeric `id`.

1. Install the v1.7 r2 build matching your framework.
2. Confirm it is the only enabled Deekseep hook.
3. Force-stop and restart DeepSeek.
4. Open the affected conversation.

The migration preserves the original response and gives the malformed reasoning
fragment a unique ID. A diagnostic line reports
`repairMalformedThinkFragments fixed=N`. A later launch should report zero.

The r2 APKs have higher Android version codes than the first v1.7 files, so an
in-channel update with the same signing key can install over them. Switching
between modern and traditional interfaces still requires uninstalling because
those tracks use different keys.

## Rollback

If DeepSeek crashes or behaves incorrectly:

1. Disable Deekseep in the injection framework.
2. Force-stop and restart DeepSeek.
3. Restore a database backup if local editing or deletion changed data.
4. Report the exact DeepSeek version, module variant, framework version, and
   redacted error lines.

Disabling or uninstalling the module does not automatically undo database edits
already written by the conversation editor.
