# Changelog

All notable public releases are documented here.

## 1.7.1 - 2026-07-22

### Release model

- Unified the stable modern and traditional Xposed packages at version 1.7.1.
  The legacy APK now compiles the canonical stable feature core through a
  traditional-Xposed compatibility adapter, including the local OpenAI/
  Anthropic gateway and current account/editor/login fixes.
- Discontinued the API 102 and traditional-Xposed test editions. No test or
  load-probe APK is attached to 1.7.1; only the two stable interface packages
  and their checksums are published.
- Established **Experimental Features** as a dedicated in-app and documentation
  section. Expert unlock/image relay and the local API live behind a versioned
  first-entry disclaimer, explicit exit, five-second confirmation and separate
  help page.

### Fixes and features

- Upgraded the stable API 102 development head to **1.7-r22-api102** and fixed
  the remaining account-import `code=40002` failure. The validator now follows
  DeepSeek 2.2.2's actual Ktor bearer-auth chain (`Authorization: Bearer`) rather
  than the unrelated telemetry `x-auth-token`, and carries the host device ID and
  timezone headers. A privacy-safe live check confirmed `code=0`, `biz_code=0`,
  and matching account IDs for both locally saved accounts before this change was
  applied to the importer.
- Upgraded the stable API 102 development head to **1.7-r21-api102** and fixed
  account imports that were deterministically rejected with HTTP 429. Validation
  now uses the installed DeepSeek app's real User-Agent shape, spaces batch
  requests, honors a bounded `Retry-After` retry, parses each bounded response
  exactly once, and still rejects expired tokens, mismatched account IDs, or any
  incomplete business-layer success without writing candidate credentials.
- Upgraded the stable API 102 development head to **1.7-r20-api102** and hardened
  OpenAI Responses for Codex CLI 0.144.5. The gateway now scopes native sessions
  from Codex thread/session metadata, emits `phase`, `end_turn`, and custom-tool
  status fields, preserves only public reasoning summaries, and never forwards
  encrypted compaction/reasoning transport blobs into the upstream model prompt.
  A real, isolated Codex regression now verifies custom `apply_patch`, tool-output
  continuation, and the final answer without reading or changing the user's Codex login.
- Corrected the Anthropic streaming boundary: `message_start` and the first
  thinking block are now emitted only after native Flow collection has begun,
  rather than during queueing or PoW. Added a lifecycle regression that inspects
  the wire before and after the explicit upstream-start callback.
- Forced the Termux Claude launcher onto its verified full-redraw path instead of
  inheriting a stale shell-wide incremental-TUI value, preventing cleared sessions
  from retaining old rows as a rendering artifact.
- Finished the Claude Code clear/new investigation. Those commands are local CLI
  state transitions and never reach `/v1/messages`; the gateway now combines the
  hashed Claude session UUID with a first-user-turn fingerprint so even a stale
  wrapper UUID cannot reuse the old hidden branch for a new transcript.
- Made model-facing tool instructions service-neutral and require Write/Edit/
  NotebookEdit/apply_patch tools for requested workspace changes instead of
  returning source code as a substitute. A bounded repair now converts ignored,
  code-only file answers to real tool calls while explicit snippet-only requests
  stay read-only. OpenAI compatibility now includes model retrieval plus
  Chat/Responses JSON object and JSON Schema output formats.
- Moved expert image relay and the local API into a gated Experimental Features
  subpage with a versioned first-entry disclaimer, five-second confirmation,
  explicit exit, and separate help. Replaced the two-button protocol selector
  with a Format/current-value row and a selection popup.
- Upgraded the stable API 102 development head to **1.7-r19-api102**. Every
  DeepSeek generation now passes through one fair account-wide native permit,
  with tool-bearing main/Task turns prioritized and non-tool Claude metadata
  paced before it can occupy the permit. This removes the competing native
  generations that produced `parallel_chat_limit`, frozen spinners, and
  prematurely ended Agent work.
- Anthropic SSE now emits a valid non-terminal cumulative `message_delta` usage
  update after each ten-second `ping`. Claude Code filters `ping` internally,
  so the additional activity event keeps its live spinner and long-thinking
  token counter advancing; the terminal token value is clamped to the highest
  activity value and cannot fall backwards.
