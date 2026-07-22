# Troubleshooting

## The Deekseep Entry Does Not Appear

1. Confirm that the installed APK matches the framework interface.
2. Confirm the module is enabled.
3. For modern libxposed, scope it to `com.deepseek.chat`; do not add the module
   app merely to make activation detection pass.
4. Force-stop DeepSeek instead of only leaving the activity.
5. Open the root DeepSeek settings screen; the entry is intentionally removed
   on other routes.
6. Check the module launcher activation status.

For a current LSPosed installation, use the stable API 102 build. If loading is
uncertain, install and enable the API 102 load probe by itself.

## The Launcher Says “Pending Verification” Although LSPosed Is Enabled

Modern libxposed no longer injects a module into its own application, so the old
“hook `isModuleActive()` in the launcher” test is not valid. Current stable
builds receive LSPosed's service Binder through the official Xposed service
provider and accept a second heartbeat only from the `com.deepseek.chat` UID.

Enable the module, scope only DeepSeek, start DeepSeek once, and reopen the
launcher. “Enabled” means the framework service connected; “Active” additionally
means the target process reported real injection. Absence of an immediate
callback is shown as pending rather than falsely claiming the module is disabled.

## WeChat or Mobile Login Is Missing Overseas

Enable **Unlock WeChat and mobile login**, fully restart DeepSeek, and then open
the login page. This switch restores both native entries together and is
independent of the Google switch. WeChat still requires a usable installed
client; SMS login remains subject to the official service's region, number, and
risk decisions.

## Android Reports an Incompatible Update

The modern and legacy forms of a channel use the same package ID but separate
development signing keys. Disable and uninstall the old module APK before
installing the other interface variant.

Uninstalling the module package does not uninstall DeepSeek or automatically
remove prior database edits.

## DeepSeek Crashes After an Update

Disable Deekseep, force-stop DeepSeek, and confirm that the host works without
the module. Obfuscated symbols probably changed if the crash began immediately
after a DeepSeek update.

Report:

- exact DeepSeek version name and code;
- exact Deekseep asset name;
- Android and framework versions;
- only the redacted exception and nearby module log lines.

Do not upload an entire response log or database.

## Prompt Injection Does Not Work

- Confirm a prompt file was imported successfully.
- Confirm the injection switch is enabled.
- Reimport if the original document was moved or permission was revoked.
- Test with a new conversation.
- Check the private copied prompt rather than relying only on a displayed shared
  storage path.

## The Prompt Appears in Stored User Messages

Current complete builds strip the module's system prefix from local rows and
from online history before DeepSeek renders or persists it. Legacy stacked
prefixes are removed in one pass, using the injector's exact byte-zero wrapper
format so indented user-authored XML is preserved. Force-stop and restart
DeepSeek after updating the module so the new history hooks are installed.

## The Chat Editor Says a Conversation Has No Local Messages

DeepSeek may keep a cloud conversation in memory without creating its dynamic
SQLite message table. Current builds automatically invoke DeepSeek's own
session-selection callback when such a conversation is selected in the editor,
then wait up to about ten seconds for its online history. An in-memory partial
record may appear immediately in read-only mode and is promoted to editable
when the complete response arrives. The editor identifies the current account
from DeepSeek's login state rather than guessing from database timestamps. A
complete snapshot is written to SQLite only when you actually save an edit, and
that materialisation plus all field edits is atomic. If automatic loading cannot
start, return to the main screen once so DeepSeek can refresh its sidebar state,
then reopen the editor.

## An Answer Still Disappears

Response preservation must be enabled before the replacement event arrives. It
only handles the known client-side `CONTENT_FILTER` replacement path. Once that
event is observed, current builds retain the exact host message across a full
restart and restore it before a filtered history response is rendered or
written locally. It cannot restore output the server did not send or an answer
that had already been replaced before the durable hook observed it.

For a newly observed replacement, `dsprobe.log` should first contain
`preserved original response`. After a later server-history refresh it should
contain `restored preserved responses in online history` or
`restored preserved responses before history write`. The private records live
under `files/deekseep_preserved_responses` and are removed when that conversation
is explicitly deleted; do not hand-edit them.

