# OmniRoute DeepSeek Tool Bridge Source Snapshot

This directory preserves the upstream TypeScript used as the source of the Android/Java tool
translation adapter. The files are intentionally kept separate from module source so changes can
be compared with upstream rather than silently diverging into a private protocol.

- Project: OmniRoute
- Repository: https://github.com/diegosouzapw/OmniRoute
- Commit: `dffff5d656c169e41c4862cb38affbd9992f24a5`
- License: MIT; see `LICENSE.txt`
- Preserved sources: `webTools.ts`, `deepseekWebTools.ts`
- Android adapter: `module/src/com/dsmod/probe/OmniRouteToolBridge.java`

The adapter retains the upstream canonical `<tool>{json}</tool>` contract, loose JSON recovery,
request-scoped fuzzy name matching, stack-based DeepSeek tag parsing, parameter/schema fallback,
and tool-markup isolation. Android-specific request validation and Anthropic/OpenAI wire encoding
remain in the module gateway.