- Scoped reusable native sessions to a one-way hash of Claude Code's client
  conversation metadata. `/clear` and its `/new` alias now rotate to a fresh
  DeepSeek branch, while the raw client identifier is never logged or stored.
  Stable sessions continue to reuse their branch and avoid session-creation 429s.
- Added one bounded repair when a tool-bearing turn only says what it will do,
  including the variant where that promise exists solely in private reasoning.
  `/init` remains actionable until a successful Read/Write/Edit of `CLAUDE.md`.
  A real backgrounded `/init` completed Glob, Task, Read, Bash, and Write; live
  acceptance also covered `/compact`, `/clear`, `/new`, `@` file input, input-line
  Bash mode, and fifteen Claude Code internal commands.
- Upgraded the stable API 102 gateway to **1.7-r16-api102**. Anthropic Messages now
  forwards native reasoning and text incrementally, emits the complete official
  thinking/text/tool content-block lifecycle, and streams tool arguments through
  `input_json_delta`. DeepSeek special-token, XML, narrated, embedded, and damaged-key
  tool envelopes are parsed without leaking their raw markup into Claude Code.
- The earlier r16 implementation introduced a separate serialized Agent lane and hidden
  Agent session while retaining global 2.5-second start pacing and serialized PoW. Its
  stress test proved that metadata scheduling mattered, but later device logs showed the
  server enforces an account-wide `parallel_chat_limit`; r19 supersedes the two-lane
  generation scheduler with the single fair permit documented above.
- Kept side-effecting tool calls protected against duplicate execution while allowing
  read-only Read/Glob/Grep-style verification calls to run again. A real Claude Code
  Write → Read → Edit → Read chain and a separate Bash tool-result round trip now both
  execute through the client, with no raw tool tags and exact files verified on disk.
- Renamed the stable API 102 gateway UI to
  **DeepSeek Local API**. A first-use custom preflight now requires verified
  unrestricted battery/background activity before listening. The control page
  provides a real OpenAI/Anthropic protocol selector; Anthropic mode implements
  Messages, count_tokens, standard text/thinking/tool SSE, tool_use/tool_result
  loops and Claude Code authentication. SSE modes send protocol-appropriate heartbeats,
  client disconnects are counted separately, and a no-dependency CLI Agent plus
  an isolated Claude Code launcher were added for reproducible testing.
- Fixed background SSE stalls caused by Android's Cached Apps Freezer even when
  DeepSeek was battery-unrestricted. A visible-flow trampoline now starts a
  module-private special-use foreground keeper with a partial wake lock and a
  token-gated five-second no-op heartbeat. It restores an enabled gateway after
  a recoverable host restart, auto-stops after 90 seconds without an
  acknowledgement, and does not alter the global freezer or other apps.
- Added live OpenAI Chat, OpenAI Responses, and Anthropic tool-loop acceptance
  tests, a Termux-compatible isolated Claude Code launcher, and local `claude`
  / `claude-upstream` command routing without storing the Gateway Key in shell
  configuration.
- Prevented Claude Code from executing a successful Bash command twice when the
  model regenerated it with only a different description or timeout. Completion
  signatures now key Bash by the actual command; failed results and genuinely
  different commands remain retryable. JVM and live two-turn Claude tests cover
  the behavior.
- Added a loopback-only OpenAI-compatible gateway to the stable API 102 build.
  Its manually drawn subpage controls listening, URL/Key copy, custom or random
  keys, deep-thinking parameters, and live request/queue/failure diagnostics.
  Chat Completions and Responses support non-streaming and SSE, chunked request
  bodies, structured errors, usage objects, and standard SDK clients.
- Added Codex/Agent tool loops for Chat functions and Responses function,
  custom, shell, apply_patch, namespace and tool-output items. API-native
  sessions are hidden and reused per model; invalid sessions self-heal, native
  requests are fairly serialized, and real server 429 responses use adaptive
  cooldown within one bounded request deadline. Completed call IDs, normalized
  arguments, and successful outputs are tracked so an identical model-generated
  action is repaired instead of being executed repeatedly by an Agent.
