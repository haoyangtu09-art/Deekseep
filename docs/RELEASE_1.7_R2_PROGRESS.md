# Deekseep 1.7 r2 Progress and Release Record

Last updated: 2026-07-15

## Status

Deekseep 1.7 r2 is implemented, built, documented, and published. The four
complete module variants now share the corrected reasoning writer, editable
reasoning duration, reasoning-aware global search, and native DeepSeek
conversation navigation.

The refreshed APKs remain attached to the existing `v1.7.0` GitHub Release.
No separate r2 Release was created. The FPA-oriented test APK is included on
that same page.

Public locations:

- Repository: <https://github.com/haoyangtu09-art/Deekseep>
- Release: <https://github.com/haoyangtu09-art/Deekseep/releases/tag/v1.7.0>
- Implementation commit: `248e2fd6ed7e312f5c79f9012346a45b456b9237`
- Successful CI run: <https://github.com/haoyangtu09-art/Deekseep/actions/runs/29389753930>

## Completed Requirements

The maintenance work completed the following requests:

1. Allow an assistant reply that originally had no reasoning chain to receive
   newly entered reasoning content.
2. Allow the displayed reasoning duration to be edited as a nonnegative number
   of seconds, including decimal values.
3. Keep the original assistant response visible after saving new reasoning and
   restarting DeepSeek.
4. Automatically repair malformed reasoning fragments written by the older
   editor implementation.
5. Search user requests, assistant responses, template responses, and deep
   reasoning content in the global local-history search.
6. Open a search hit in DeepSeek's native conversation screen instead of the
   Deekseep database editor.
7. Synchronize the behavior across stable, test, API 102, traditional Xposed,
   and FPA-oriented test distributions.
8. Publish the refreshed packages on the existing 1.7 project page and retain
   stable public download URLs.
9. Document the data failure, repair strategy, build variants, limitations,
   checksums, and release verification.

## Published Variants

| Project | Release asset | Interface | Package | Version |
|---|---|---|---|---|
| `module/` | `deekseep-stable-api102-v1.7.apk` | libxposed API 102 | `com.dsmod.probe` | code 9, `1.7-r2-api102` |
| `module-inject/` | `deekseep-test-api102-v1.7.apk` | libxposed API 102 | `com.dsmod.inject` | code 8, `1.7-r2-inject-api102` |
| `module-legacy/` | `deekseep-stable-legacy-v1.7.apk` | Traditional Xposed API 82+ | `com.dsmod.probe` | code 9, `1.7-r2-legacy` |
| `module-inject-legacy/` | `deekseep-test-legacy-v1.7.apk` | Traditional Xposed API 82+ / FPA test | `com.dsmod.inject` | code 8, `1.7-r2-inject-legacy` |
| `module-mtest/` | `deekseep-api102-load-probe-v0.1.apk` | libxposed API 102 diagnostic | `com.dsmod.mtest` | code 2, `0.1-api102-probe` |

The load probe contains no editor or search features. It is published only to
diagnose whether a framework loads a modern API 102 module.

Modern and traditional builds in the same channel use the same package name
but different signing certificates. Switching interfaces requires uninstalling
the installed interface variant first. Stable and test channels must not both
be enabled for DeepSeek because their hooks can conflict.

## Failure Root Cause

The broken editor inserted a new reasoning fragment in this form:

```json
{"type":"THINK","content":"new reasoning"}
```

DeepSeek 2.2.2 uses typed reasoning models represented by the decompiled
`ht7` and `ft7` classes. Its serializer requires both `id: Int` and
`content: String`. `elapsed_secs: Float?` is optional.

The editor used permissive `org.json` parsing, so the malformed row appeared to
save successfully. After process restart, DeepSeek strictly deserialized the
complete `fragments` array. A `THINK` object without a numeric `id` caused the
entire array to fail, so the valid `RESPONSE` in the same row also disappeared
from the UI. The response was not intentionally deleted by the save operation.

The old writer also left `thinking_enabled=0` on a message that now contained
reasoning. This made the message-level state inconsistent with its fragment
data.

## Corrected Reasoning Writer

All four complete projects use the same structured update rules:

