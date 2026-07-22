# Architecture

## Process Model

Deekseep is an Android application and an Xposed module. The important code runs
inside the `com.deepseek.chat` process after the selected framework loads the
module. The launcher activity exists primarily for activation status, version
information, and storage-permission checks.

Only the DeepSeek target is required in the modern scope. Target scope installs
request, response, navigation, editor, and database hooks. Modern libxposed does
not hook a module application into itself; the launcher instead exposes the
official `com.dsmod.probe.XposedService` provider that receives the framework
Binder. A second provider method persists a target heartbeat only after the
Binder caller UID resolves to `com.deepseek.chat`, proving actual scope loading
without shared-storage ownership assumptions.

## Entry Interfaces

The modern stable build uses:

```text
XposedModule
  -> onPackageLoaded(PackageLoadedParam)
  -> META-INF/xposed/java_init.list
```

The legacy stable build uses:

```text
IXposedHookLoadPackage
  -> handleLoadPackage(LoadPackageParam)
  -> assets/xposed_init
```

Both entries compile the canonical `module/src/com/dsmod/probe` feature core.
The legacy build mechanically adapts the modern entry to
`IXposedHookLoadPackage` and supplies the around-hook contract through
`module-legacy/compat/LegacyXposedModule.java`; compile-only traditional-Xposed
stubs are not packaged. Feature code uses reflection because the target app is
obfuscated and does not expose a supported plugin API.

## Startup Sequence

The shared stable core performs the following high-level work:

1. installs activity lifecycle and file-picker result hooks;
2. records the current host activity and displays the first-run disclosure;
3. hooks completion request construction for optional prompt injection;
4. hooks online history construction and the version-matched history repository
   so injected prompt wrappers are removed before rendering or persistence;
5. repairs malformed local reasoning fragments before the host loads a
   conversation;
6. synchronously migrates historical prompt prefixes once, retrying a later
   launch if any account database could not be processed;
7. installs response replacement, message merge, and status hooks;
8. installs expert feature, transport, proof-of-work, and image-history hooks;
9. captures the host sidebar session list and native session click handler;
10. tracks settings navigation and optional sidebar selection;
11. starts the loopback OpenAI gateway when its explicit persistent switch is
    enabled and the native transport/PoW objects become ready;
12. injects the native Deekseep entry only on the settings route.

Failures are generally caught and logged so an unavailable optional hook does
not prevent the original host method from running.

## User Interface

The stable path uses Android Views in a full-screen dialog layered over the host
activity. It does not replace the host Compose tree. This provides a predictable
surface for:

- prompt selection and toggles;
- diagnostics;
- expert controls;
- chat editing;
- data export, search, statistics, and backup;
- help and risk information.

The discontinued test editions' direct Compose and host long-press menu
experiments are not part of 1.7.1 release support. Maintained risky features
are exposed through the stable builds' gated Experimental Features page.

## DeepSeek Local API Gateway Path

The stable gateway runs inside the injected DeepSeek process and binds an
authenticated local/LAN listener. A dedicated HTTP worker pool parses OpenAI Chat Completions or
Responses JSON/SSE while a fair one-slot native lane serializes actual DeepSeek
generations. The gateway owns only its independent local API Key; DeepSeek
credentials and PoW objects remain inside the host transport.

Each native model reuses hidden API sessions separated by workload and a one-way
hash of the client conversation. Full request context is still submitted with an
empty parent for every independent request, preventing history leakage through the
reused transport container. The hash combines Claude's session UUID with the first
user-turn fingerprint: `/clear` and `/new` normally rotate the UUID, while a stale
wrapper UUID still rotates when it submits a genuinely new transcript.
Session mappings persist across process restarts and are recreated when the service
rejects an obsolete ID. API session IDs are filtered from the cloud directory,
sidebar, and editor.

