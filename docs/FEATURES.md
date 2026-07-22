# Feature Reference

This document describes the shared 1.7.1 stable feature core. Refer to
[Build Variants](VARIANTS.md) for interface packaging.

## Settings Entry

Deekseep hooks the DeepSeek settings route and adds a native Android View entry
above the host UI. The entry is tied to the settings route and removed when the
user navigates away. This avoids depending on a stable public Compose extension
point.

The module's launcher activity reports activation state, version, Xposed API,
and build date. The stable modern build receives the framework Binder through
the official `<applicationId>.XposedService` provider and separately accepts a
UID-validated heartbeat from the injected DeepSeek process. This distinguishes
“framework enabled” from “target process actually loaded” without relying on
the obsolete assumption that modern libxposed hooks the module app itself.

The bottom of the in-host Deekseep page shows the exact module version, Xposed
interface/API, compile time, and installed DeepSeek version for compatibility
screenshots.

## System Prompt Injection

The settings page imports a text document through Android's Storage Access
Framework. The selected content is copied into DeepSeek's private files
directory. When a real storage path is available, the module records it and
attempts to maintain a symbolic link; the private copy remains the fallback.

When enabled, the outgoing completion request is rewritten as:

```text
<system>
imported prompt
</system>

original user prompt
```

The visible input box is not changed. Stored prompt prefixes are removed from
local user-message rendering and periodically cleaned from persisted history.

## Response Preservation

DeepSeek can stream an answer and later apply a client-side patch that replaces
the visible text with a template marked `CONTENT_FILTER`. The optional response
preservation path:

1. observes the streaming message and its original in-memory object;
2. ignores the matching replacement patch;
3. prevents a later template object from replacing the original object during
   final message merge;
4. leaves normal append events and unrelated state transitions untouched.

This feature preserves only text already delivered to the device. It does not
bypass a server refusal, retrieve hidden server data, or guarantee an answer.

## Conversation Editor

The editor opens the per-account DeepSeek SQLite databases from inside the host
process. It reconstructs the current parent chain and supports:

- a full live-sidebar refresh every time the editor opens, including newly
  created conversations not yet present in `chat_session_list`;
- selection of the newest native message branch when SQLite is briefly behind;
- creation of a blank local conversation, followed by any number of user or AI
  messages through the fixed bottom actions;
- conversation title editing;
- user request editing;
- removal, replacement, and reuse of images on user messages;
- assistant response or template-response editing;
- reasoning-fragment editing;
- creation of reasoning content on a response that originally had none;
- a custom nonnegative reasoning duration stored as the native optional
  `elapsed_secs` number;
- viewing multiple local accounts;
- native DeepSeek conversation navigation from global search results;
- Markdown rendering and formatting helpers.

Image management can reuse a successful existing upload or select a new image
from Android's system photo picker. New selections are copied to a durable
master under DeepSeek's private `files/` directory and mirrored into the host
FileProvider's `cache/captured/` root. The mirror is recreated after process
restart or cache eviction, so historical images do not depend on a temporary
server credential. Non-image attachments and unrelated fragments remain intact.

New conversations are local synthetic records with a fresh UUID and the native
message-table schema. Each editor-owned session is also stored in an atomic
private sidecar. DeepSeek's `p68` cloud-directory prune excludes only those
sidecar IDs, while all new server conversations and normal server deletions keep
their original behavior. The delayed `ed0.h` refresh restores missing local
`tp` objects into the canonical `ed0.e` state list; `mc.f` also keeps a render
fallback. Navigation, the active chat, and the sidebar therefore observe the
same union, while the sidecar repair worker remains a compatibility fallback.

When live memory contains a newer turn than SQLite, the editor displays that
turn immediately but keeps it read-only until DeepSeek finishes writing it.
This gives an up-to-date view without applying edits against an obsolete
database baseline.

Saved sessions receive a high local cache version to keep a stale server copy
from immediately overwriting the local edit. This is intentionally invasive:
create a database backup before editing.

