# Deekseep

[![Build stable releases](https://github.com/haoyangtu09-art/Deekseep/actions/workflows/build.yml/badge.svg)](https://github.com/haoyangtu09-art/Deekseep/actions/workflows/build.yml)
[![libxposed API 102](https://img.shields.io/badge/libxposed-API%20102-2f6feb)](https://github.com/libxposed/api)
[![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3ddc84)](https://developer.android.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> [!WARNING]
> **Google Play 实验分支 / Experimental Google Play branch**
>
> 此 `google-play` 分支只针对 **Google Play DeepSeek 2.2.2
> (`versionCode 236`)**。当前实机测试包为 **`1.7.2`**，已恢复专家模式、
> 图片上传/视觉中继、响应覆盖保护，以及 OpenAI/Anthropic 本地 API。它仍是按精确
> APK 混淆表适配的实验版本；使用前请备份聊天和账号数据。
>
> This branch targets only **Google Play DeepSeek 2.2.2 (`versionCode 236`)**.
> Device build **`1.7.2`** restores expert mode, image upload/vision
> relay, response-preservation guards, and the OpenAI/Anthropic local API. It is
> still an exact-APK experimental port; back up chat and account data first.

Deekseep is an unofficial Xposed module toolkit for the DeepSeek Android client.
It provides a stable native settings entry, prompt injection, response-preservation
hooks, local conversation tools, an advanced chat editor, database backup, and
experimental expert-mode image relay. The port also provides an authenticated
local/LAN OpenAI/Anthropic gateway for local SDKs, Codex, and Claude Code. This
branch currently produces only the modern libxposed API 102 Google Play build;
the traditional-Xposed directories remain mainland reference code.

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

## Google Play build

The current branch build is **`1.7.2`**, using the modern libxposed API 102
interface under [`module/`](module/). Download the explicitly labelled Google
Play asset from the unified `v1.7.2` release; its mainland assets use a separate
symbol map and are not interchangeable with this build.

The Google Play port retains the gated **Experimental Features** page, its
five-second first-entry disclosure, and separate help. Modern and legacy APKs
share one package ID but may use different signing keys, so Android may require
uninstalling the old interface variant before switching.

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
- A manually drawn local API control page,
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

- Experimental target: Google Play DeepSeek Android 2.2.2 (`versionCode 236`)
  with the inspected APKS fingerprint documented in
  [Google Play port status](docs/GOOGLE_PLAY.md).
- Device-verified in `1.7.2`: native settings entry, expert selection,
  image picker/upload/vision relay, response-preservation hook chain, OpenAI
  Chat/Responses (including SSE), Anthropic Messages, and an isolated Codex
  Responses tool loop.
- Other mapped features retain regression coverage but have not all received
  the same exhaustive UI acceptance pass; treat the branch as experimental.
- For the maintained mainland-China build, use the `main` branch and stable
  release instead.
- Module minimum Android version: Android 7.0 / API 24.
- Modern interface: libxposed API 102, metadata under `META-INF/xposed/`.
- Legacy interface: traditional Xposed API 82+, entry under `assets/xposed_init`.
- Target application package: `com.deepseek.chat`.
- Do not reuse this symbol map for another Google Play version or for the
  mainland-China `versionCode 233` APK.

Most hooks target R8-obfuscated classes. A DeepSeek update can rename those
classes without notice. Treat version compatibility as build-specific, not as a
permanent guarantee.

## Quick Installation

1. Confirm that DeepSeek is the Google Play 2.2.2 build (`versionCode 236`).
2. Back up the DeepSeek chat database.
3. Build `module/` from this branch, or download the explicitly labelled Google
   Play 2.2.2 asset from `v1.7.2`. Do not use a mainland APK.
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
| `module/` | Google Play port using modern libxposed API 102 |
| `module-legacy/` | Mainland traditional-Xposed reference; not ported to Google Play 236 |
| `module-inject/`, `module-inject-legacy/` | Discontinued historical mainland test-edition source; not released |
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
cd module
bash test-expert-relay-regression.sh
bash test-thinking-regression.sh
bash build.sh
```

The build writes `module/ds-probe.apk`. Do not run the repository-wide stable
release build expecting a Google Play legacy APK; only `module/` carries the
Play 236 symbol map. The scripts support Termux/ARM and discover its native
`aapt2`, `zipalign`, and `apksigner` tools automatically.

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
database backups without reviewing and redacting them. The private connection
file and its optional shared-storage compatibility copy contain the complete
Gateway Key and must be treated as credentials; diagnostic logs and runtime
status JSON deliberately record only whether a key is configured.

The response-preservation option only prevents a client-side replacement of text
that has already reached the device. It does not provide access to content the
server never returned. Expert-mode behavior remains dependent on server support.

## License

Project-owned source and documentation are released under the [MIT License](LICENSE).
Third-party names and trademarks remain the property of their respective owners.
The license does not grant rights to redistribute the proprietary DeepSeek APK
or its decompiled source.
