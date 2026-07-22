# DeepSeek Android 本地 API 转译层：实现状态与后续计划

> 状态：两个稳定接口包 **1.7.1** 共用实现，已完成 OpenAI 与 Anthropic 双格式及协议、SDK、
> Codex、Claude Code 客户端契约和故障恢复测试。
> 日常配置与示例见 [DeepSeek 本地 API 使用说明](LOCAL_DEEPSEEK_API.md)。本文记录实现取舍、
> 已完成范围、验证证据和仍未实现的后续阶段，供 GitHub 1.7.1 移植使用。

## 1. 当前目标

把已登录 DeepSeek Android 应用的原生 transport、PoW、会话创建和流式事件转换成只监听
本机和可信私有局域网的 OpenAI / Anthropic 兼容 API，供 Termux、OpenAI SDK、Codex、Claude Code
和其他本机 Agent 调用。

本轮完成：

- `GET /healthz`、`GET /v1/models`、`GET /v1/models/{model}`；
- `POST /v1/chat/completions` 非流式与 SSE；
- `POST /v1/responses` 非流式与 SSE；
- `POST /v1/messages` 与 `/v1/messages/count_tokens`，包含 Anthropic SSE 与客户端工具循环；
- Bearer / `X-API-Key` 认证、随机或自定义 Key；
- function/custom/shell/apply_patch/namespace 工具与工具结果回传；
- `previous_response_id` 进程内延续；
- DeepSeek 深度思考和原生搜索参数；
- 限流退避、会话复用、自愈、总截止时间、请求队列和私有轮转日志；
- 全部生成共享的公平原生 permit、Agent/Task 优先级、元数据外部 pacing，以及按哈希客户端会话
  隔离的隐藏分支；
- OpenAI 注释心跳、Anthropic `ping` + 非终态累计 usage 活动事件、客户端主动断开单独计数；
- Chat `response_format`、Responses `text.format` 的 JSON object/schema 约束与回显；
- 首次后台/电池不限制硬校验、独立手绘控制页、当前格式弹窗选择与实时状态统计；
- 首次进入需等待 5 秒确认的实验性功能页，集中承载专家图片中继、本地 API 和独立帮助；
- 用户操作启动的模块私有 `specialUse` 前台保活、5 秒令牌心跳、局部唤醒锁与 90 秒失联自停；
- 支持 `/think` 和命令行 prompt 的无依赖微型 Agent，以及带 Android `/tmp` 映射的隔离
  Claude Code 启动器。

本轮不实现：局域网监听、API 图片输入、把 HTTP/宿主 transport 搬到独立进程、多账号请求路由。

## 2. 实际架构

```text
Termux / OpenAI SDK / Codex / Claude Code
        │ HTTP + JSON / SSE
        │ 本机/私有 LAN + Gateway API Key
        ▼
DeepSeek 目标进程中的 LocalApiGateway
        │ 协议解析、认证、状态、工具桥、日志
        │                         ▲
        │       5 秒显式心跳      │
        │              模块私有 specialUse 前台 Service
        ▼
Main.executeLocalApiCompletion
        │ 单一公平原生 permit、作用域会话池、串行 PoW、重试、Agent 优先级/限流退避
        ▼
DeepSeek 原生 transport / Flow / 当前登录账号
        ▼
DeepSeek 服务端
```

HTTP 网关继续运行在目标进程，这样可以复用已经初始化的混淆对象，不需要把 Flow、PoW 和宿主
请求对象跨 Binder 重新建模。真机日志确认 DeepSeek 账号不能可靠地并行运行两个 native generation；
r19 因此让所有生成共享一个公平 permit。工具型主/Task 请求在入队前登记优先级，无工具标题/摘要
先在 permit 外 pacing；隐藏会话仍按 workload 与哈希客户端会话分开，PoW 和起始间隔全局串行。模块前台
Service 不承载 HTTP，只通过显式空操作心跳防止
目标进程在后台被 Cached Apps Freezer 冻结，并在宿主可重新启动后触发已保存网关恢复。用户强制
停止 DeepSeek 后端口仍会消失；下一次用户启动 DeepSeek 时会自动恢复监听。

网关不读取或导出 Cookie、登录 token、设备指纹和 PoW 中间值。HTTP 调用方只持有独立的本机
Gateway Key。

## 3. HTTP 与协议核心

`LocalApiGateway` 提供一个小型 HTTP/1.1 server：