Both stable 1.7.1 builds provide the same refreshed editor, conversation
creation, gallery/image manager, reasoning writer, and malformed-`THINK`
migration. See
[Chat Editor Thinking Fix](CHAT_EDITOR_THINKING_FIX.md).

## Local Data Tools

The maintained data-tools implementation provides:

- one Markdown file per local conversation;
- keyword search across user requests, assistant responses, and deep-reasoning
  fragments;
- native conversation opening through the host's own session controller;
- totals for sessions, messages, and text volume;
- immediate database backup;
- automatic backup at most once every 24 hours;
- retention of the five newest automatic backups.

Backups are copies of private application data. Protect them like the original
chat database.

DeepSeek does not expose a public URI for opening an arbitrary existing session.
Deekseep therefore captures the host session list and click handler while the
sidebar is composed. A result can open only a conversation available to the
currently logged-in account's native session list; cross-account hits require
switching to that account first.

## Sidebar Multi-Select

When enabled, a long press on the conversation sidebar enters a selection mode.
The module tracks selected sessions and adds visual selection marks. Confirmed
deletions are sent through DeepSeek's original session-delete event so its
authenticated server request still runs. Deekseep then idempotently removes the
local directory row and message table, deactivates the exact editor sidecar, and
clears stale native/history snapshots. This prevents an apparently deleted
edited conversation from being restored by its sidecar on the next cold start.

If the native event is unavailable, the result explicitly reports that fact
instead of calling the item merely “submitted.” A server request can still fail
for network or account reasons; local removal and native-request counts are
therefore reported separately. Batch deletion is destructive. Back up first.

## Experimental Features

Expert unlock/image relay and the local API are grouped in a dedicated subpage.
On first entry, the user must read an account-ban, instability and data-loss
disclosure; **Exit** does not enter or record acceptance, while confirmation is
disabled for five seconds. The subpage has its own Help & Issues entry. See
[Experimental Features](EXPERIMENTAL_FEATURES.md).

## Expert Mode and Image Relay (Experimental)

The expert flag hook attempts to populate feature templates for reasoning,
search, and file upload. Server-side authorization still controls what the
service accepts.

For an expert request containing images, the relay implementation can:

1. capture complete uploaded-image metadata before the normal request reduces it
   to file IDs;
2. obtain a fresh completion proof-of-work value;
3. create a temporary session;
4. send each image to the vision model in its own clean temporary session;
5. collect the descriptions in parallel and preserve input order;
6. delete temporary sessions;
7. append the descriptions to the expert prompt and clear image files from the
   expert request;
8. persist image fragments locally so reopening server-synchronized history can
   still render the original images.

The relay path is compiled into both stable interface packages from the same
core. It remains experimental because transport,
proof-of-work, model, and obfuscated class contracts are service-version
dependent. See [Expert Image Relay](EXPERT_IMAGE_RELAY.md).

## Native Google Login Restoration

DeepSeek's mainland login-state builder omits the app's existing Google option
while retaining phone and WeChat methods. Deekseep provides an
opt-in switch that inserts that same native option back into the populated login
method list without changing the reported device region or removing domestic
methods. Clicking it continues through DeepSeek's Credential Manager, Google ID
token, captcha configuration, and official OAuth exchange path; Deekseep does
not implement a replacement OAuth screen or log the token.

This restores a client entry only. Google Play services availability and
DeepSeek's server-side region, account, and risk decisions still apply.

## Native WeChat and Mobile Login Restoration

DeepSeek's overseas login-state builder can omit the app's native WeChat and
SMS/mobile-number entries. A separate combined switch inserts those two host
option singletons into the populated list, immediately after Google when it is
present, while preserving password, registration, one-tap, and every existing
entry. It does not turn on the Google switch.

Clicks still use DeepSeek's WeChat SDK and SMS verification routes. The module
does not capture credentials, simulate success, or bypass installation,
region, number, account, or server-risk checks.

