# Deekseep

[![Build stable releases](https://github.com/haoyangtu09-art/Deekseep/actions/workflows/build.yml/badge.svg)](https://github.com/haoyangtu09-art/Deekseep/actions/workflows/build.yml)
[![libxposed API 102](https://img.shields.io/badge/libxposed-API%20102-2f6feb)](https://github.com/libxposed/api)
[![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3ddc84)](https://developer.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> [!IMPORTANT]
> **请选择匹配的渠道构建 / Choose the matching channel build**
>
> 国内版与 Google Play 版使用不同的混淆符号映射，安装包不能混用。
> `main` 稳定构建匹配国内 DeepSeek 2.2.2 (`versionCode 233`)；
> `google-play` 分支和对应的预发布构建匹配 Google Play DeepSeek 2.2.2
> (`versionCode 236`)。
>
> Mainland and Google Play packages use different obfuscation maps and are not
> interchangeable. The `main` stable build targets DeepSeek 2.2.2 code 233;
> the `google-play` branch and its prerelease target Play build code 236.

Deekseep is an unofficial Xposed module toolkit for the DeepSeek Android client.
It provides a stable native settings entry, prompt injection, response-preservation
hooks, local conversation tools, an advanced chat editor, database backup, and
experimental expert-mode image relay. Both stable 1.7.1 builds also provide a
authenticated local/LAN OpenAI/Anthropic gateway for local SDKs, Codex, and Claude Code.
The release contains a modern libxposed API 102 build and a traditional Xposed
compatibility build compiled from the same feature core.

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
| [deekseep-stable-api102-v1.7.1.apk](https://github.com/haoyangtu09-art/Deekseep/releases/latest/download/deekseep-stable-api102-v1.7.1.apk) | Stable | libxposed API 102 | `com.dsmod.probe` | Current LSPosed; recommended |
| [deekseep-stable-legacy-v1.7.1.apk](https://github.com/haoyangtu09-art/Deekseep/releases/latest/download/deekseep-stable-legacy-v1.7.1.apk) | Stable compatibility | Traditional Xposed API 82+ | `com.dsmod.probe` | Compatible FPA and older LSPosed environments |
| [deekseep-google-play-2.2.2-v1.7.2.apk](https://github.com/haoyangtu09-art/Deekseep/releases/download/v1.7.2-google-play/deekseep-google-play-2.2.2-v1.7.2.apk) | Google Play prerelease | libxposed API 102 | `com.dsmod.probe` | Google Play 2.2.2 (`versionCode 236`) only |

Modern and legacy builds use the same Android package ID but
different signing keys. They cannot be installed over one another. Uninstall the
old interface variant before switching.

Checksums are published with every release in `SHA256SUMS.txt`.

The mainland stable release is **1.7.1**; the exact-build Google Play port is
published separately as **1.7.2**. The former test editions are discontinued
and no longer published. Maintained high-risk options are collected under a
gated **Experimental Features** page with a five-second first-entry disclosure
and separate help.

## Main Features

- Native Deekseep entry attached to the DeepSeek settings screen.
- System-prompt import and per-request injection with a private-file fallback.
- Preservation of already-streamed answers when a later client update attempts
  to replace them with a `CONTENT_FILTER` template, including subsequent cold
  starts and server-history refreshes once the replacement event was observed.
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
- Opt-in restoration of DeepSeek's native Google Credential
  Manager login item on the mainland login page, without removing domestic
  phone or WeChat methods.
- A separate switch that restores the native WeChat and SMS phone
  entries together on overseas login pages without changing the Google switch.
- Modern activation reporting through the official Xposed service bridge plus
  a UID-validated heartbeat from the DeepSeek target process.
- A manually drawn local API control page in both stable builds,
  with unrestricted-background preflight, OpenAI/Anthropic selection, custom keys,
  Chat/Responses/Messages SSE, deep-thinking parameters, Codex/Claude Code Agent tool
  loops, HTTP heartbeat and adaptive rate-limit recovery, a module foreground keeper
  for Cached Apps Freezer, one fair account-wide native generation lane with Agent
  priority and client-session isolation, and live request diagnostics.
- Opt-in protocol diagnostics and module activation diagnostics.
- First-run in-app risk disclosure.

See the [stable interface guide](docs/VARIANTS.md) and dedicated
[Experimental Features](docs/EXPERIMENTAL_FEATURES.md) safety notes.

## Compatibility

- Mainland stable target: DeepSeek Android 2.2.2 (`versionCode 233`) on `main`.
- Google Play target: DeepSeek Android 2.2.2 (`versionCode 236`) on the
  `google-play` branch and `v1.7.2-google-play` prerelease.
- Module minimum Android version: Android 7.0 / API 24.
- Modern interface: libxposed API 102, metadata under `META-INF/xposed/`.
- Legacy interface: traditional Xposed API 82+, entry under `assets/xposed_init`.
- Target application package: `com.deepseek.chat`.
- The current modern symbol map and chat-fragment fix were checked against
  the mainland-China DeepSeek Android 2.2.2 build (`versionCode 233`).

Most hooks target R8-obfuscated classes. A DeepSeek update can rename those
classes without notice. Treat version compatibility as build-specific, not as a
permanent guarantee.

## Quick Installation

1. Check the installed DeepSeek `versionCode` and select the matching mainland
   or Google Play build from the table above.
2. Back up the DeepSeek chat database.
3. Download exactly one release variant from the table above.
4. Install it and enable it in the matching Xposed framework.
5. Select `com.deepseek.chat` in scope. Modern libxposed does not require or
   support self-hooking the module application.
6. Force-stop and restart DeepSeek.
7. Accept the first-run risk disclosure.
8. Open DeepSeek Settings and select the injected **Deekseep** entry.

FPA packaging, signature switching, activation checks, and recovery steps are
covered in [Installation](docs/INSTALLATION.md).

## Repository Layout

| Path | Purpose |
|---|---|
| `module/` | Stable modern build using libxposed API 102 |
| `module-legacy/` | Stable traditional Xposed adapter/build using the canonical stable core |
| `module-inject/`, `module-inject-legacy/` | Discontinued historical test-edition source; not released |
| `module-mtest/` | Historical diagnostic source; not part of the 1.7.1 release |
| `scripts/` | Portable SDK discovery and stable-release build orchestration |
| `docs/` | Installation, architecture, feature, compatibility, and repair notes |
| `.github/workflows/` | Public CI for both stable interfaces |

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

The command builds both stable interfaces, runs the protocol/account/editor/relay
regressions, and writes the two release files plus checksums to `dist/`. The scripts
also support Termux/ARM and discover its native `aapt2`, `zipalign`, and
`apksigner` tools automatically.

See [Building](docs/BUILDING.md) for individual commands, signing behavior, CI,
and compile-only Xposed API boundaries.

## Documentation

- [Feature reference](docs/FEATURES.md)
- [Experimental Features](docs/EXPERIMENTAL_FEATURES.md)
- [Variant matrix](docs/VARIANTS.md)
- [Installation](docs/INSTALLATION.md)
- [Building from source](docs/BUILDING.md)
- [Architecture](docs/ARCHITECTURE.md)
- [1.7.1 implementation and porting guide (中文)](docs/V1_7_1_PORTING_GUIDE.md)
- [DeepSeek Local API, Codex and Claude Code configuration (中文)](docs/LOCAL_DEEPSEEK_API.md)
- [Local DeepSeek API implementation status and roadmap (中文)](docs/LOCAL_DEEPSEEK_API_GATEWAY_PLAN.md)
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
metadata, response events, and—when explicitly enabled—local API requests and
private connection diagnostics. Server-response logging is off by default because
those logs can contain complete prompts and model output. Do not publish logs or
database backups without reviewing and redacting them. Local API connection and
status files contain the complete Gateway Key and must be treated as credentials.

The response-preservation option only prevents a client-side replacement of text
that has already reached the device. It does not provide access to content the
server never returned. Expert-mode behavior remains dependent on server support.

## License

Project-owned source and documentation are released under the [MIT License](LICENSE).
Third-party names and trademarks remain the property of their respective owners.
The license does not grant rights to redistribute the proprietary DeepSeek APK
or its decompiled source.