- Added a second, independent regional-login switch that restores DeepSeek's
  native WeChat and SMS/mobile-number entries together on overseas login lists.
  It preserves Google, password, registration, and existing regional entries,
  keeps native click/authentication routes, and has immutable-list, ordering,
  idempotency, and fail-open JVM coverage.
- Replaced the modern launcher's obsolete self-hook activation test with the
  official `<applicationId>.XposedService` Binder delivery endpoint plus a
  UID-validated heartbeat from the actual DeepSeek target process. The launcher
  now distinguishes framework connection from verified target injection and no
  longer falsely labels an enabled module “inactive” merely because modern
  libxposed does not hook module apps themselves.
- Added a bottom-of-page build footer showing module version, libxposed API,
  compile time, and the installed DeepSeek version. The earlier local API design
  document is now an implementation-status and hardening roadmap.
- Added an opt-in stable API 102 switch that restores DeepSeek's own Google
  login option on the mainland login page while preserving phone, one-tap, and
  WeChat methods. The injected item continues through the host Credential
  Manager and official OAuth exchange; the module neither builds a replacement
  OAuth UI nor records the Google ID token. The hook is idempotent, fail-open,
  and covered by a pure JVM list-policy regression test.
- Fixed preserved model output reverting to the server's `CONTENT_FILTER`
  template after a full DeepSeek restart. A replacement event now records the
  host's exact static message in a private, SID/message-scoped store; filtered
  `pw0` history, `gm8/fm8` repository rows, and final `tp` application restore
  that evidence-backed copy before rendering or persistence. Ordinary server
  messages are untouched, explicit conversation deletion removes the saved
  copy, and equal-content snapshots prefer a finished state over streaming.
- Fixed editor-created conversations disappearing several seconds after a cold
  start even though their SQLite rows remained intact. The delayed `ed0.h`
  server refresh now restores sidecar-owned `tp` objects into the canonical
  `ed0.e` native state list, so navigation and the active chat observe the same
  server-plus-local union while newly synchronized server sessions still enter.
- Fixed sidebar and editor batch deletion reporting requests as submitted while
  leaving conversations recoverable locally. Both surfaces now dispatch
  DeepSeek's real <code>h61(tp)</code> deletion event, idempotently remove the
  local directory row and message table, deactivate the exact sidecar, clear
  stale native/editor snapshots, and report native requests separately from
  verified local removal.
- Made blank local-conversation creation idempotent at both the UI and database
  layers. Rapid duplicate calls now reuse the same still-empty SID, while every
  committed sidecar immediately registers its SID and branch head with the
  runtime cloud-sync guards.
- Made gallery selection an immediate persistent edit. The selected FILE
  fragment, frozen cache version, timestamp, and sidecar are committed as soon
  as the picker completes; appending a later USER or ASSISTANT row also saves
  any older pending image selection in the same transaction.
- Completed the cold-start path for editor-owned conversations by repairing and
  preserving frozen message heads, hydrating the selected native session
  through DeepSeek's own WCDB loader/mapper, narrowly suppressing the local-only
  server-deleted flow, rebuilding FileProvider cache mirrors, and resolving
  Deekseep's private content URI without the host HTTPS path builder.
- Refreshed the chat editor from DeepSeek's live sidebar every time it opens.
  The editor now merges native-only conversations into the SQLite directory,
  sorts all accounts by the actual latest timestamp, and prefers a newer
  in-memory branch while the host database writer catches up. A live record
  shown over stale local rows remains read-only, preventing accidental rollback.
- Added blank local conversation creation from the editor drawer. User and AI
  rows are appended directly to the current conversation from the fixed bottom
  actions; the duplicate top add action was removed.
- Added user-message image management. Existing images can be removed or reused,
  and new images can be selected from the system photo picker. Gallery binaries
  now use a durable private master plus a FileProvider cache mirror that is
  restored on startup, fixing expired/missing long-term server credentials and
  image load failures after reopening a conversation.
