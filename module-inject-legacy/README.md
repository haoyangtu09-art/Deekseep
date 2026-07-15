# Test Legacy Compatibility

This is the traditional Xposed experimental project for FPA and older framework
environments.

- Package: `com.dsmod.inject`
- Interface: traditional Xposed API 82+
- Entry: `assets/xposed_init`
- Output: `ds-inject-legacy.apk`
- Public asset: `deekseep-test-legacy-v1.7.apk`

It contains Compose and host message-menu experiments, local data tools,
sidebar multi-select, and the device-validated legacy implementation of expert
image relay, parallel multi-image description, and image-fragment restoration.

```bash
cd module-inject-legacy
bash build.sh
```

This is a test build. Do not enable it together with another Deekseep variant.
See [Expert Image Relay](../docs/EXPERT_IMAGE_RELAY.md) and
[Build Variants](../docs/VARIANTS.md).
