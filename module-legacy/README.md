# Stable Legacy Compatibility

This is the traditional Xposed compatibility form of the stable Deekseep
channel, intended for FPA and older framework environments.

- Package: `com.dsmod.probe`
- Interface: traditional Xposed API 82+
- Entry: `assets/xposed_init`
- Output: `ds-probe-legacy.apk`
- Public asset: `deekseep-stable-legacy-v1.7.apk`

It contains the stable core prompt, response-preservation, settings, and local
editor paths. It is a compatibility subset and does not contain every feature
from the modern stable project.

```bash
cd module-legacy
bash build.sh
```

The modern stable APK uses the same package ID with a different signature.
Uninstall one before switching to the other. See
[Build Variants](../docs/VARIANTS.md) and
[Installation](../docs/INSTALLATION.md).
