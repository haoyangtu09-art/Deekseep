# Deekseep 1.7

This release publishes all maintained interface and experiment tracks in one
source repository. The attached APKs were refreshed in place with the v1.7 r2
maintenance build; this is still the same release page.

## Recommended Download

Use `deekseep-stable-api102-v1.7.apk` with a current LSPosed installation.

## Included APKs

- `deekseep-stable-api102-v1.7.apk`
- `deekseep-test-api102-v1.7.apk`
- `deekseep-stable-legacy-v1.7.apk`
- `deekseep-test-legacy-v1.7.apk`
- `deekseep-api102-load-probe-v0.1.apk`
- `SHA256SUMS.txt`

## Highlights

- Complete modern API 102 stable toolkit.
- Traditional Xposed compatibility builds.
- Experimental Compose and host message-menu tracks.
- Expert image-to-vision relay and multi-image processing where supported.
- Local editor, search, export, statistics, and database backup.
- Reasoning can be added to replies that originally had no chain in all four
  complete variants, with a custom reasoning duration in seconds.
- Fixed malformed reasoning insertion and automatic recovery of affected rows
  across stable, test, modern, and traditional-Xposed builds.
- Global search now includes user input, model output, and deep-reasoning text.
- Selecting a search result opens DeepSeek's native conversation screen.
- Portable Termux/desktop builds and public CI.

The traditional test APK is the FPA-oriented experimental build and is attached
as `deekseep-test-legacy-v1.7.apk`; it is not published as a separate release.

The complete implementation, verification, checksum, and publishing record is
available in the
[1.7 r2 progress document](https://github.com/haoyangtu09-art/Deekseep/blob/main/docs/RELEASE_1.7_R2_PROGRESS.md).

## Important

Modern and legacy builds in the same channel share a package ID but use
different signatures. Uninstall before switching interfaces. Enable only one
Deekseep hook implementation for DeepSeek.

Read the [Disclaimer](https://github.com/haoyangtu09-art/Deekseep/blob/main/DISCLAIMER.md)
and [Installation guide](https://github.com/haoyangtu09-art/Deekseep/blob/main/docs/INSTALLATION.md)
before use.
