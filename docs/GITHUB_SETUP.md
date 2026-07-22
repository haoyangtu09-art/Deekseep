# GitHub Project Setup

This document lists repository-page changes that the owner can make manually after reviewing the documentation refactor. None of these remote settings are changed by the documentation work itself.

## Confirmed repository facts

- Current remote repository: `haoyangtu09-art/Deekseep`
- Current public URL: <https://github.com/haoyangtu09-art/Deekseep>
- Primary repository language reported by GitHub: Java
- Other reported repository languages: Shell and Python
- License: MIT
- Issues: enabled
- Discussions: disabled at the time of this audit
- Projects: enabled
- Wiki: enabled
- Current topics: `android`, `deepseek`, `libxposed`, `lsposed`, `termux`, `xposed`
- Latest stable Release at the time of this audit: `v1.7.2`

## 1. Repository name

Recommended repository name:

```text
Deekseep-LSPosed
```

This is only a recommendation. Renaming the GitHub repository does not require and must not be used to change the module package name, `applicationId`, internal namespace, signing configuration, class names, or published APK filenames.

To rename manually, open **Repository → Settings → General → Repository name**. Review external links and automation before confirming.

## 2. Repository description

Recommended English description:

```text
Deekseep LSPosed is an independent LSPosed/Xposed module adding account, chat, image, interface, and local API tools to the DeepSeek Android app.
```

Chinese alternative:

```text
Deekseep LSPosed：面向 DeepSeek Android App 的独立 LSPosed/Xposed 模块，提供账号、聊天、图片、界面和本地 API 工具。
```

Both descriptions are factual, avoid claiming official affiliation, and fit within GitHub's repository-description limit.

## 3. Topics

Recommended topics based on the checked source tree and GitHub language report:

```text
deekseep
deepseek
lsposed
libxposed
xposed
android
android-module
xposed-module
root
java
termux
```

Kotlin is not included because the tracked module implementation is Java and the repository has no tracked Gradle/Kotlin source configuration.

## 4. Social preview

The repository now contains:

```text
assets/social-preview.svg
```

The source canvas is 1280 × 640. In GitHub, open **Settings → General → Social preview → Edit** and upload the image. If GitHub rejects SVG, export it to a 1280 × 640 PNG locally and upload that PNG; keep the SVG as the editable source.

Do not add download counts, Star counts, user counts, unsupported compatibility claims, or an unlicensed DeepSeek logo to the preview.

## 5. GitHub Features settings

Review **Settings → General → Features** rather than enabling everything automatically:

- **Issues:** currently enabled and useful for the new structured forms.
- **Discussions:** currently disabled. Enable only if there is capacity to moderate support and community conversations; no README link assumes it is available.
- **Projects:** currently enabled. Keep it enabled only if it will be used for public roadmap or issue tracking.
- **Wiki:** currently enabled. Decide whether it adds value beyond the maintained `docs/` directory.

## 6. Releases

For future Releases:

- use consistent semantic version tags and clear Release titles;
- state the exact DeepSeek channel, `versionName`, and `versionCode`;
- identify one recommended stable APK for ordinary users;
- label Google Play and Legacy packages explicitly;
- publish and link checksums;
- keep test and diagnostic APKs out of the default stable download path;
- do not describe a historical feature as newly added in a later version;
- keep prerelease, test, and stable terminology consistent across the filename, Release title, and notes.

No Release, tag, APK, or existing Release note is changed by this setup guide.

## 7. LSPosed module repository preparation

Before proposing Deekseep LSPosed to an LSPosed module repository, confirm:

- a stable GitHub Release exists and its recommended APK downloads correctly;
- the module package ID remains fixed and matches the submitted metadata;
- README.md and README_CN.md state exact compatibility and installation steps;
- the scope is accurately documented as `com.deepseek.chat`;
- the supported DeepSeek channels, versions, and version codes are explicit;
- the module icon and listing artwork are original or otherwise legally usable;
- checksums and APK filenames are stable and unambiguous;
- privacy, logging, account, database, and experimental-feature risks are disclosed;
- the submitted APK has been tested in the claimed LSPosed interface and target App build.

This checklist does not mean the project has already been submitted or accepted.

## Optional GitHub CLI commands

The authenticated owner and current repository were confirmed during the audit. Review every command before running it; these examples are intentionally not executed by the documentation task.

```bash
gh auth status
gh repo view haoyangtu09-art/Deekseep
```

Optional repository rename:

```bash
gh repo rename Deekseep-LSPosed --repo haoyangtu09-art/Deekseep
```

Optional description update:

```bash
gh repo edit haoyangtu09-art/Deekseep --description "Deekseep LSPosed is an independent LSPosed/Xposed module adding account, chat, image, interface, and local API tools to the DeepSeek Android app."
```

Optional topic update:

```bash
gh repo edit haoyangtu09-art/Deekseep --add-topic deekseep --add-topic deepseek --add-topic lsposed --add-topic libxposed --add-topic xposed --add-topic android --add-topic android-module --add-topic xposed-module --add-topic root --add-topic java --add-topic termux
```

Run the rename command only after deciding to rename the remote repository. GitHub may redirect old links, but external package indexes, badges, automation, and documentation should still be checked afterward.
