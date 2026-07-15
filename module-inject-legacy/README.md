# Test Legacy Compatibility

This is the traditional Xposed experimental project for FPA and older framework
environments.

- Package: `com.dsmod.inject`
- Interface: traditional Xposed API 82+
- Entry: `assets/xposed_init`
- Output: `ds-inject-legacy.apk`
- Public asset: `deekseep-test-legacy-v1.7.apk`

It contains Compose and host message-menu experiments, local data tools,
sidebar multi-select, and the FPA-oriented expert image-relay experiments. The
v1.7 r2 build includes the shared reasoning creator, editable reasoning
duration, reasoning-aware search, and native chat opening. Parallel multi-image
description and history image-fragment restoration remain experimental; see the
feature documentation for their current validation status.

```bash
cd module-inject-legacy
bash build.sh
```

This is a test build. Do not enable it together with another Deekseep variant.
See [Expert Image Relay](../docs/EXPERT_IMAGE_RELAY.md) and
[Build Variants](../docs/VARIANTS.md).
