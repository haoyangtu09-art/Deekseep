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
deekseep-stable-api102-v1.7.1.apk
```

For FPA or an older framework that cannot load modern modules, install:

```text
deekseep-stable-legacy-v1.7.1.apk
```

The former modern and legacy test editions are discontinued in 1.7.1 and are
not release downloads. Maintained high-risk functions now live behind the
dedicated **Experimental Features** page in both stable builds.

See [Build Variants](VARIANTS.md) for the full comparison.

## Install on Current LSPosed

1. Download the stable API 102 asset and verify its SHA-256 value against
   `SHA256SUMS.txt`.
2. Install the APK.
3. Enable Deekseep in LSPosed.
4. Select `com.deepseek.chat` in the module scope. Do not select the module app
   itself for activation detection; modern libxposed does not self-hook module
   applications.
5. Force-stop DeepSeek.
6. Start DeepSeek and accept the first-run disclosure.
7. Open DeepSeek Settings. The Deekseep entry should appear on the settings
   screen.

The launcher reports **Enabled** when the official Xposed service connects and
**Active** after the DeepSeek target process sends its UID-validated heartbeat.

## Install with FPA or Traditional Xposed

1. Download the matching legacy asset.
2. Follow the injector's normal patch/install process for a traditional Xposed
   module.
3. Scope the module to DeepSeek. A legacy injector may expose its own activation
   convention, but the stable modern self-scope rule does not apply to it.
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

## First Safe Configuration

1. Leave response and protocol diagnostics disabled.
2. Use **Back up chat database now** before opening the editor.
3. Import a small test prompt.
4. Enable prompt injection and test it in a disposable conversation.
5. Enable response preservation or one experimental feature at a time so a
   failure can be attributed to one feature.
6. Leave the DeepSeek Local API disabled unless a trusted local client needs it.
   When enabled, copy the Key only from its control page and treat connection
   files and API logs as credentials. Its foreground keeper intentionally increases
   background battery use. See [DeepSeek Local API](LOCAL_DEEPSEEK_API.md).

## Upgrading from 1.7 or an Older Reasoning Writer

Both 1.7.1 stable builds scan local assistant rows for a `THINK` fragment
without a numeric `id` and repair it idempotently.

1. Install the 1.7.1 stable build matching your framework.
2. Confirm it is the only enabled Deekseep hook.
3. Force-stop and restart DeepSeek.
4. Open the affected conversation.

The migration preserves the original response and gives the malformed reasoning
fragment a unique ID. A diagnostic line reports
`repairMalformedThinkFragments fixed=N`. A later launch should report zero.

The 1.7.1 APKs have higher Android version codes for their stable interface
tracks. Switching between modern and traditional interfaces still requires an
uninstall when those builds use different keys.

## Rollback

If DeepSeek crashes or behaves incorrectly:

1. Disable Deekseep in the injection framework.
2. Force-stop and restart DeepSeek.
3. Restore a database backup if local editing or deletion changed data.
4. Report the exact DeepSeek version, module variant, framework version, and
   redacted error lines.

Disabling or uninstalling the module does not automatically undo database edits
already written by the conversation editor.