- 默认端口 8765，占用时尝试后续 15 个端口；
- 绑定 `0.0.0.0` 以提供本机和私有 LAN 地址，但所有业务路由强制 Gateway Key；
- 不提供 TLS、公网防护或端口转发支持，只允许可信网络；
- 4 个常驻、最多 8 个 HTTP worker，队列 16；
- 32 KiB 请求头、1 MiB 请求体；
- 同时支持 `Content-Length` 与 chunked request body；
- 普通 JSON 响应带正确 Content-Length；
- SSE 每个 data/event frame 独立 flush；OpenAI 静默阶段发送注释心跳，Anthropic 发送标准
  `ping` 和非终态累计 usage `message_delta`；Chat 以 `[DONE]` 结束，Anthropic 以
  `message_stop` 结束；
- OpenAI 错误对象包含 `message/type/param/code`；Anthropic 错误对象使用 `type=error` 和
  标准 authentication/rate_limit/api/invalid_request 类型。

HTTP worker 与原生生成通道分离，所以生成排队时 `/healthz` 和轻量配置请求仍能响应。

## 4. Chat Completions

已实现：

- `model`、非空 `messages`、`stream`；
- `max_tokens`、`max_completion_tokens` 的正数校验；
- `n=1`；
- `stream_options.include_usage`；
- system/user/assistant/tool 上下文转换；
- `tools`、旧版 `functions`、`tool_choice`、旧版 `function_call`；
- 非流式 `tool_calls` 与 `finish_reason=tool_calls`；
- SSE role/text/reasoning/tool argument/usage delta 与 `[DONE]`；
- 后续 `role=tool` + `tool_call_id` 结果回传。

一个请求携带的完整 messages 会重建上下文。API 专用原生会话只用于稳定 transport，不把先前
请求的隐藏会话历史偷偷拼入新请求，因此并发调用不会相互串线。

## 5. Responses

已实现：

- `model`、string/array `input`、`instructions`、`stream`；
- `max_output_tokens`；
- message 和各种 tool call/output item；
- `previous_response_id`；
- 非流式 Response 对象与 usage；
- SSE 的 created/in_progress、output item、content part、text/reasoning/tool delta、done、
  completed/failed 生命周期；
- 单调递增 `sequence_number`；
- function/custom/shell/local_shell/apply_patch/namespace 工具。

延续状态最多 128 条，TTL 六小时，保存在目标进程内。状态只保存生成下一轮所需的 transcript 和
tool plan，不写账号凭证。进程重启后旧 ID 明确返回 `previous_response_not_found`；发送完整历史的
Codex 不依赖这一缓存。请求参数不经 previous ID 隐式继承；工具结果回传后，只有客户端再次发送
`tools` 才允许规划下一项工具，避免先前的 required/forced call 被无限重复。

## 6. Anthropic Messages

Anthropic 模式实际开放 `POST /v1/messages` 和 `/v1/messages/count_tokens`，根 base URL 不带
`/v1`。翻译层处理 string/array system、user/assistant content block、thinking、客户端 tool schema、
tool choice、`tool_use` 与 `tool_result`，再复用同一个 `OpenAiToolBridge` 工具信封和完成调用去重。

非流式响应返回标准 Message content block 与 `end_turn/tool_use/max_tokens` stop reason。SSE 顺序为
`message_start -> content_block_* -> message_delta -> message_stop`，文本使用 `text_delta`，工具参数
使用 `input_json_delta`。DeepSeek 思考片段使用本地 SHA-256 签名的 thinking block；该签名只供本地
回放，不冒充 Anthropic 云端签名。Claude Code 的 Bearer `ANTHROPIC_AUTH_TOKEN` 与 SDK 的
`x-api-key` 均由同一个恒定时间比较认证。

`message_start` 不是在 HTTP 请求刚到达时写出。请求先经过队列、PoW 并进入原生 Flow 收集，随后
`DeltaSink.onUpstreamStarted` 才开启 message/thinking 生命周期；首个同步 delta 也会通过幂等的
lazy start 保证事件顺序。上游启动失败时不会先给客户端制造一个假的 thinking 状态。

Claude Code 会在 UI/stall detector 之前过滤纯 `ping`。因此每个十秒心跳还携带 stop reason 为 null
的合法累计 usage `message_delta`；它不结束 content block，却能保持底部动画和长思考 token 计数
活动。终态 usage 取估算值与活动期间最高值的较大者，保证计数不倒退。

