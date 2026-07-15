# Changelog

All notable public releases are documented here.

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
- Added a consolidated [1.7 r2 implementation and release record](docs/RELEASE_1.7_R2_PROGRESS.md)
  with root-cause, verification, checksum, and public-link details.

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
