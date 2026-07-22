# Experimental Features

Deekseep 1.7.1 consolidates maintained high-risk functions into a dedicated
**Experimental Features** page in both stable builds. They are separated from
the normal settings because they depend on obfuscated host internals, private
service behavior, or local Agent tool execution and can regress without notice.

## First-Entry Disclosure

The first attempt to enter the page opens a non-dismissible risk disclosure.
It warns that experimental hooks may be unstable, can cause account restriction
or banning, and may damage, overwrite or lose chat records, cache data or Agent
workspace files. Do not test with an important account or on a device holding
the only copy of important conversations or files.

The user can choose **Exit**, which closes the disclosure without entering or
recording acceptance. **Confirm** is disabled for five seconds; after the
countdown it becomes **Confirm and enter**. A versioned acceptance marker is
stored only after confirmation, so the disclosure is shown again if its safety
text is materially revised.

## Expert Mode and Image Relay

The page contains the expert-feature unlock and the image-to-vision relay. The
relay can describe uploaded images in isolated temporary sessions and append
the ordered descriptions to an expert request. It also preserves image metadata
for local history rendering. Server authorization, proof-of-work, model routing
and obfuscated data classes remain outside Deekseep's control.

## Local API Service

The same page contains the local API control entry. Its own panel provides one
**Format / current format** row; selecting it opens a popup for OpenAI or
Anthropic instead of presenting two permanent protocol buttons.

The OpenAI mode implements model listing, Chat Completions and Responses,
including streaming, structured output and Agent tool-result continuation. The
Anthropic mode implements Messages, count_tokens, thinking/text/tool streaming
and Claude Code tool loops. Requests are authenticated by a separate gateway
key. Keep that key and all connection diagnostics private.

The service adds model-facing instructions that workspace creation or editing
must be performed through the client's Write/Edit/NotebookEdit/apply_patch-style
tools. A bounded repair can turn a code-only or narrated response into a real
tool call, but the client still controls sandboxing, approval and filesystem
permissions.

## Separate Help

The Experimental Features page has its own **Help & Issues** entry. It covers
format endpoints, API authentication/readiness errors, Codex setup, Claude Code
`/clear` and `/new`, delayed thinking lifecycle, and risk controls. Questions
specific to these features are intentionally removed from the normal help page.

## Safe Use

- Back up the DeepSeek database and Agent workspace first.
- Use a disposable account and nonessential conversations.
- Keep only one Deekseep module enabled for DeepSeek.
- Retain client sandboxes, confirmation prompts and minimal tool permissions.
- Stop the gateway immediately after repeated tool calls, stale context,
  synchronization errors or an incompatible DeepSeek update.
- Never publish unredacted API keys, request logs, databases or account data.