## Added Reasoning and the Answer Both Disappeared

Install the stable 1.7.1 APK matching your framework, ensure it is the only active
Deekseep hook, and force-stop/restart DeepSeek. Both stable interfaces run
the startup migration, which should report a positive
`repairMalformedThinkFragments fixed=N` once and zero later.

If the database row no longer contains the original `RESPONSE`, restore it from
a database backup.

## A Search Result Does Not Open

Search includes user input, model output, and deep-reasoning fragments. Opening
uses DeepSeek's native sidebar session controller, so the target conversation
must belong to the currently logged-in account and be present in its native
session list. Switch to the account named by the local database and search
again. Deekseep intentionally does not fall back to its editor for this action.

## Local Edits Revert

The editor raises the local session cache version after a successful save, but a
different account database, a server-side replacement, or another active module
can still cause unexpected results. Confirm account selection, disable duplicate
variants, and inspect a backup copy before editing again.

## Expert Image Relay Fails

- Confirm the expert option is enabled before entering or reselecting the model.
- Test one image first.
- Confirm the account can use the required server models and upload path.
- Expect failure after host or server protocol changes.
- Enable relay diagnostics only for the shortest possible test.

For a later turn, diagnostics should show a send-point capture with
`effectiveModel=expert`, followed by a relay line explaining that the request's
null `model_type` was resolved from the send point. If the capture is missing,
the obfuscated `fu0`/`uu0` contract has probably changed.

Relay failure should fall back to the original request, but the original expert
path may still reject images.

## Images Disappear After Reopening a Relay Conversation

The current relay stores image fragment metadata and merges it only into a
marked relay request that has no server image. Signed image paths can expire,
and older relay messages created before capture support may have no persisted
source. New host history schemas can also break restoration.

## Backup or Export Files Are Missing

Check Android storage permission and the application's external files directory.
On recent Android versions, general file-manager visibility and all-files access
are controlled separately. Automatic backups are private and rotate to the five
newest copies.

## Safe Log Sharing

## The Local API Refuses the Connection

The HTTP server exists inside the DeepSeek target process. Open DeepSeek, enter
**Deekseep → DeepSeek Local API**, complete the unrestricted-background check,
keep the enable switch on, and wait until the
native backend is ready. Copy the URL again because the gateway can select a
later port when 8765 is occupied. A complete force-stop removes the listener;
the saved switch recreates it after DeepSeek starts again.

For a client on the phone, use the displayed `127.0.0.1` URL. A client on the
same trusted Wi-Fi/LAN may use the private IPv4 URL shown by the control page.
The listener has no TLS or public-network hardening: never forward its port or
expose it to an untrusted network, and always protect the Gateway Key.

If the page reports that background verification failed, set DeepSeek battery
usage to **Unrestricted / allow high power** and allow background activity. The
module validates both Doze exemption and Android's background-restricted state;
it intentionally refuses to listen while either check is failing.

## The Local API Returns 401

Copy the Key from the control page after the service starts and send it as
`Authorization: Bearer …` or `X-API-Key`. Saving a custom Key or generating a
random Key invalidates the previous value immediately. Ensure shell quotes do
not include extra whitespace.

The bundled `scripts/deekseep-agent.py` re-reads the private connection file once
after a 401 when the key was not supplied explicitly. Other clients must update
their stored key themselves.

## The Local API Returns protocol_mismatch

The OpenAI and Anthropic formats have different SSE contracts and are intentionally
mutually exclusive. Select OpenAI for `/v1/chat/completions`, `/v1/responses` and
Codex; select Anthropic for `/v1/messages` and Claude Code. OpenAI's base URL ends
in `/v1`; Anthropic's base URL does not.

## Claude Code Stays at Zero Tokens or Prints Tool Tags

Confirm the installed module is `1.7.1` or newer, select Anthropic mode,
and launch through `scripts/claude-deekseep`. The gateway emits native Anthropic
`thinking_delta`, `text_delta`, `tool_use`, and `input_json_delta` events instead
of exposing DeepSeek tool text. It uses OmniRoute's canonical `<tool>{json}</tool>`
translation. r19 serializes every DeepSeek generation through one fair account-wide
permit, prioritizes tool-bearing main/Task turns, and paces no-tool `deepseek-aux`
metadata before it enters that permit. Competing native generations can no longer
trigger `parallel_chat_limit` while a Claude stream appears frozen.

