# Expert Image Relay

## Status

Expert image relay is an experimental feature in:

- the stable API 102 project; and
- the legacy test project.

The relay, multi-image flow, and history image restoration were device-validated
on the legacy experimental track on 2026-07-12, then ported to the current
modern stable source. It remains version-sensitive and is not a promise that the
service will accept expert features on every account or future app version.

## Problem

The target client can upload an image, but the expert model path may not consume
that image directly. Merely forcing a local "upload enabled" flag does not
change server model capability.

The relay converts images into text through the available vision model, then
sends the resulting description to the expert model.

## Request Flow

For an expert completion with image file IDs:

1. The module captures the full host file objects at the send point, before the
   request reduces them to IDs.
2. It wraps the host's cold completion flow. No network work blocks the Android
   main thread.
3. On collection, it obtains a fresh completion proof-of-work value.
4. It creates a temporary chat session.
5. It clones the existing request object, changes the model to vision, disables
   search and reasoning, uses a neutral image-description prompt, and keeps the
   selected image.
6. It collects text deltas until completion.
7. It deletes the temporary session.
8. It appends a marked description block to the original expert prompt and
   clears the image list.
9. It sends and forwards the rewritten expert completion flow.

If any required step fails, the wrapper falls back to the original host flow.

## Proof-of-Work Isolation

Completion proof-of-work values are single-use. Reusing the original expert
request's value for the vision request can consume it first and make the real
expert request fail.

The relay captures the host proof-of-work manager and mints a separate value for
each generated vision request. The original expert request keeps its own value.

## Temporary Session Isolation

Sending the vision request into the user's real conversation would insert vision
messages into that session and could change retry behavior. Every relay image
uses a temporary session that is deleted after the description is collected.

## Multi-Image Processing

A vision request containing multiple images plus unrelated text was observed to
refuse or fail to inspect the images reliably. The final design uses one clean
temporary session per image.

For multiple images, descriptions run in parallel with bounded waits. Results
are joined in original image order and labeled before being appended to the
expert prompt. This reduces latency and keeps each vision context isolated.

## Preserving Images in Reopened History

The server stores the expert request as rewritten text with no attached image.
Without extra handling, reopening an online conversation can replace the local
image-bearing message with that text-only server history.

The module therefore:

1. captures complete image file metadata before request rewriting;
2. serializes an image-only `FILE` fragment per real session;
3. recognizes only messages containing the relay's private request marker;
4. merges persisted image fragments into a server message only when the server
   message has no image;
5. preserves server request and response text unchanged;
6. resolves fragment ID collisions without renumbering server fragments;
7. strips the injected system prompt and relay description from local display
   while leaving the sent expert text intact.

The merge is gated by the expert/relay setting and exact per-message markers so
ordinary default or vision conversations are not modified.

## Safety Properties

- No temporary vision request reuses the expert proof-of-work value.
- No temporary vision request uses the user's real session.
- Failed relay preparation restores or forwards the original request.
- Existing server images are never replaced.
- Only image entries are retained from a mixed file fragment.
- Server text remains authoritative.
- Dangerous session IDs are rejected before constructing persistence paths.
- Decode, reflection, or serialization failure leaves the host data unchanged.

## Known Limits

- Obfuscated request, flow, proof-of-work, and history classes can change.
- Signed image paths can expire.
- Temporary-session creation and vision access remain server-controlled.
- Extra network requests increase latency and data use.
- Multiple images are bounded by relay timeouts.
- A changed server history format can disable restoration.
- Diagnostic logs can contain sensitive prompt and file information.

## Diagnostics

Relay diagnostics are written only when enabled. Useful stages include:

- send-point file capture;
- flow wrapping;
- proof-of-work minting;
- temporary session creation/deletion;
- per-image description length and timing;
- expert request rewrite verification;
- persisted image count;
- history image merge decisions.

Redact prompts, session IDs, file IDs, signed paths, and model output before
sharing a log.