## Local API Gateway (Experimental)

Both stable builds place expert-image relay and the opt-in HTTP gateway in one
Experimental Features subpage. First entry shows a risk/data-loss/account-ban
disclaimer whose confirmation unlocks after five seconds; the experimental help is
separate from the main help. The gateway has an independent manually drawn control
page. It listens on local/LAN addresses, uses a separate random or
user-defined Gateway Key, and converts the already authenticated DeepSeek native
transport into a user-selected OpenAI Chat/Responses or Anthropic Messages API.

Before listening, a custom preflight requires DeepSeek to be exempt from battery
optimization and not background-restricted. The gateway supports normal and SSE
responses with five-second protocol activity heartbeats, chunked request bodies, Chat
function tools, Responses function/custom/shell/apply_patch/namespace items,
Anthropic tool_use/tool_result and count_tokens, tool-result continuation,
`previous_response_id`, deep-thinking parameters, and native web search. A
`gpt-5.4` compatibility alias lets current Codex load its
full built-in tool metadata while the actual request still uses DeepSeek's
default model.

Native generations enter one fair serialized lane; tool-bearing main/Task work
receives priority while no-tool metadata is paced before it occupies the lane.
Hidden API sessions are reused per native model, workload, and hashed client
conversation to avoid repeated session-create 429 errors without carrying `/clear`
or `/new` context forward. The scope combines a hashed client UUID and first-user-turn
fingerprint, covering stale UUIDs as well as normal Claude session rotation. Invalid session IDs are recreated automatically before
any output has reached the client. Requests share a bounded deadline across queueing,
PoW, retries, streaming, and the optional tool-format repair. Real server rate limits
trigger an adaptive cooldown instead of a retry storm.

Anthropic quiet periods emit both standard `ping` and a non-terminal cumulative
usage delta, keeping Claude Code's spinner and long-thinking token display active.
The message/thinking lifecycle starts only after the native upstream Flow starts;
queueing and PoW no longer publish a synthetic early thinking state.
A narrated or reasoning-only promise to perform work receives one bounded repair
into a real client tool call; `/init` remains active until `CLAUDE.md` is actually
read, written, or edited.

Because an unrestricted app can still enter Android's Cached Apps Freezer, the
module starts a private special-use foreground keeper through a visible
user action. A five-second token-gated heartbeat keeps the injected host responsive
without disabling the global freezer or touching other applications; the keeper
auto-stops when the API is disabled or the host stops acknowledging it.

For Agent histories, the gateway pairs each structured call ID with normalized
tool name/namespace/arguments and its output. If the output succeeded, an exact
repeat is withheld and repaired into a final answer or a genuinely different
call. Different parameters and explicitly failed outputs remain callable, so
this guard prevents repeated side effects without blocking normal multi-step
workflows.

The control page displays the actual URL and Key, copy actions, custom/random
Key actions, foreground-keeper and backend readiness, received/success/failed/
cancelled/tool counts, queue state, and failure reasons. HTTP and native transport
remain in the DeepSeek process; the companion service only supplies the wake/health
heartbeat. Diagnostics contain the complete Key, so they must not be shared
unredacted. See
[DeepSeek Local API](LOCAL_DEEPSEEK_API.md).

The former test-edition direct Compose settings and host long-press menu hooks
are discontinued and are not part of 1.7.1 release support.

## Diagnostics

Response-event logging is opt-in. When enabled, the module records raw streaming
events and selected hook decisions in the host private files directory and may
mirror them to shared storage. Expert relay has a separate diagnostic log.

Raw events can contain full prompts, model output, session identifiers, file
metadata, and signed paths. Never attach an unreviewed log to a public issue.

## Built-In Risk Disclosure

The first successful injection shows a risk disclosure. Acceptance is stored in
the host private files directory. This notice supplements, but does not replace,
the repository [Disclaimer](../DISCLAIMER.md).
