# Google Play port status

The `google-play` branch is an experimental, build-specific port for Google
Play DeepSeek Android. It is separate from the maintained mainland-China
release and must not be assumed compatible with a later Play Store update.

## Exact target

- Package: `com.deepseek.chat`
- Version name: `2.2.2`
- Version code: `236`
- Minimum SDK: `32`
- Source APKS SHA-256:
  `f9a3f7c313c39a4e825f996b2bd562a408f318c5d7a8e480cf2e6883e44399b3`
- Current module test build: `1.7.1-gplay.5` (`versionCode 35`)

No DeepSeek APK, account material, device log, or decompiled source is included
in this repository.

## Restored and verified

| Area | `1.7.1-gplay.5` evidence |
|---|---|
| Settings and Experimental Features | Native entry loads; first-entry disclaimer and nested pages render. |
| Expert mode | Expert selection is accepted by the native composer and the expert request path completes. |
| Image upload and expert relay | Native picker, upload, temporary vision session, streamed description extraction, expert prompt rewrite, and local image-metadata restoration were exercised on-device. |
| Response overwrite protection | Play mappings for clear-response, content-filter patch, status write, final merge, final apply, online history, and cold local history are installed. A real clear-response event was blocked; cold-history behavior is covered by regression tests. |
| Local API | `/v1/models`, OpenAI Chat, Responses JSON/SSE, and Anthropic Messages returned HTTP 200 through the real DeepSeek transport. Responses emitted the complete created/in-progress/item/text/done/completed lifecycle. |
| Codex compatibility | The installed Codex CLI completed a disposable custom `apply_patch` tool loop with an isolated `CODEX_HOME`; the current Codex login was not read or changed. |
| Protocol security | Gateway keys are no longer written to diagnostic logs or runtime status JSON. The explicit connection file remains a credential and must be protected. |

The API acceptance test temporarily enabled the listener and battery exemption,
then restored the original disabled state, removed the generated test key, and
removed the temporary exemption.

## Important symbol changes

Google Play 236 uses a different R8 map even where class shapes are identical.
Several successful class loads in the first probe were unrelated classes, which
is why visible controls could appear while their behavior remained inert.

| Role | Mainland 2.2.2 (`233`) | Google Play 2.2.2 (`236`) |
|---|---|---|
| Settings content / navigation / route | `u25.i` / `rm5` / `vc7` | `ph6.d` / `eo5` / `og7` |
| Chat completion request / transport | `ew0` / `s92.b` | `tx0` / `kb2.d` |
| Flow / collector / continuation | `b41` / `q03` / `uz1` | `q51` / `q23` / `j12` |
| Coroutine suspended / failure | `w02` / `fx6` | `l22` / `m07` |
| Stream event / event payload / error | `xs0` / `lv7` / `ws0` | `mu0` / `iz7` / `lu0` |
| Expert config / file feature / upload gate | `sf5` / `gf5` / `y91` | `eh5` / `sg5` / `mb1` |
| Session / message / online history | `tp` / `uo` / `pw0` | `vp` / `xo` / `ey0` |
| Clear response / response model / patch decoder | `kb7` / `mv` / `mv.i` | `df7` / `ov` / `ov.k` |

The coroutine-suspended mapping is particularly important: using the mainland
sentinel made the API collector declare an asynchronous native Flow complete
before its first event and return `502 empty_completion` even though DeepSeek
was still generating.

## Remaining caveats

- This is not a general “international version” compatibility layer. Only the
  exact target above was inspected.
- Server-side availability, rate limits, account policy, and content the server
  never sends cannot be changed by client hooks.
- Account import, chat editing, multi-select, prompt injection, and login hooks
  have mapped code paths and regression coverage, but they have not all received
  the same end-to-end UI pass as expert upload and the local API.
- Back up important chats before enabling experimental database or account
  features, and do not test with an irreplaceable account or device state.