## 7. Agent 工具桥

DeepSeek 当前原生 transport 没有直接暴露 OpenAI 工具结构，所以 `OpenAiToolBridge` 使用严格的
请求专属 OmniRoute `<tool>` 协议：

1. 把工具名称、namespace、描述、JSON Schema 和 custom grammar 加入模型提示；
2. 用服务无关的 `[TOOL USE INSTRUCTIONS]` 要求只输出规范 `<tool>` 块；旧私有 JSON 信封仅解析兼容；
3. 有文件修改工具时，明确要求写入/编辑任务本轮调用工具，不准用代码块代替落盘；
4. 容忍 Markdown fence、已观察到的键前缀损坏和 custom tool 叙述式输出；
5. 校验名称、forced choice、JSON 参数、shell command、patch input；
6. 格式像工具调用但解析失败时，在同一总截止时间内只修复一次；
7. 返回标准 Chat `tool_calls` 或 Responses tool item；
8. 客户端执行后，把 output 和 call id 无损送回下一轮。

Agent 完整历史还会建立 `call_id -> 类型/namespace/名称/稳定排序参数` 映射。output 没有明确失败
时，把该签名记为已完成；有副作用的同一调用不会再次向客户端发出，而是要求模型消费现有结果。
畸形但疑似重复的信封走同一路恢复。不同参数、失败结果以及 Read/Glob/Grep 等只读验证调用不进入
副作用抑制，允许 Edit 后再次 Read。

有副作用或有顺序依赖的 custom/shell/apply_patch 只返回一个调用；普通 function 可以按
`parallel_tool_calls` 并行。网关本身不执行工具，也不改变 Codex 的 sandbox/approval 决策。

Codex 当前请求中的 namespace 嵌套函数会在内部展平，但输出保留原 namespace。无法由本地模型
执行的 hosted 工具类型不会让整份请求失败；允许外部访问的 web search 映射到 DeepSeek 原生搜索。

## 8. 原生会话与稳定性

早期每次请求都创建/删除临时会话，容易触发 `session_create_failed` 和 429。当前方案为每个
native model 按 workload 与哈希客户端会话作用域复用隐藏 API 会话：

- 映射原子保存到 `deekseep_local_api_sessions.json`；
- 云目录、侧栏和编辑器过滤这些会话；
- 每次 API 请求仍提交自己的完整 prompt 与空 parent，避免上下文泄漏；
- Anthropic `metadata.user_id` 的 session UUID 与首条用户消息共同做 SHA-256 截断哈希，原文不
  持久化；正常 `/clear`、`/new` 旋转 UUID，旧客户端即使复用 UUID，新 transcript 也选择新分支；
- 关闭服务时走 DeepSeek 原生删除路径清理；
- 服务端报告 `invalid chat session id` 时删除失效映射、创建新会话，并仅在尚未输出 delta 时重试。

稳定性策略：

- 所有生成共享一个公平 `Semaphore(1)`，最长排队 90 秒；工具型主/Task 请求登记 waiters，
  无工具辅助元数据在占用 permit 前等待并按 4 秒间隔 pacing；
- 请求起点最少间隔 2.5 秒；
- 整个请求（包括队列、PoW、重试、SSE、工具格式修复）共享 170 秒 deadline；
- transient 重试等待为 0/2.5/6/12 秒；
- 原生 429 冷却为连续 15/25/40 秒，成功后重置 streak；
- PoW 全局串行签发；单次超时重试只复用其有界任务，避免同一 token 被并发消费；
- 一旦 SSE 已输出正文便不重放请求；客户端断开通过 DeltaSink 取消上游收集；
- SSE 静默时每 5 秒发送协议对应的活动事件；本地写端断开作为 cancelled 单独统计，不再误算成
  上游失败；
- 首次进入控制页强制校验 Doze 豁免和后台限制，未通过时 enabled marker 也不能启动网关；
- 通过无动画 deep-link Activity 将“启动前台服务”保持为用户可见操作；模块 Service 使用
  `foregroundServiceType=specialUse`、局部唤醒锁和低优先级通知；
- Service 每 5 秒向宿主导出的 receiver 发送带控制令牌的有序显式广播，注入 hook 在原 receiver
  逻辑前消费它并回传 enabled/running；90 秒无确认或 API 已关闭时 Service 自动停止；
- 该方案没有关闭系统全局 Cached Apps Freezer，也没有更改或保活其他应用。

## 9. 模型与参数映射