- Preserved editor-created local conversations across DeepSeek cloud-directory
  sync. The host `p68`/`aw` prune excludes only sidecar-owned local IDs, the
  native `mc.f` list is merged with cached local records, and incoming server
  conversations continue to synchronize normally. Removed an interim SQLite
  trigger that could leave WCDB waiting in `sqlite3_step`.
- Fixed editor-created conversations being reported as deleted when opened.
  The `at0` server-deletion handler is bypassed only for sidecar-owned local
  IDs. Sidecar repair now runs once before WCDB starts instead of from delayed
  Android-SQLite workers, preventing database-lock stalls that made complete
  text and gallery-image conversations appear blank after a cold restart.
- Synchronized the refreshed editor, blank-conversation writer, uploaded-image
  manager, Markdown tools, search jump support, and sidebar multi-select across
  all four complete stable/test and modern/legacy builds. Added regression tests
  for freshness selection and lossless FILE-fragment transforms.
- Fixed injected system prompts appearing after a cold restart by sanitising
  online `pw0` history before DeepSeek renders or persists it. Prompt wrapping
  and legacy cleanup are now idempotent and remove stacked old prefixes. The
  one-time migration is marked complete only after every account database was
  scanned successfully, so a transient lock is retried on the next launch.
- Fixed the chat editor reporting no records when DeepSeek rendered a cloud
  history response in memory without creating its per-session SQLite table.
  The editor now selects the exact MMKV login database, reads a bounded and
  versioned native-history snapshot, automatically asks DeepSeek to load a
  selected cloud-only conversation, merges incremental responses, and safely
  materialises a complete snapshot only when the user saves an edit. Snapshot
  materialisation and all edited fields now commit in one atomic transaction.
- Fixed untouched Markdown messages being mistaken for edits and overwritten
  with their rendered plain text when the editor saved another field.
- Updated history repository and row discovery for both the DeepSeek 2.2.1
  `fm8/rl8/qs7` layout and the 2.2.2 `gm8/sl8/rs7` layout.
- Fixed expert image relay after the first message in a conversation. DeepSeek
  omits `model_type` from continuation requests, so the module now carries the
  effective session model from the image send point to the exact request in
  both the stable API 102 and test legacy relay implementations.
- Added a JVM regression test for explicit first-turn and captured later-turn
  expert model resolution.

## 1.7 - 2026-07-15

### Maintenance update r2

- Synchronized reasoning creation and malformed-fragment migration across the
  stable/test and modern/legacy complete variants.
- Added editable reasoning duration backed by the host's numeric
  `elapsed_secs` field.
- Expanded global search to index user input, model output, and deep-reasoning
  content.
- Changed search-result navigation to call DeepSeek's captured native session
  controller instead of opening the Deekseep editor.
- Added global search to the stable traditional-Xposed build.
- Increased Android version codes for in-channel upgrades while retaining the
  existing v1.7 release asset names.

### Stable API 102

- Added an advanced local conversation editor with title, user-message,
  assistant-response, and reasoning-fragment editing.
- Added Markdown rendering and formatting helpers in the editor.
- Added cross-account search, Markdown export, statistics, manual backup, and
  rotating automatic database backup.
- Added optional sidebar multi-select and batch deletion.
- Ported expert image relay, parallel multi-image vision description, and
  persisted image-fragment restoration to the modern API 102 track.
- Fixed creation of reasoning content on a message that originally had no
  reasoning fragment.
- Added automatic, idempotent migration for records damaged by the old
  reasoning-fragment writer.

### Repository

- Published stable, test, legacy, and diagnostic projects together.
- Added portable Termux and desktop/CI build discovery.
- Added an all-variant build script, regression test, public CI workflow,
  release checksums, and complete English documentation.
- Excluded proprietary target APKs, reverse-engineering output, logs,
  databases, prompts, and signing keys from version control.

## 1.6 and earlier

- Added the native settings entry and module activation page.
- Added system-prompt import, private storage, source-path display, and
  injection toggle.
- Added response-preservation hooks for client-side `CONTENT_FILTER`
  replacement.
- Added modern libxposed and traditional Xposed interface tracks.
- Added the Compose injection experiment, long-press edit experiment, and API
  102 load probe.
