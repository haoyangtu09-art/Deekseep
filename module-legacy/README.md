# Stable Traditional-Xposed Build

This project produces `deekseep-stable-legacy-v1.7.1.apk` for traditional
Xposed API 82+ environments.

The 1.7.1 legacy APK does not maintain a second feature fork. `build.sh`
compiles the canonical sources under `../module/src/com/dsmod/probe`, generates
a traditional `handleLoadPackage` entry from canonical `Main.java`, and bridges
its around-hook contract through `compat/LegacyXposedModule.java`.

`src/de/robv/android/xposed` contains compile-only signatures supplied by the
real framework at runtime. The historical files under `src/com/dsmod/probe`
are excluded from compilation and retained only for repository history.

```bash
cd module-legacy
bash build.sh
bash test-adapter-regression.sh
```

The unrenamed output is `ds-probe-legacy.apk`. Use `scripts/build-all.sh` from
the repository root to produce the signed 1.7.1 release filenames, run the
canonical regressions and verify both stable APK layouts.
