# Feature Reference

This document describes the current behavior of the complete projects. Refer to
[Build Variants](VARIANTS.md) for per-APK availability.

## Settings Entry

Deekseep hooks the DeepSeek settings route and adds a native Android View entry
above the host UI. The entry is tied to the settings route and removed when the
user navigates away. This avoids depending on a stable public Compose extension
point.

The module's launcher activity reports activation state, version, Xposed API,
and build date. Legacy builds also use a small provider-based handshake because
some patching environments inject only the target app process.

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

- conversation title editing;
- user request editing;
- assistant response or template-response editing;
- reasoning-fragment editing;
- creation of reasoning content on a response that originally had none;
- a custom nonnegative reasoning duration stored as the native optional
  `elapsed_secs` number;
- viewing multiple local accounts;
- native DeepSeek conversation navigation from global search results;
- Markdown rendering and formatting helpers.

Saved sessions receive a high local cache version to keep a stale server copy
from immediately overwriting the local edit. This is intentionally invasive:
create a database backup before editing.

Version 1.7 r2 provides the same reasoning writer and malformed-`THINK`
migration in all four complete builds. See
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
The module tracks selected sessions, adds visual selection marks, invokes host
delete actions where possible, and falls back to deleting the local session row
and message table when necessary.

Batch deletion is destructive and may later interact with server synchronization.
Back up first.

## Expert Mode and Image Relay

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

The relay path was device-validated in the legacy experimental track and later
ported to the stable modern track. It remains experimental because transport,
proof-of-work, model, and obfuscated class contracts are service-version
dependent. See [Expert Image Relay](EXPERT_IMAGE_RELAY.md).

## Experimental Host UI Injection

The test variants include two additional experiments:

- a Compose settings-row injection path;
- a host long-press message-menu "Edit" action.

They are kept separate from the recommended native overlay because Compose and
obfuscated host menu structures change frequently.

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