During a quiet Anthropic turn, r19 sends a standard `ping` followed by a valid
non-terminal cumulative `message_delta` usage update every ten seconds. Claude Code
filters a pure `ping` before its UI activity detector; the usage delta is what keeps
the bottom animation and long-thinking token count alive. A terminal usage value is
never allowed to fall below the largest activity value already emitted.

On Android, always use the launcher rather than the raw isolated CLI. It relocates
Claude Code's hard-coded `/tmp/claude` Task and sandbox paths to
`$PREFIX/tmp/claude`; an `EACCES mkdir '/tmp/claude/...'` line means the launcher was
bypassed or the isolated CLI was replaced after launch.

In `deekseep_api.log`, a real Agent turn should show `ANTHROPIC_BEGIN` with a
nonzero `tools=` value followed by `TOOL_CALL`. Raw `<tool>`, `deekseep_tool_calls`,
DeepSeek special-token tags, `Tool call:` labels, or XML in the Claude transcript indicate an old build or a new
unrecognised model envelope and should be reported with a redacted log. Never share
the connection/status files unchanged because they contain the Gateway Key.

Read-only verification is intentionally repeatable from r16 onward. A Read after Edit must
reach Claude Code again; only successful side-effecting equivalents such as the
same Write, Edit, or Bash command are suppressed.

If an Agent prints “I will read/edit/run…” and stops without doing anything, check
for `AGENT_DEFERRED_ACTION_REPAIR` followed by `TOOL_CALL` in `deekseep_api.log`.
r19 repairs one such deferred action even when the promise exists only in private
reasoning and the visible answer is empty. `/init` stays pending until Claude Code
has successfully read, written, or edited `CLAUDE.md`; a model that still refuses to
produce a usable tool call returns `agent_action_not_started` instead of pretending
that work completed.

## Claude Code `/clear` or `/new` Keeps the Old Conversation

`/new` is a local alias of `/clear` in the bundled Claude Code 2.1.0. The command
clears Claude Code's own message/UI state and rotates its UUID; it does not call
`/v1/messages`, so an API-side slash-command parser cannot fix a command that was
not executed locally. Type the command by itself and press Enter. When text was
pasted while autocomplete was open, the first Enter can select the completion;
press Enter again only if the command is still visible in the input line.
The bundled launcher also forces `ENABLE_INCREMENTAL_TUI=0` so a stale inherited
shell setting cannot keep old terminal rows visible after the local state was cleared.

After the local command succeeds, the next request is isolated by a one-way hash of
both the Claude session UUID and the first user turn. This keeps the same transcript
stable but creates a fresh native branch even if an old wrapper accidentally reuses
the UUID. To distinguish stale screen pixels from real context leakage, teach the old
conversation a random marker, clear, then ask for it. The current launcher/device
regression returns no old marker for both `/clear` and `/new`.

If old context is genuinely returned, verify the newly built module is loaded and
inspect only whether the private session map gained another `#s-<hash>` key; never
publish the file or API Key, and do not delete DeepSeek's private data as a first
troubleshooting step.

## SSE Stops When DeepSeek Goes to the Background

Keep DeepSeek set to unrestricted battery/background use and confirm that the
control page reports the foreground keeper as running. Battery exemption alone
does not prevent Android/OEM Cached Apps Freezer on every device. From r16 onward the module
starts a private `specialUse` foreground service from the visible control
flow; it holds a partial wake lock and sends a token-gated explicit no-op heartbeat
to DeepSeek every five seconds. This wakes the target before broadcast delivery,
while the injected hook consumes the event before the host share receiver sees it.

OpenAI streams send an SSE comment heartbeat every five seconds during PoW, cooldown
and buffered tools. Anthropic streams use `ping` plus the cumulative activity
`message_delta` described above. If the module foreground notification disappears,
turn the API off and on once from the control page. The keeper stops itself after
90 seconds without a DeepSeek acknowledgement and never changes the global freezer
or another application's process state. `client disconnected` remains counted
separately from upstream network failures.

