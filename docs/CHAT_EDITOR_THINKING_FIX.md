# Chat Editor Reasoning-Fragment Fix

## Symptom

The failure sequence was:

1. Open a conversation containing an assistant response with no reasoning
   fragment.
2. Use the Deekseep editor to add reasoning content.
3. Save and restart DeepSeek.
4. Reopen the conversation.
5. The new reasoning is missing, and the original assistant response is also
   invisible.

The original response was not intentionally deleted. The old editor wrote an
invalid fragment array that the host could not deserialize after restart.

## Root Cause

### Missing Required Fragment ID

The old insertion path created:

```json
{"type":"THINK","content":"new reasoning"}
```

The target's reasoning-fragment serializer requires:

| Field | Requirement |
|---|---|
| `type` | Optional, defaults to `THINK` |
| `id` | Required integer |
| `content` | Required string |
| `elapsed_secs` | Optional |
| `references` | Optional |
| `stage_id` | Optional |

Because `id` was absent, strict host deserialization failed for the entire
fragment list. The host did not skip only the invalid reasoning object, so the
valid `RESPONSE` in the same array also disappeared from rendering.

The editor itself used permissive `org.json` parsing, which is why the save could
appear correct until the host process restarted and loaded its typed model.

### Inconsistent Message-Level Flag

The old SQL statement updated only `fragments`. A message that originally had no
reasoning retained `thinking_enabled=0`, leaving row metadata inconsistent even
if the new fragment had otherwise been valid.

### Existing Damaged Rows

Correcting only future writes would not restore rows already saved by the broken
version. They required a narrow compatibility migration.

## Evidence

Normal protocol samples contain numeric IDs:

```json
{
  "thinking_enabled": false,
  "fragments": [
    {"id": 2, "type": "RESPONSE", "content": "answer"}
  ]
}
```

A response with reasoning uses `thinking_enabled=true` and a numeric ID on the
`THINK` object. Static serializer inspection also confirmed that `id` and
`content` are required constructor fields.

## Fix

The stable API 102 editor now uses one structured transform,
`upsertFragmentContent()`:

1. repair any old `THINK` object whose ID is missing or nonnumeric;
2. scan existing numeric IDs;
3. assign the new reasoning object `max(id)+1`;
4. insert it before the existing response in array order;
5. preserve every existing fragment, response ID, and response body;
6. set `thinking_enabled=1` in the same database update when nonempty reasoning
   is present.

Using a new unique ID avoids modifying the identity of the original response.
Array order still places reasoning before the answer.

## Automatic Migration

At module startup, before the host opens a conversation, the stable API 102
build scans all local account databases and session message tables.

The migration:

- reads assistant rows that contain a reasoning fragment;
- changes only `THINK` fragments with a missing or nonnumeric ID;
- assigns a unique numeric ID;
- sets `thinking_enabled=1`;
- keeps the response and all other fragments unchanged;
- freezes only a repaired session against immediate stale server overwrite;
- is idempotent, so a second startup performs no write.

The log line is:

```text
repairMalformedThinkFragments fixed=N
```

## Regression Test

`module/tests/com/dsmod/probe/ChatEditorThinkingRegressionTest.java` covers:

- adding reasoning to `RESPONSE id=2`;
- creation of `THINK id=3` before the response;
- preservation of response ID and content;
- detection of nonempty reasoning;
- repair of a legacy `THINK` with no ID;
- idempotence of a second repair pass.

Run it after building the stable project:

```bash
cd module
bash build.sh
bash test-thinking-regression.sh
```

Expected result:

```text
PASS: chat editor THINK JSON transform and legacy repair
```

## Scope and Recovery Limit

The "add reasoning to a response with no reasoning fragment" implementation
exists only in the stable API 102 project, so the code fix and migration are
intentionally limited to that project.

The migration can restore visibility when the original `RESPONSE` still exists
in the malformed JSON row. It cannot reconstruct response text that a later
server synchronization or manual operation already deleted from the database.
