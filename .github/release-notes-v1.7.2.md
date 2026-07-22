# Deekseep 1.7.2

Version 1.7.2 publishes the maintained mainland and Google Play builds together
in one release. Select the APK by the installed DeepSeek channel and Xposed
interface; the packages share an Android package ID but use channel-specific
obfuscation maps and must not be mixed.

## Downloads

- `deekseep-stable-api102-v1.7.2.apk` — mainland DeepSeek 2.2.2
  (`versionCode 233`), recommended for current LSPosed
- `deekseep-stable-legacy-v1.7.2.apk` — mainland DeepSeek 2.2.2
  (`versionCode 233`), traditional Xposed API 82+/compatible FPA
- `deekseep-google-play-2.2.2-v1.7.2.apk` — Google Play DeepSeek 2.2.2
  (`versionCode 236`), modern libxposed API 102
- `SHA256SUMS.txt`

## Language support

- Added complete Chinese and English interfaces across the injected settings,
  dialogs, Help & Issues, Experimental Features, account management, chat
  editor/search, local API controls, warnings, and runtime status messages.
- Automatic mode follows DeepSeek's own language on each process start. Chinese
  selects Chinese; English and every other detected language select English.
- Added a dedicated language row for manually selecting Chinese or English.
- The standalone module launcher follows Android's system language.

## Module launcher

- Simplified the launcher to a compact activation and version status page.
- Shows the installed DeepSeek version, module version, and build time.
- Removed the SELinux status row and the associated system-command lookup.

## Features and compatibility

- Included the Google Play 2.2.2 adaptation in the same release. The Play build
  restores its mapped settings entry, expert image flow, prompt/chat tools,
  response preservation, account/login hooks, and local API paths.
- Improved OpenAI Chat/Responses and Anthropic Messages streaming, incremental
  activity, client-session isolation, tool-result continuation, and Codex and
  Claude Code compatibility through DeepSeek's native generation transport.
- Expert image relay and the local API remain under the dedicated Experimental
  Features page with a five-second first-entry disclosure and separate help.
- Former test editions remain discontinued and are not included in this release.

Back up important chats before installation. Use only the APK matching the
installed DeepSeek `versionCode`, and enable only one Deekseep interface at a
time. Read the repository disclaimer and Experimental Features notice before
enabling high-risk options.