For a direct reproduction, run `python3 scripts/deekseep-agent.py` and use
`/think` to toggle deep thinking. If a stream already emitted text, the client
will report the partial interruption without automatically replaying the turn.

## Account Import Returns HTTP 429 or `code=40002`

This is separate from Local API generation rate limiting. Older account-import
code sent `User-Agent: okhttp/4.12.0`, while DeepSeek 2.2.2 identifies its app
requests as `DeepSeek/<version> Android/<sdk>`. The old fingerprint can be
rejected at the edge with HTTP 429 before the token is evaluated.

Stable API 102 r21 corrected that fingerprint but still sent the token through
the unrelated telemetry header `x-auth-token`. DeepSeek's authenticated Ktor
client actually sends `Authorization: Bearer <token>`, so a valid export could
then reach the business layer but be rejected with HTTP 200 and `code=40002`.

Install stable **1.7.1** or newer. It matches the host bearer
authentication, device ID, timezone, version, locale, and User-Agent; spaces
batch checks; and performs at most one bounded 429 retry. With 1.7.1, a remaining
`code=40002` is treated as a genuinely rejected or expired token and is never
written to the account list.

## The Local API Returns 429, 503, or 504

- `host_not_ready` / 503: DeepSeek's native transport or PoW manager is still
  starting. Keep the app open and wait for the control page to report ready.
- `gateway_busy` / 503: the bounded HTTP worker queue is full; reduce client
  concurrency.
- `too_many_requests` / 429: the single fair native generation permit was occupied
  too long; retry with backoff.
- `request_deadline_exceeded` / 504: queueing, DeepSeek rate-limit cooldown,
  PoW, retries, and generation consumed the shared 170-second budget.

The gateway already spaces native starts and automatically waits 15/25/40
seconds after consecutive service-side rate limits. Aggressive client retries
only add more queued work; use one retry with jitter and a client timeout of at
least 180 seconds.

## Codex Can Chat but Does Not Offer apply_patch

Use Responses wire API and configure the model as `gpt-5.4`. This is a local
compatibility alias that makes current Codex load its complete tool catalogue;
generation still runs on DeepSeek. `deepseek-chat` remains suitable for normal
text and basic function tools.

If Codex receives an apply_patch call but refuses to execute it, inspect Codex's
workspace, sandbox, and approval policy. The gateway returns structured tool
calls but intentionally cannot override the client's permission decision.

On Termux, Codex CLI 0.144.5 has no native platform sandbox backend. Combining
that environment with an approval policy of `never` can reject a correctly
relative patch with the misleading message `writing outside of the project`.
This is a client-side permission decision, not evidence that the gateway changed
the path. Keep interactive approval for real work. To test the complete wire and
tool loop without logging out, run `python scripts/test-codex-responses-compat.py`;
it uses an isolated ephemeral `CODEX_HOME`, a controlled localhost mock, and a
disposable workspace. Do not copy its bypass flag into model-controlled work in
an important directory.

## A Responses Continuation Says previous_response_not_found

Responses continuation state is process-local, limited to 128 entries, and
expires after six hours. Restarting DeepSeek clears it. Resend the full input
history and start a new response chain. Codex normally supplies its full history
and does not depend on this cache.

## Where Are the Local API Logs?

The control page shows live counters and recent failures. Detailed logs are in
DeepSeek's private `files/deekseep_api.log`, with one `.1` rotation, plus
`deekseep_api_status.json` and `deekseep_local_api.txt`. These files contain the
complete Gateway Key and can include tool names, errors, and session diagnostics.
Redact them before sharing. The optional shared-storage connection copy may be
missing under Android scoped storage; use the control page instead.

## Safe Log Sharing

Before sharing, remove:

- prompts and responses;
- account and session IDs;
- file IDs and names;
- signed URLs or paths;
- authorization and proof-of-work values;
- database paths tied to an account.

Prefer a ten-to-twenty-line excerpt around the first error.
