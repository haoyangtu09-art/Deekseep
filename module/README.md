# Stable API 102

This is the recommended Deekseep project for a current LSPosed installation.

- Package: `com.dsmod.probe`
- Interface: libxposed API 102
- Metadata: `META-INF/xposed/`
- Output: `ds-probe.apk`
- Public asset: `deekseep-stable-api102-v1.7.apk`

It contains the broadest maintained stable feature set, including the advanced
chat editor, v1.7 r2 reasoning creation and duration support, reasoning-aware
native chat search, local data tools, sidebar multi-select, and the current
expert image relay port.

```bash
cd module
bash build.sh
bash test-thinking-regression.sh
```

See the root [README](../README.md), [feature reference](../docs/FEATURES.md),
and [variant matrix](../docs/VARIANTS.md).
