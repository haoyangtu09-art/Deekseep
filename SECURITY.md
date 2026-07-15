# Security Policy

## Supported Code

Security fixes are applied to the current source and latest public release. The
project cannot guarantee compatibility or security support for older target app
versions, old module APKs, private forks, or modified signing builds.

## Reporting a Vulnerability

Do not open a public issue for a vulnerability that could expose conversation
data, arbitrary files, credentials, signed URLs, or unsafe cross-process access.

Use GitHub's private security advisory form:

https://github.com/haoyangtu09-art/Deekseep/security/advisories/new

Include:

- affected Deekseep variant and commit;
- Android, framework, and DeepSeek versions;
- a minimal reproduction;
- impact and required attacker access;
- a redacted stack trace or proof;
- any proposed mitigation.

Do not attach a real chat database, full response log, imported prompt, target
APK, or signing key.

## Security Model

Deekseep intentionally runs inside a privileged target process and accesses
private application files. A user who enables the module grants it the same
effective data visibility as the hooked process. The primary security goals are
therefore:

- no unintended export of private target data;
- no path traversal from session-derived filenames;
- no credentials or sensitive runtime data in the repository;
- opt-in diagnostics;
- narrow and fail-open database transforms;
- explicit user disclosure before use.

The project does not claim to protect a rooted or compromised device from a
malicious local administrator or injection framework.
