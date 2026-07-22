# Deekseep 1.7.1

1.7.1 unifies the maintained feature set across the current libxposed API 102
package and the traditional-Xposed compatibility package.

## Downloads

- `deekseep-stable-api102-v1.7.1.apk` — recommended for current LSPosed
- `deekseep-stable-legacy-v1.7.1.apk` — traditional Xposed API 82+ compatibility
- `SHA256SUMS.txt`

The two APKs share the package ID `com.dsmod.probe` and may use different
signing keys. Disable and uninstall one before switching interfaces. Enable
only one Deekseep implementation for DeepSeek.

## Test Editions Discontinued

The former API 102 and traditional-Xposed test editions are discontinued.
1.7.1 does not publish test or load-probe APKs. Their fragile Compose and host
long-press injection experiments are no longer supported release products.
Maintained high-risk functions are consolidated in the stable builds under the
new **Experimental Features** section.

## Experimental Features

- Added a dedicated page for expert unlock/image relay and the local API.
- First entry displays account-ban, instability and data-loss warnings.
- **Exit** leaves without acceptance; **Confirm** unlocks only after five
  seconds and is required only for the current disclosure version.
- Added a separate experimental Help & Issues page.
- Replaced two protocol buttons with one **Format / current format** row and an
  OpenAI/Anthropic selection popup.

Do not use these functions with an important account or on a device containing
the only copy of important chats or workspace files. Back up first and retain
Agent sandbox/approval controls.

## Local API and Agent Compatibility

- OpenAI model listing, Chat Completions and Responses in JSON or SSE, including
  structured output, function/custom/shell/apply_patch/namespace tools,
  `previous_response_id` and tool-result continuation.
- Current Codex compatibility metadata and a `gpt-5.4` capability alias while
  the native request continues to use the configured DeepSeek model.
- Anthropic Messages and count_tokens with full thinking/text/tool block
  lifecycle, Claude Code tool loops and quiet-period activity updates.
- `/clear` and `/new` isolation by client session plus first-user-turn
  fingerprint, preventing a cleared transcript from reusing the old hidden
  native branch even when a wrapper retains a stale UUID.
- Model-facing workspace rules prefer real Write/Edit/NotebookEdit/apply_patch
  tool calls over printing code when the user asks to create or change files.
- Queueing, proof-of-work and native startup complete before the Anthropic
  thinking lifecycle is emitted to the client.
- One fair account-wide generation permit, bounded deadlines, adaptive 429
  cooldown, invalid-session recovery, repeated-side-effect protection and an
  opt-in foreground keeper.

## Accounts, Login and Data Tools

- Fixed account import validation failures: requests now use DeepSeek's actual
  bearer authorization, device/timezone headers, app User-Agent shape, bounded
  response parsing, paced batches and one bounded 429 retry. Credentials are
  written only after full business-layer success and account-ID matching.
- Restored optional native Google login on mainland login lists and native
  WeChat/SMS entries on overseas lists without replacing the host auth flows.
- Refreshed cross-account chat editing, search, native navigation, Markdown
  export, statistics and database backup.
- Added durable local conversations and uploaded-image mirrors, cold-history
  recovery, response preservation, native delete dispatch and stale-snapshot
  protections.
- Preserved reasoning fragment IDs/content and editable `elapsed_secs`, with
  automatic idempotent repair of malformed older rows.

## Packaging

- Both stable APKs are version 1.7.1 and compile the same canonical feature
  core.
- Modern packaging uses `META-INF/xposed`; legacy packaging uses
  `assets/xposed_init` and a traditional hook adapter. The adapter composes
  repeated canonical interceptors into one low-priority callback so it does not
  suppress ordinary before/after hooks from other modules.
- CI and `scripts/build-all.sh` now produce exactly the two stable APKs and
  checksums, and reject retired test/probe artifacts.

Read the [Disclaimer](https://github.com/haoyangtu09-art/Deekseep/blob/main/DISCLAIMER.md),
[Installation guide](https://github.com/haoyangtu09-art/Deekseep/blob/main/docs/INSTALLATION.md),
and [Experimental Features guide](https://github.com/haoyangtu09-art/Deekseep/blob/main/docs/EXPERIMENTAL_FEATURES.md)
before installing.
