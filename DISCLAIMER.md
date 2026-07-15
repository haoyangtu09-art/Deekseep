# Disclaimer

## Unofficial Project

Deekseep is an independent, unofficial project. It is not affiliated with,
endorsed by, sponsored by, or supported by DeepSeek, High-Flyer, LSPosed,
libxposed, the Xposed project, or any related company or maintainer.

All product names, logos, service names, and trademarks belong to their
respective owners. Their appearance in this repository is solely for technical
identification and compatibility documentation.

## Runtime Modification Risk

This software hooks and changes a third-party Android application at runtime.
The target application's implementation is obfuscated and can change without
notice. Installing or enabling this module can cause crashes, boot loops in the
target process, message corruption, lost conversations, broken uploads,
unexpected network requests, or other malfunction.

The chat editor and backup tools access DeepSeek's local databases directly.
Always create an independent backup before editing, deleting, migrating, or
restoring conversation data.

## Account and Service Risk

Using a runtime modification may violate the target service's terms, acceptable
use rules, account policies, or regional requirements. It may cause an account
warning, suspension, restriction, or permanent loss. You are solely responsible
for checking the rules that apply to you and for every action performed with the
software.

No feature in this project guarantees access to server-controlled capabilities.
The project cannot recover data or output that the server never sent.

## Safety and Lawful Use

Some optional hooks preserve text that the client would otherwise replace after
streaming, and some experiments alter local expert-mode feature flags. These
features must not be used to facilitate unlawful conduct, abuse another person
or service, evade access controls, expose private information, or create harmful
content. You are responsible for lawful and responsible use.

## Privacy

The module executes inside the target app and may process prompts, responses,
conversation databases, file metadata, and diagnostic events. Diagnostic logs
can contain sensitive conversation content. Keep diagnostics disabled unless
needed, store backups securely, and redact data before sharing an issue report.

This public repository intentionally excludes target APKs, decompiled target
code, real user databases, device logs, imported prompts, credentials, and local
signing keys.

## No Warranty

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND. TO THE MAXIMUM
EXTENT PERMITTED BY LAW, THE AUTHORS AND CONTRIBUTORS ARE NOT LIABLE FOR ANY
CLAIM, DAMAGE, DATA LOSS, ACCOUNT ACTION, SECURITY INCIDENT, OR OTHER LIABILITY
ARISING FROM THE SOFTWARE OR ITS USE.

By installing, building, distributing, or using Deekseep, you confirm that you
understand and accept these risks.