The module package also owns a small special-use foreground keeper. A no-animation
trampoline Activity starts it from the user's control-page action; its explicit
five-second heartbeat unfreezes and checks the target process. HTTP, credentials,
PoW, and obfuscated transport objects remain in DeepSeek and never cross into the
keeper process.

Chat and Responses share the same completion core. Tool definitions are mapped
to a strict request-scoped `<tool>` protocol, parsed and validated, then returned as
standard OpenAI tool items; the external Agent remains responsible for execution
and permissions. Queueing, PoW, native retries, streaming, and one optional
format-repair call share one deadline. See
[DeepSeek Local API](LOCAL_DEEPSEEK_API.md) for the complete contract.

Anthropic streaming has an explicit upstream-start boundary. HTTP arrival, queueing,
cooldown, and PoW do not emit `message_start`; entering native Flow collection starts
the message and thinking block, and the first synchronous delta uses the same
idempotent lazy start.

## Request Path

Prompt injection modifies the outgoing request's prompt field immediately before
the host sends it. The imported text is wrapped in a system marker, while the
user's visible input remains unchanged.

Expert image relay wraps the cold completion flow instead of blocking the UI
thread. Network work occurs when the host collects the wrapped flow. The wrapper
creates separate vision requests, rewrites the expert request only after
successful descriptions, and forwards the real expert flow to the original
collector.

## Response Path

Response preservation uses several defensive hook layers:

- raw streaming event observation for diagnostics;
- patch application filtering for a matching content-filter replacement;
- status and reconstructed-message observation;
- final merge protection that restores the original object when a later
  template object attempts to replace it;
- an evidence-gated private response store keyed by session and message ID;
- cold-history restoration in the `pw0` response, `gm8/fm8` repository write,
  and final `tp` message-application paths.

The durable layer is populated only after a real replacement event is observed.
It serializes the host's static message with DeepSeek's own serializer and only
matches a later message that is itself marked `CONTENT_FILTER` or contains a
`TEMPLATE_RESPONSE`. This prevents the feature from becoming a general server
history override. Explicit conversation deletion removes matching records.
An answer already replaced before this layer observed it has no recoverable
original and is intentionally not synthesized.

Normal append events must continue through the original path. Blocking them
would remove legitimate model output.

## Local Conversation Storage

DeepSeek stores per-account databases under its private database directory.
Each database has a session list and per-session message tables. Message rows
include a JSON fragment array with types such as:

- `REQUEST`
- `THINK`
- `RESPONSE`
- `TEMPLATE_RESPONSE`
- `FILE`

The editor uses structured `org.json` transforms and parameterized values for
content. Dynamic table names originate from discovered session IDs and are
quoted. The expert image history path reuses the host serializer when object
fidelity is required.

DeepSeek can render an online history response without materialising its
per-session table when a request-generation guard becomes stale. Deekseep keeps
a bounded process-local snapshot produced by the host's own persistence-row
conversion. `REPLACE` responses establish a complete baseline, while `MERGE`
responses are combined by message ID and rejected for editing if no complete
baseline was observed. Older server versions cannot overwrite newer snapshots.
The editor can display an incomplete native `tp` snapshot read-only while it
waits for a complete `pw0` response. For a cloud-only directory entry it invokes
DeepSeek's own session-selection callback and polls only that SID, instead of
requiring the user to open it manually or scanning every sidebar session. If a
user saves an edit, the editor rechecks snapshot identity and database version
under a write transaction, creates the standard table, inserts the exact host
rows, applies every edited field, strips prompt wrappers, and freezes the
session as one atomic commit. If DeepSeek wins the write race, the editor rolls
everything back and asks the user to reopen the conversation.

On every editor open, the captured `List<tp>` is also treated as a live session
directory. Its IDs, state-backed titles, timestamps, and models are merged with
`chat_session_list`, so a conversation created seconds earlier appears before
Room/SQLite flushes it. For a selected session, the editor compares cache
versions, branch heads, message IDs, and fragment JSON. A newer native branch is
shown over stale local rows but is marked read-only until the database reaches
the same state; a frozen local edit can only be superseded for display by a
strictly later appended message ID.

