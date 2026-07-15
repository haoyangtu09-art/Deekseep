# Test API 102

This project carries modern libxposed API 102 experiments that are intentionally
separate from the recommended stable package.

- Package: `com.dsmod.inject`
- Interface: libxposed API 102
- Metadata: `META-INF/xposed/`
- Output: `ds-inject.apk`
- Public asset: `deekseep-test-api102-v1.7.apk`

Its defining experiments are direct Compose settings-row injection and an item
added to the host's long-press message menu. It also contains core prompt,
response, editor, and local data tools, but not every feature from the stable
modern project. The v1.7 r2 build includes the shared reasoning creator,
editable reasoning duration, reasoning-aware search, and native chat opening.

```bash
cd module-inject
bash build.sh
```

Do not enable it together with the stable package for the same target process.
See [Build Variants](../docs/VARIANTS.md).
