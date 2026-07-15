# Build Variants

Deekseep is maintained as five standalone Android module projects. The variants
exist because modern libxposed modules and traditional Xposed modules use
different runtime entry APIs and packaging metadata.

## Selection Guide

Use `module/` unless a specific compatibility or diagnostic need requires
another build.

| Directory | Public asset | Channel | Framework target | Package ID |
|---|---|---|---|---|
| `module/` | `deekseep-stable-api102-v1.7.apk` | Stable | Current LSPosed with libxposed API 102 | `com.dsmod.probe` |
| `module-inject/` | `deekseep-test-api102-v1.7.apk` | Test | Current LSPosed with libxposed API 102 | `com.dsmod.inject` |
| `module-legacy/` | `deekseep-stable-legacy-v1.7.apk` | Stable compatibility | FPA or older LSPosed | `com.dsmod.probe` |
| `module-inject-legacy/` | `deekseep-test-legacy-v1.7.apk` | Test compatibility | FPA or older LSPosed | `com.dsmod.inject` |
| `module-mtest/` | `deekseep-api102-load-probe-v0.1.apk` | Diagnostic | Current LSPosed with libxposed API 102 | `com.dsmod.mtest` |

## Feature Matrix

"Experimental" means that the feature is present but depends on build-specific
obfuscated symbols, server behavior, or an injection path that is less stable
than the normal native settings overlay.

| Capability | Stable API 102 | Test API 102 | Stable legacy | Test legacy |
|---|---:|---:|---:|---:|
| Native settings entry | Yes | Yes | Yes | Yes |
| Prompt file import and injection | Yes | Yes | Yes | Yes |
| Client response-replacement preservation | Yes | Yes | Yes | Yes |
| First-run risk disclosure | Yes | Yes | Yes | Yes |
| Local conversation editor | Yes | Yes | Yes | Yes |
| Add reasoning to a response with no reasoning fragment | Yes | No | No | No |
| Automatic malformed-reasoning migration | Yes | No | No | No |
| Markdown conversation export | Yes | Yes | No | Yes |
| Global local-chat search | Yes | Yes | No | Yes |
| Conversation statistics | Yes | Yes | No | Yes |
| Manual and automatic database backup | Yes | Yes | No | Yes |
| Sidebar multi-select and batch delete | Yes | No | No | Yes |
| Expert feature-flag unlock | Experimental | Experimental | No | Experimental |
| Expert image-to-vision relay | Experimental | No | No | Experimental |
| Parallel multi-image description | Experimental | No | No | Experimental |
| Relay image metadata persistence | Experimental | No | No | Experimental |
| Compose settings-row injection | No | Experimental | No | Experimental |
| Long-press host message edit item | No | Experimental | No | Experimental |
| Opt-in response diagnostics | Yes | Yes | Yes | Yes |

The minimal load probe contains none of the end-user features. It only hooks
`Activity.onResume`, reports that the modern module loaded, and attempts to write
a diagnostic marker.

## Modern Interface

The modern projects:

- extend `io.github.libxposed.api.XposedModule`;
- receive packages through `onPackageLoaded`;
- declare entry metadata in `META-INF/xposed/`;
- use libxposed API 102 as a compile-only dependency;
- require a framework build that implements the modern API.

## Traditional Interface

The legacy projects:

- implement `IXposedHookLoadPackage`;
- receive packages through `handleLoadPackage`;
- declare the entry in `assets/xposed_init` and manifest metadata;
- compile against small `de.robv.android.xposed` stubs that are not packaged
  into the final DEX;
- target FPA and older LSPosed environments.

## Signature and Coexistence Rules

Stable modern and stable legacy share `com.dsmod.probe`. Test modern and test
legacy share `com.dsmod.inject`. Each interface track uses a separate local
signing key, so Android will reject an in-place cross-interface update. Uninstall
the installed package before switching between modern and legacy.

Stable and test packages have different IDs and can be installed together, but
they must not both be enabled for `com.deepseek.chat`. Duplicate hooks can
rewrite the same request or database row twice and are unsupported.

Release builds use local development keys. Keys are excluded from Git and CI
generates temporary keys. A build from another machine may therefore require an
uninstall before installation.

## Why Feature Sets Differ

The projects are parallel compatibility tracks, not generated flavors of one
Gradle module. Some experiments were first proven on FPA, while later stable
work moved to the modern API 102 project. Code is only ported when the target
hook interface and affected feature have been verified; undocumented bulk
copying between tracks would create false compatibility claims.
