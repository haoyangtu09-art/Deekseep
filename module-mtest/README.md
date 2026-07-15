# API 102 Load Probe

This minimal module diagnoses whether the framework loads modern libxposed API
102 modules for `com.deepseek.chat`.

- Package: `com.dsmod.mtest`
- Interface: libxposed API 102
- Output: `ds-mtest.apk`
- Public asset: `deekseep-api102-load-probe-v0.1.apk`

It hooks `Activity.onResume`, reports successful loading, and attempts to write
a shared-storage marker. It does not include prompt injection, response hooks,
the editor, backup, or expert features.

```bash
cd module-mtest
bash build.sh
```

Disable other Deekseep variants while using the probe.
