# Architecture

## Process Model

Deekseep is an Android application and an Xposed module. The important code runs
inside the `com.deepseek.chat` process after the selected framework loads the
module. The launcher activity exists primarily for activation status, version
information, and storage-permission checks.

The target and module package are both listed in scope:

- target scope installs request, response, navigation, editor, and database
  hooks;
- module scope allows the launcher to confirm activation;
- legacy environments can use an exported status provider for a process
  handshake when module-process injection is unavailable.

## Entry Interfaces

Modern projects use:

```text
XposedModule
  -> onPackageLoaded(PackageLoadedParam)
  -> META-INF/xposed/java_init.list
```

Legacy projects use:

```text
IXposedHookLoadPackage
  -> handleLoadPackage(LoadPackageParam)
  -> assets/xposed_init
```

The feature code uses reflection because the target app is obfuscated and does
not expose a supported plugin API.

## Startup Sequence

The stable API 102 project performs the following high-level work:

1. installs activity lifecycle and file-picker result hooks;
2. records the current host activity and displays the first-run disclosure;
3. hooks completion request construction for optional prompt injection;
4. repairs malformed local reasoning fragments before the host loads a
   conversation;
5. starts noncritical historical prompt-prefix cleanup in the background;
6. installs response replacement, message merge, and status hooks;
7. installs expert feature, transport, proof-of-work, and image-history hooks;
8. tracks settings navigation and optional sidebar selection;
9. injects the native Deekseep entry only on the settings route.

Failures are generally caught and logged so an unavailable optional hook does
not prevent the original host method from running.

## User Interface

The stable path uses Android Views in a full-screen dialog layered over the host
activity. It does not replace the host Compose tree. This provides a predictable
surface for:

- prompt selection and toggles;
- diagnostics;
- expert controls;
- chat editing;
- data export, search, statistics, and backup;
- help and risk information.

Test builds also include direct Compose and host long-press menu experiments.

## Request Path

Prompt injection modifies the outgoing request's prompt field immediately before
the host sends it. The imported text is wrapped in a system marker, while the
user's visible input remains unchanged.

Expert image relay wraps the cold completion flow instead of blocking the UI
thread. Network work occurs when the host collects the wrapped flow. The wrapper
creates separate vision requests, rewrites the expert request only after
successful descriptions, and forwards the real expert flow to the original
collector.

## Response Path

Response preservation uses several defensive hook layers:

- raw streaming event observation for diagnostics;
- patch application filtering for a matching content-filter replacement;
- status and reconstructed-message observation;
- final merge protection that restores the original object when a later
  template object attempts to replace it.

Normal append events must continue through the original path. Blocking them
would remove legitimate model output.

## Local Conversation Storage

DeepSeek stores per-account databases under its private database directory.
Each database has a session list and per-session message tables. Message rows
include a JSON fragment array with types such as:

- `REQUEST`
- `THINK`
- `RESPONSE`
- `TEMPLATE_RESPONSE`
- `FILE`

The editor uses structured `org.json` transforms and parameterized values for
content. Dynamic table names originate from discovered session IDs and are
quoted. The expert image history path reuses the host serializer when object
fidelity is required.

## Storage Boundaries

The module stores configuration markers and prompt copies in DeepSeek's private
files directory because the hook already runs inside that process. Shared
storage is used only for optional diagnostics, exported Markdown, user-visible
backups, and convenient APK copies.

Sensitive local artifacts are excluded from the public repository:

- target APKs and decompiled target output;
- databases and write-ahead logs;
- runtime diagnostics;
- imported prompts;
- local signing keys.

## Compatibility Boundary

Most reflected symbols are R8-obfuscated. Compatibility depends on:

- target class and method names;
- constructor and field layouts;
- serialized fragment schemas;
- server event and patch formats;
- proof-of-work and session APIs;
- Xposed framework behavior.

All hooks should fail open: when a contract cannot be confirmed, the host
operation proceeds unchanged. Version updates require symbol remapping and
device validation.

## Source Ownership Boundary

This repository publishes project-owned module source, tests, scripts, and
documentation. It does not publish the proprietary target APK or generated
decompiled target source. Technical notes describe only the minimum contracts
needed to understand the module.