The current account database is resolved from DeepSeek's MMKV
`key_user_info.id`; file modification time is only a compatibility fallback.
Every session remains bound to its owning account database.

A `THINK` object requires a numeric `id` and string `content`.
`elapsed_secs` is optional and numeric. The editor preserves all other
fragments, allocates a unique ID when creating reasoning, and updates
`thinking_enabled` with the fragment array.

Image edits operate on FILE fragment descriptors rather than raw filesystem
paths. The transform clones the JSON tree, removes only image entries, retains
all non-image files, and adds selected descriptors to the first FILE fragment
(or creates a uniquely numbered FILE fragment before REQUEST). Existing uploads
retain their server metadata. A gallery selection is copied to a private
`files/deekseep_editor_images` master and a FileProvider-visible
`cache/captured` mirror; process startup recreates missing mirrors before the
history renderer resolves its stable `content://` descriptor.

Synthetic local conversations insert one `chat_session_list` row plus an empty
standard 13-column per-session table in a transaction. Fixed bottom actions then
append USER or ASSISTANT rows to that same conversation. Every change refreshes
an atomic private JSON sidecar. During DeepSeek's `p68`/`aw` cloud-directory
transaction, only editor-owned sidecar IDs are removed from the local prune
input; incoming server sessions still enter normally. The captured `mc.f`
directory retains a render-level union, and the `ed0.h` refresh boundary puts
missing sidecar-owned `tp` objects back into the canonical `ed0.e`
`SnapshotStateList`. This state-level merge matters because navigation and the
active-chat validator do not consume `mc.f`'s temporary argument copy. A delayed
sidecar restore is the fallback for host versions whose obfuscated transaction
hook no longer matches.

Explicit deletion uses the inverse contract. The module captures the central
sidebar event sink from `mc.f` and sends the same `h61(tp)` event as the host
delete item, preserving DeepSeek's authenticated server request. It then
idempotently removes the SQLite row/table and deactivates the exact sidecar even
when the host already removed the row. A short in-memory tombstone and snapshot
cleanup keep a stale native `tp` from being merged straight back into the
editor while the server request is in flight.

## Native Conversation Navigation

DeepSeek 2.2.2 has deep links for new/share flows but no supported URI for
opening an arbitrary existing conversation. The sidebar root composable
`mc.f` receives both the native `List<tp>` and its `ib3` session-click
handler. Each complete build captures those objects and appends only missing
editor-owned `tp` records to a same-type list. Search selection finds the matching
`tp.a` session ID and invokes the original
handler's `g(tp)` method. The Deekseep dialogs close only after that call
succeeds, revealing the host's normal chat screen.

## Storage Boundaries

The module stores configuration markers, prompt copies, local-session sidecars,
and durable editor-image masters in DeepSeek's private files directory because
the hook already runs inside that process. Shared
storage is used only for optional diagnostics, exported Markdown, user-visible
backups, and convenient APK copies.

Sensitive local artifacts are excluded from the public repository:

- target APKs and decompiled target output;
- databases and write-ahead logs;
- runtime diagnostics;
- imported prompts;
- local signing keys.

## Compatibility Boundary

Most reflected symbols are R8-obfuscated. Compatibility depends on:

- target class and method names;
- constructor and field layouts;
- serialized fragment schemas;
- server event and patch formats;
- proof-of-work and session APIs;
- Xposed framework behavior.

All hooks should fail open: when a contract cannot be confirmed, the host
operation proceeds unchanged. Version updates require symbol remapping and
device validation.

## Source Ownership Boundary

This repository publishes project-owned module source, tests, scripts, and
documentation. It does not publish the proprietary target APK or generated
decompiled target source. Technical notes describe only the minimum contracts
needed to understand the module.