`deepseek-chat` 和 `deepseek-reasoner` 映射原生 `default`，`deepseek-expert`、
`deepseek-vision` 映射同名原生能力。`gpt-*`、`o1*`、codex、`claude-*`、sonnet、opus 和
haiku 名称是默认模型兼容别名；
其中 `gpt-5.4` 会出现在 `/v1/models`，用于让 Codex 加载完整内建工具元数据，实际生成仍是
DeepSeek。

以下参数任一为真即可开启原生 `thinking_enabled`：`deep_think`、`thinking_enabled`、
`enable_thinking`、boolean/object `thinking`、非 none `reasoning_effort`、Responses
`reasoning.effort`。`deepseek-reasoner` 自动开启。`search=true` 或可外部访问的 web search
tool 开启原生搜索。

## 10. 控制面与诊断

Deekseep 主页面只显示“实验性功能”入口；首次进入弹出不可绕过的风险说明，确认键等待 5 秒，
退出则不进入。实验页集中显示专家模式图片中继、本地 API 和独立实验帮助。本地 API 控制页提供：

- 首次后台运行手绘引导、系统电池页返回自动复检和页面内实时校验；
- 前台保活运行状态及最近确认结果；
- 启停开关与“格式 / 当前格式”单行入口，点击当前格式后在弹窗中选择 OpenAI 或 Anthropic；
- 当前 URL、Key、后端状态；
- 一键复制 URL / Key；
- 保存自定义 Key、生成随机 Key；
- Chat/Responses/Messages、Agent/Codex/Claude Code 和思考参数说明；
- 每秒更新的收到/成功/失败/客户端中断/工具调用数量、HTTP worker、队列、最近路由和失败原因。

日志位于 `files/deekseep_api.log`，超过 2 MiB 轮转为 `.1`。状态 JSON 和连接信息也写入私有目录。
它们包含完整 API Key 或敏感诊断，不能未经脱敏公开。

## 11. 2026-07-19 设备验收结果

在当前 DeepSeek 2.2.2 / Android 设备上完成：

- Chat 非流式与 SSE 文本、reasoning、usage、`[DONE]`；
- Responses 非流式与 SSE 全事件 JSON 解析和唯一 completed；
- Chat function 非流式/SSE、tool result 续写；
- Responses function/custom/shell/apply_patch/namespace 和 output 续写；
- `previous_response_id` 工具循环；
- chunked HTTP 请求；
- 深度思考关闭/开启的原生请求字段与输出；
- 401、畸形 JSON、缺少/未知模型、未知 previous ID 的错误契约；
- 3 个同时到达的请求全部成功，负载中 health 仍即时返回；
- 12 个无客户端间隔的连续请求 12/12 成功，第 8 个真实触发 429 后自动等待并恢复；
- 冷启动后请求成功；主动注入无效隐藏 session ID 后出现
  `invalid_api_session -> SESSION_RECREATED -> retry success`；
- OpenAI JavaScript SDK 6.48.0：Chat/Responses 非流式与流式四项全部成功；
- Codex CLI 0.144.5：Responses provider、`exec_command` 工具结果回传和最终回答成功；
- 独立 `CODEX_HOME` 的真实 CLI 回归由受控本机 Responses mock 发出唯一的 custom
  `apply_patch`，验证文件创建、`custom_tool_call_output` 回传、`phase=final_answer` 与
  `end_turn`，不读取、覆盖或登出用户的 Codex 登录；
- Codex `gpt-5.4` 真实依赖链只执行一次 `pwd`，再把其真实路径用于第二条 `basename` 命令，
  两轮工具结果均进入下一轮且没有 API 重连；
- 人为诱导模型重复一次成功 `exec_command` 时，日志记录 `TOOL_DUPLICATE_SUPPRESSED`，Codex
  端只执行一次；
- Codex `gpt-5.4` custom apply_patch 实际被解析并执行；普通策略下是否允许写文件仍由 Codex
  approval/sandbox 决定。
- DeepSeek 退到 Termux 后跨越原先约 60 秒冻结窗口持续检查，`isFrozen=false`，`/healthz`
  始终可响应；当前方案未修改系统全局 freezer；
- Anthropic SSE `tool_use -> tool_result` 真机闭环成功；OpenAI Chat SSE `tool_calls -> tool`
  与 Responses SSE `function_call -> previous_response_id -> function_call_output` 均闭环成功；
