# Deekseep 1.7.2 — Google Play 2.2.2

This build is the Google Play port of Deekseep for the exact DeepSeek
Android **2.2.2 (`versionCode 236`)** build. It uses the modern libxposed API
102 interface and is not interchangeable with the mainland `versionCode 233`
packages.

## Download

- `deekseep-google-play-2.2.2-v1.7.2.apk`
- `deekseep-google-play-2.2.2-v1.7.2.apk.sha256`

## Google Play adaptation

- Restored the native Deekseep settings entry for the Play Store symbol map.
- Restored expert-mode selection, image picker/upload and vision relay.
- Restored prompt injection, chat tools, multi-select, response preservation,
  account/login hooks, and the local OpenAI/Anthropic API on the Play build.
- Kept compatibility exact-build scoped; later DeepSeek updates may require a
  new mapping.

## New user features

- Added multi-account management with saved account slots, explicit add,
  switch and remove actions, selectable JSON import/export, and validation
  before candidate credentials are stored.
- Added automatic Chinese/English selection from DeepSeek's language, an
  explicit language picker, and complete English module/help text. Other host
  languages fall back to English.
- Added a dedicated **Experimental Features** page. Expert image relay and the
  local API now use a first-entry disclosure with a five-second confirmation,
  an explicit exit action, and separate Help & Issues content.
- Discontinued the former test editions; this release contains the maintained
  module interface only.

## Module and API

- Simplified the standalone module page to show activation, installed DeepSeek
  version, module version, and module build time. The SELinux row was removed.
- Improved OpenAI Chat/Responses JSON and SSE behavior, Anthropic Messages
  streaming, Codex and Claude Code tool loops, session isolation, heartbeats,
  and incremental delivery through the native DeepSeek transport.
- The format selector is now a single Format/current-value row with a popup.
- Sensitive Gateway Keys are omitted from diagnostics and runtime status JSON.

Back up important conversations before installing. Use this build only with
Google Play DeepSeek 2.2.2 (`versionCode 236`) and only on accounts and devices
you control. Read the repository disclaimer and Experimental Features notice
before enabling those options.