1. Parse the existing fragment array without rebuilding unrelated objects.
2. Repair any `THINK` whose `id` is absent or nonnumeric.
3. Scan existing numeric fragment IDs and allocate a unique `max(id) + 1` ID.
4. Insert a newly created `THINK` before the existing response.
5. Preserve every original response ID, response body, and unrelated fragment.
6. Set `thinking_enabled=1` when the resulting message has nonempty reasoning.
7. Freeze a successfully edited or repaired session against an immediate stale
   server-cache overwrite.

The core JSON operations are implemented in `ChatEditorUi` through
`repairMissingThinkFragmentIds()`, `upsertFragmentContent()`, and
`updateThinkElapsed()`.

## Reasoning Duration

The editor now exposes **Reasoning duration (seconds)** for every assistant
message. The field maps to the native optional `elapsed_secs` value on the
`THINK` fragment.

- Empty input removes `elapsed_secs`.
- Integer and decimal seconds are accepted.
- Negative, NaN, and infinite values are rejected.
- JSON stores a number rather than a quoted string.
- A duration cannot be saved unless the same message has nonempty reasoning.
- Updating duration does not change the response or any unrelated fragment.

Assistant messages without reasoning always expose an empty reasoning editor,
so the user can create the missing chain and then set its duration.

## Automatic Repair of Older Rows

Before DeepSeek loads the first conversation, each complete build synchronously
scans the local account databases for assistant rows containing malformed
`THINK` objects.

The migration changes only a missing or nonnumeric reasoning ID, sets
`thinking_enabled=1`, preserves all response data, and freezes only sessions
that were repaired. It is idempotent, so later starts perform no additional
write after a row becomes valid.

The diagnostic message is:

```text
repairMalformedThinkFragments fixed=N
```

This migration restores rendering only when the original `RESPONSE` is still
present in the stored JSON. It cannot recreate text that was later removed or
overwritten and no longer exists in the database.

## Reasoning-Aware Global Search

Each complete project now contains a `ChatSearchUi` implementation. It searches
the active branch of every locally discovered conversation and creates separate
result entries for:

- user `REQUEST` content, excluding Deekseep's injected system-prefix text;
- assistant `RESPONSE` or `TEMPLATE_RESPONSE` content;
- concatenated `THINK` content labeled as deep reasoning.

The result cap remains 200 entries. Each row identifies its source and shows a
highlighted context snippet.

The stable traditional-Xposed project, which previously lacked a global search
entry, now exposes the same search behavior as the other complete builds.

## Native Conversation Navigation

The previous search result callback called `ChatEditorUi.showAt()`, which opened
the Deekseep editor. That did not satisfy native conversation navigation.

DeepSeek 2.2.2 exposes deep links for new and shared conversations, but it has
no supported URI for opening an arbitrary existing session ID. The implemented
path therefore reuses the host's own sidebar controller:

1. Hook the sidebar root composable `mc.f`.
2. Capture its complete `List<tp>` session list and first `ib3` click handler.
3. Match the selected search result against the host session field `tp.a`.
4. Invoke the original host callback `ib3.g(tp)` on the Android main thread.
5. Close the search and Deekseep dialogs only after the native callback succeeds.

The module does not fabricate a chat screen and does not fall back to the editor
when native opening fails.

The native list belongs to the currently logged-in DeepSeek account. A search
hit found in another local account database requires switching to that account
before it can be opened through the native controller.

## Main Source Changes

The synchronized implementation touches these ownership areas in all four
complete projects:

- `ChatEditorUi.java`: fragment repair, reasoning creation, duration UI and
  storage, startup migration support.
- `ChatSearchUi.java`: user, assistant, and reasoning indexing plus native hit
  selection.
- `Main.java`: migration startup and capture of the native session controller.
- `DeekseepUi.java`: search entry, editor wording, and dialog dismissal after
  successful native navigation.
- `DeekseepTools.java`: maintained search entry points delegate to the new
  implementation where that utility exists.
- `AndroidManifest.xml`: increased version codes and r2 version names.

Public documentation was updated in the root README, changelog, release notes,
feature reference, architecture, installation guide, troubleshooting guide,
variant matrix, project-specific READMEs, and reasoning-fix document.

## Build and Regression Verification

The final release set was created with:

```bash
bash scripts/build-all.sh
```