- 隔离 Claude Code 2.1.0 经 Messages SSE 调用一次 Bash `pwd`，真实工具结果回传后两轮结束并
  得到最终回答；模型仅改写 `description/timeout` 的重复命令已由等价签名拦截；
  当前一次完整验收运行的网关统计为 18 次收到、18 次成功、0 次失败。
- 1.7-r16 的真实交互会话按 Write → Read → Edit → Read 连续完成四个客户端工具回合；最终文件
  精确为 `ALPHA\nBETA`，Edit 后的同参数 Read 未被误判为副作用重复，转录中没有工具信封泄漏。
- Claude Code `--output-format stream-json --include-partial-messages` 验证 Bash 的标准
  `tool_use`、两段 `input_json_delta`、真实 `tool_result` 和后续最终回答；磁盘结果精确为
  `BASH_TOOL_OK`。
- r19 真机先复现并定位独立 native lane 之间的 `parallel_chat_limit`，改为单一公平 permit 后，
  最终 Claude 会话与网关日志均无 stream stall、并发限制或 timeout；验收状态为收到 52、成功 51、
  失败 0、客户端主动取消 1，并发队列回到 0。
- 长 `/init` 中 Anthropic token 显示从约 2.2k 持续增长到 5.7k，十秒活动更新期间没有退回纯
  `think` 标签，底部 spinner 字形和动词持续变化。DeepSeek 退到后台后任务仍完成 Glob、Task/Explore、
  Read、Bash、Write，并生成真实 `CLAUDE.md` 与最终总结。
- 先构造“正文为空、仅 reasoning 承诺下一步 Read”的真实失败，再验证 r19 自动转为结构化 Read
  `tool_use/tool_result` 并返回磁盘精确内容；`/init` 中途的“我将分析”同样触发一次最小工具修复，
  不再把空承诺当完成。
- `/compact` 产生 `isCompactSummary=true`；`/clear` 与 `/new` 后询问旧标记均返回 `UNKNOWN`，私有
  session map 出现多个不同 `#s-<hash>` 且不保存原始 metadata。`@input.txt` 读取到精确测试值，
  输入行 `! printf ... > input-line-r19.txt` 实际执行并在磁盘得到精确内容。
- 当前隔离 Claude Code 2.1.0 手工打开或执行 `/help`、`/status`、`/context`、`/cost`、`/todos`、
  `/stats`、`/model`、`/permissions`、`/config`、`/memory`、`/mcp`、`/hooks`、`/agents`、`/skills`、
  `/resume` 十五项内部命令；另验证 `/compact`、`/clear`、`/new`、`/init`、`/vim` 和 `/exit`。

纯 JVM 回归覆盖工具信封、forced choice、namespace、hosted tool、custom grammar、损坏输出恢复、
Chat/Responses tool SSE、previous ID 工具循环，以及 Anthropic 非流式、文本 SSE、工具 SSE、
tool_result 续写、thinking block 和 count_tokens。

## 12. 已知边界

- 用户强制停止目标应用时监听会停止；模块前台 Service 只负责保活心跳，不承载 HTTP 或原生生成；
- API 输入当前是文本和工具 item，不包含 OpenAI 图片/data URL；
- usage 为近似值，部分 OpenAI sampling 参数只做兼容接收；
- 模型生成的工具选择和参数不是数学上可保证的，Agent 必须继续验证参数和权限；
- DeepSeek 服务端限流、账号权限、地区、网络和内容策略仍然生效；
- Anthropic thinking 使用本地签名，不可当作 Anthropic 云端签名跨服务复用。

## 13. 后续计划

按优先级：

1. 增加取消信号从 socket 到宿主 Flow 的显式双向确认；
2. 为 API 图片输入复用已完成的私有图片主副本和上传凭证链；
3. 持久化 Responses 状态并增加幂等请求键；
4. 增加脱敏诊断包和可配置端口，但继续禁止 `0.0.0.0`；
5. 扩大 Anthropic/Claude Code 工具回归语料和长时间 soak；
6. 只有出现必须跨进程承载 HTTP 的明确需求时，再设计 UID 校验 Binder/PFD；不得为了保活重写
   已验证的宿主 Flow/PoW 调用链。

任何后续迁移都必须保留当前的受认证本机/可信 LAN 边界、独立 Gateway Key、按 workload/哈希客户端会话隔离的
隐藏分支、单一公平原生 permit、Agent 优先级和“已输出内容后绝不自动重放”原则。
