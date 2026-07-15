# Contributing

Contributions are welcome when they keep the project buildable, minimize target
version assumptions, and respect the source-ownership boundary.

## Before Opening a Change

1. Read the [Disclaimer](DISCLAIMER.md), [Architecture](docs/ARCHITECTURE.md),
   and [Build Variants](docs/VARIANTS.md).
2. Search existing issues.
3. Identify the exact target variant and DeepSeek version.
4. Keep changes scoped to the feature and interface being tested.

## Development Setup

```bash
git clone https://github.com/haoyangtu09-art/Deekseep.git
cd Deekseep
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
bash scripts/build-all.sh
```

For a narrow change, build the affected project first, then run the complete
build before submitting.

## Pull Request Requirements

- Explain the observed behavior and root cause.
- List the exact DeepSeek, Android, and Xposed framework versions used.
- State which variants were modified and why.
- Include focused tests or a reproducible device validation procedure.
- Confirm all affected variants compile.
- Update English documentation and the feature matrix when behavior changes.
- Keep optional hooks fail-open.
- Do not include signing keys, APKs, databases, prompts, full logs, or user data.

## Clean Source Boundary

Do not submit:

- the proprietary DeepSeek APK;
- decompiled DeepSeek source or generated reverse-engineering output;
- copied proprietary resources;
- credentials, proof-of-work values, signed file URLs, or account identifiers.

A small original description of an observed method contract is acceptable when
needed to explain compatibility. Contributions must contain only code and text
the contributor has the right to publish.

## Code Style

- Target Java 8 language compatibility used by the current build.
- Prefer existing reflection and hook helpers.
- Catch failures at optional hook boundaries and allow the original host call.
- Use structured JSON or host serializers instead of manual JSON strings.
- Keep database transforms narrow, idempotent, and covered by a backup path.
- Avoid unrelated refactors across parallel variants.

## Diagnostics

Share the smallest redacted excerpt that proves the behavior. Remove prompts,
responses, session IDs, file metadata, signed paths, tokens, and personal
filesystem details.