That command built all five projects and completed the following Android stages
for every APK:

- Java compilation;
- D8 conversion;
- AAPT2 resource linking;
- modern or traditional Xposed metadata packaging;
- zip alignment;
- APK signing.

The modern APKs contain `META-INF/xposed/`. The traditional and FPA APKs contain
`assets/xposed_init`.

All five APKs passed `zipalign -c`. `apksigner verify --verbose` confirmed APK
Signature Scheme v2 and v3 signatures. `aapt2 dump badging` confirmed every
package name, version code, and version name listed in the variant table.

The focused regression test reported:

```text
PASS: THINK content/id/duration transforms preserve the response
```

The regression test covers:

- adding a valid numeric-ID `THINK` while preserving the original response;
- repairing a legacy malformed reasoning fragment;
- migration idempotence;
- preservation of an existing duration during ID repair;
- numeric duration insertion and removal;
- rejection of negative, NaN, and infinite durations;
- preservation of response ID and content during duration changes.

GitHub Actions independently rebuilt the pushed source successfully on the
public repository.

## Published Checksums

The final `SHA256SUMS.txt` contains:

```text
78ef4e3d86d8bad82520cf7099276c8308b306a2308393f4b548068271839ce8  deekseep-api102-load-probe-v0.1.apk
52af0bd04f6410b23ee09d609f05e2d6abe44ecacf32512b025f489337bc6f32  deekseep-stable-api102-v1.7.apk
fe246a010a1499a3db4e34e206854b6abdf883eb1350bcef70904942d6673a62  deekseep-stable-legacy-v1.7.apk
d6d6fe5c9ab6b7078c2c0d97db19d9071ff781694794b9d0b1c7dcd069ac05b8  deekseep-test-api102-v1.7.apk
5011fade6bd4cd35b1b07f0c58f3f0a87560c7c9a04a82ff5711673f72224890  deekseep-test-legacy-v1.7.apk
```

After `gh release upload v1.7.0 --clobber`, every APK and the checksum file were
downloaded through the public `releases/latest/download/...` URLs without using
GitHub authentication. The repository page, Release page, and all six attachment
URLs returned HTTP 200. Hashes recalculated from those public downloads matched
the local release set exactly.

## Release Layout

The existing `v1.7.0` Release contains:

- `deekseep-stable-api102-v1.7.apk`;
- `deekseep-test-api102-v1.7.apk`;
- `deekseep-stable-legacy-v1.7.apk`;
- `deekseep-test-legacy-v1.7.apk`, the FPA-oriented test build;
- `deekseep-api102-load-probe-v0.1.apk`;
- `SHA256SUMS.txt`.

The asset names were retained so existing `releases/latest/download/...` links
continue to work. Android version codes were increased so an update within the
same interface and signing channel can install over the first 1.7 package.

## Current Limits and Follow-Up State

- Host hooks are verified against DeepSeek Android 2.2.2, version code 233.
  Obfuscated symbols can change in a later DeepSeek release.
- Native search opening is limited to the current account's captured sidebar
  session list.
- The editor directly modifies private local databases. A backup is required
  before use.
- A repaired row cannot recover response text that is no longer stored.
- Expert relay and related server-dependent behavior remain experimental.
- The current send-point persistence implementation for history image-fragment
  restoration builds successfully but has not completed device validation. It
  must not be described as a completed, device-validated feature.
- Search, editor, and migration behavior are not included in `module-mtest/`.

## Documentation Map

- This document is the consolidated implementation, verification, and release
  record for 1.7 r2.
- [Chat Editor Reasoning-Fragment Fix](CHAT_EDITOR_THINKING_FIX.md) contains the
  focused failure analysis and recovery contract.
- [Build Variants](VARIANTS.md) is the maintained feature matrix.
- [Architecture](ARCHITECTURE.md) explains startup, storage, and native
  navigation boundaries.
- [Installation](INSTALLATION.md) covers interface selection, signatures, and
  migration from the broken writer.
- [Troubleshooting](TROUBLESHOOTING.md) covers activation, repair, and native
  search-opening failures.
- [Expert Image Relay](EXPERT_IMAGE_RELAY.md) records the experimental relay and
  the current validation limit for history image restoration.
