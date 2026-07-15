# Deekseep

[![Build all variants](https://github.com/haoyangtu09-art/Deekseep/actions/workflows/build.yml/badge.svg)](https://github.com/haoyangtu09-art/Deekseep/actions/workflows/build.yml)
[![libxposed API 102](https://img.shields.io/badge/libxposed-API%20102-2f6feb)](https://github.com/libxposed/api)
[![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3ddc84)](https://developer.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Deekseep is an unofficial Xposed module toolkit for the DeepSeek Android client.
It provides a stable native settings entry, prompt injection, response-preservation
hooks, local conversation tools, an advanced chat editor, database backup, and
experimental expert-mode image relay. The repository contains modern libxposed
API 102 builds, traditional Xposed compatibility builds, experimental builds,
and a minimal API 102 load probe.

> [!CAUTION]
> **UNOFFICIAL SOFTWARE - READ BEFORE INSTALLING**
>
> This project is not affiliated with, endorsed by, or supported by DeepSeek,
> High-Flyer, LSPosed, or the Xposed project. It modifies a third-party app at
> runtime and directly accesses local chat databases. It may stop working after
> an app update, cause data loss, conflict with service terms, trigger account
> restrictions, or weaken safeguards expected by the original client. Back up
> your data, use only on accounts and devices you control, and accept all risk.
> See the full [Disclaimer](DISCLAIMER.md).

## Downloads

The recommended build for a current LSPosed installation is
**Deekseep Stable API 102**.

| Release asset | Channel | Xposed interface | Package | Intended use |
|---|---|---|---|---|
| [deekseep-stable-api102-v1.7.apk](https://github.com/haoyangtu09-art/Deekseep/releases/latest/download/deekseep-stable-api102-v1.7.apk) | Stable | libxposed API 102 | `com.dsmod.probe` | Current LSPosed; broadest maintained stable feature set |
| [deekseep-test-api102-v1.7.apk](https://github.com/haoyangtu09-art/Deekseep/releases/latest/download/deekseep-test-api102-v1.7.apk) | Test | libxposed API 102 | `com.dsmod.inject` | Compose and long-press message-injection experiments |
| [deekseep-stable-legacy-v1.7.apk](https://github.com/haoyangtu09-art/Deekseep/releases/latest/download/deekseep-stable-legacy-v1.7.apk) | Stable compatibility | Traditional Xposed API 82+ | `com.dsmod.probe` | FPA and older LSPosed environments |
| [deekseep-test-legacy-v1.7.apk](https://github.com/haoyangtu09-art/Deekseep/releases/latest/download/deekseep-test-legacy-v1.7.apk) | Test compatibility | Traditional Xposed API 82+ | `com.dsmod.inject` | FPA/older LSPosed plus experimental hooks and vision relay |
| [deekseep-api102-load-probe-v0.1.apk](https://github.com/haoyangtu09-art/Deekseep/releases/latest/download/deekseep-api102-load-probe-v0.1.apk) | Diagnostic | libxposed API 102 | `com.dsmod.mtest` | Confirms whether the framework loads a modern module |

Modern and legacy builds in the same channel use the same Android package ID but
different signing keys. They cannot be installed over one another. Uninstall the
old interface variant before switching. Stable and test channels have different
package IDs, but enabling both against DeepSeek at the same time can install
conflicting hooks and is not supported.

Checksums are published with every release in `SHA256SUMS.txt`.

The current files are the **v1.7 r2 maintenance builds**. All four complete
variants now share reasoning creation, custom reasoning duration, reasoning-aware
search, and native conversation navigation. They replace the earlier v1.7 APKs
on the same release page; no separate release is required.

## Main Features

- Native Deekseep entry attached to the DeepSeek settings screen.
- System-prompt import and per-request injection with a private-file fallback.
- Preservation of already-streamed answers when a later client update attempts
  to replace them with a `CONTENT_FILTER` template.
- Local chat editor for titles, user messages, assistant responses, and reasoning
  fragments, including creation of a missing reasoning chain and a custom
  `elapsed_secs` duration.
- Automatic repair for malformed reasoning fragments in every complete build.
- Search across user input, model output, and deep-reasoning text. A result opens
  the matching conversation in DeepSeek's native chat screen.
- Cross-account Markdown export, statistics, manual backup, and rotating
  automatic database backup where provided by the selected variant.
- Optional sidebar multi-select and batch deletion.
- Expert feature-flag experiments and image-to-text relay through the vision
  model, including multi-image parallel processing and local image metadata
  preservation.
- Opt-in protocol diagnostics and module activation diagnostics.
- First-run in-app risk disclosure.

Feature availability differs by build. See the complete
[variant and feature matrix](docs/VARIANTS.md) before choosing an experimental
or legacy package.

## Compatibility

- Module minimum Android version: Android 7.0 / API 24.
- Modern interface: libxposed API 102, metadata under `META-INF/xposed/`.
- Legacy interface: traditional Xposed API 82+, entry under `assets/xposed_init`.
- Target application package: `com.deepseek.chat`.
- The current modern symbol map and chat-fragment fix were checked against
  DeepSeek Android 2.2.2 (`versionCode 233`).

Most hooks target R8-obfuscated classes. A DeepSeek update can rename those
classes without notice. Treat version compatibility as build-specific, not as a
permanent guarantee.

## Quick Installation

1. Back up the DeepSeek chat database.
2. Download exactly one release variant from the table above.
3. Install it and enable it in the matching Xposed framework.
4. Select both `com.deepseek.chat` and the module package in scope.
5. Force-stop and restart DeepSeek.
6. Accept the first-run risk disclosure.
7. Open DeepSeek Settings and select the injected **Deekseep** entry.

FPA packaging, signature switching, activation checks, and recovery steps are
covered in [Installation](docs/INSTALLATION.md).

## Repository Layout

| Path | Purpose |
|---|---|
| `module/` | Stable modern build using libxposed API 102 |
| `module-inject/` | Experimental modern API 102 build |
| `module-legacy/` | Stable traditional Xposed compatibility build |
| `module-inject-legacy/` | Experimental traditional Xposed compatibility build |
| `module-mtest/` | Minimal modern API 102 load probe |
| `scripts/` | Portable SDK discovery and all-variant build orchestration |
| `docs/` | Installation, architecture, feature, compatibility, and repair notes |
| `.github/workflows/` | Public CI build for all five variants |

DeepSeek APK files, decompiled application output, device logs, chat databases,
local signing keys, and other user data are deliberately excluded from this
repository.

## Build

Requirements: JDK 17 or newer, Android SDK Platform 35, Android Build Tools,
`zip`, and `curl`.

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
bash scripts/build-all.sh
```

The command builds all five variants, runs the reasoning ID/content/duration
regression test, and writes renamed release files plus checksums to `dist/`. The scripts
also support Termux/ARM and discover its native `aapt2`, `zipalign`, and
`apksigner` tools automatically.

See [Building](docs/BUILDING.md) for individual commands, signing behavior, CI,
and compile-only Xposed API boundaries.

## Documentation

- [1.7 r2 implementation and release record](docs/RELEASE_1.7_R2_PROGRESS.md)
- [Feature reference](docs/FEATURES.md)
- [Variant matrix](docs/VARIANTS.md)
- [Installation](docs/INSTALLATION.md)
- [Building from source](docs/BUILDING.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Expert image relay](docs/EXPERT_IMAGE_RELAY.md)
- [Chat-editor reasoning repair](docs/CHAT_EDITOR_THINKING_FIX.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Privacy and Safety

Deekseep runs inside the DeepSeek process. Depending on enabled features, it can
read or update local chat databases, imported prompt files, uploaded-image
metadata, and response events. Server-response logging is off by default because
those logs can contain complete prompts and model output. Do not publish logs or
database backups without reviewing and redacting them.

The response-preservation option only prevents a client-side replacement of text
that has already reached the device. It does not provide access to content the
server never returned. Expert-mode behavior remains dependent on server support.

## License

Project-owned source and documentation are released under the [MIT License](LICENSE).
Third-party names and trademarks remain the property of their respective owners.
The license does not grant rights to redistribute the proprietary DeepSeek APK
or its decompiled source.
