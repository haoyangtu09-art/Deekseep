# DeepSeek 本地 API 使用说明

两个稳定 1.7.1 构建都可以把当前已登录的 DeepSeek Android 原生发送链路转换为在本机和可信局域网
监听的 OpenAI 或 Anthropic 兼容 API。OpenAI 模式支持 Chat Completions 与
Responses；Anthropic 模式支持 Messages 与 token count。两种模式都支持普通响应、SSE、
深度思考和 Agent 工具循环，不会把 DeepSeek 的 Cookie、token 或 PoW 数据交给调用方。

> 这是非官方协议适配层。请求仍受当前账号权限、DeepSeek 服务端限流、网络和模型输出质量影响。
> API Key、状态文件和日志都应按账号凭证保护。

## 1. 启用与连接

1. 打开 DeepSeek 设置中的 **Deekseep**。
2. 点击 **实验性功能**。首次进入会显示独立风险说明；确认键倒计时 5 秒后才可点击，选择
   **退出**只会留在 Deekseep 主页面。确认状态按免责声明版本保存，之后不再重复弹出。
3. 在实验性功能页点击 **本地 API 服务**。首次进入会出现模块自绘的后台运行检查。
4. 在系统电池页面把 DeepSeek 设为“不限制/允许高耗电”，并允许后台活动。返回后模块自动
   检查 `isIgnoringBatteryOptimizations` 与 `isBackgroundRestricted`；没有同时通过时不会启动监听。
5. 点击“格式”右侧的当前值，在弹窗中选择 **OpenAI** 或 **Anthropic**，再打开
   **启用本地 API 服务**。模块会经过一个无动画、
   立即返回的用户操作桥启动自己的前台保活服务；通知“DeepSeek 本地 API 正在运行”存在期间，
   控制页的“前台保活”应显示正常。
6. 等待“原生后端”显示已就绪，用 **一键复制 URL** 和 **一键复制 API Key** 获取连接信息。

OpenAI 的默认 base URL 是 `http://127.0.0.1:8765/v1`；Anthropic 的默认 base URL 是
`http://127.0.0.1:8765`，不能额外附加 `/v1`。如果端口被占用，网关会依次尝试
`8766` 至 `8780`，所以客户端应以控制页显示的 URL 为准。网关绑定 `0.0.0.0`；同一 Wi-Fi/LAN
中的设备可使用控制页显示的 `http://设备私有IPv4:端口`。地址选择优先 Wi-Fi、热点和以太网，
忽略 VPN/tun 与蜂窝接口。彻底结束 DeepSeek 进程后监听也会停止，下一次启动时按开关恢复。

业务路由即使来自局域网也必须携带 API Key；只有 `/healthz`、`/health` 和 `/` 不要求认证。
此服务没有 TLS、IP 白名单或公网防护，API Key 等同当前 DeepSeek 账号的调用权限。只应在可信
局域网中使用，不要做路由器端口转发，也不要把 URL、Key 或连接信息文件发给他人。

电池“不限制”是启用前提，但在部分 Android/OEM 系统上并不足以阻止 Cached Apps Freezer。从 r16
起模块额外运行私有的 `specialUse` 前台服务，持有局部唤醒锁并每 5 秒向 DeepSeek 发送带控制令牌的
显式空操作心跳；宿主在接收心跳前由系统解冻，注入 hook 会消费心跳而不触发原分享逻辑。关闭 API
会同步停止保活；DeepSeek 连续 90 秒没有确认时保活也会自动退出。该机制不修改系统全局冻结策略，
不启动、停止或保活其他应用。

格式选择是实际路由门控：OpenAI 模式拒绝 Anthropic 路由，Anthropic 模式拒绝 OpenAI 路由，
避免客户端误把一种 SSE 事件当成另一种解析。切换不需要重启 DeepSeek。

API Key 可以在控制页保存为 8–256 位无空格 ASCII 字符，也可以生成新的随机 Key。支持两种
认证头：

```text
Authorization: Bearer <API_KEY>
X-API-Key: <API_KEY>
```

## 2. 支持的接口

| 接口 | 状态 |
| --- | --- |
| `GET /healthz` | 网关与原生后端状态，不要求认证 |
| `GET /v1/models` | 模型列表，要求认证 |
| `GET /v1/models/{model}` | 查询单个模型对象，未知模型返回 404 |
| `POST /v1/chat/completions` | 非流式、SSE、functions/tools、tool result |
| `POST /v1/responses` | 非流式、SSE、工具调用、`previous_response_id` |
| `POST /v1/messages` | Anthropic 非流式/SSE、tools、`tool_use` / `tool_result` |
| `POST /v1/messages/count_tokens` | Anthropic 输入 token 近似计数 |

`/healthz` 始终可用；表中 OpenAI 与 Anthropic 业务路由只在对应格式被选中时开放。

公开模型名包括：

- `deepseek-chat`：DeepSeek 默认模型；
- `deepseek-reasoner`：默认模型并自动开启深度思考；
- `deepseek-expert`：宿主 expert 模型；
- `deepseek-vision`：宿主 vision 模型；
- `gpt-5.4`：只用于让 Codex 加载完整内建工具目录的兼容别名，实际仍映射到
  `deepseek-chat`，并不冒充或调用 OpenAI 模型。

`deepseek-v3`、`deepseek-r1*`、`gpt-*`、`o1*`、名称中带 `codex`，以及 `claude-*`、
`sonnet*`、`opus*`、`haiku*` 的请求也会兼容映射到 DeepSeek 默认模型。响应中的 `model`
保留客户端请求的名称；这些名称只用于客户端兼容，不会调用同名云模型。

## 3. curl 示例

先把控制页复制的密钥放入当前 shell；不要把真实 Key 写入公开脚本或提交到 Git：

```bash
export DEEKSEEP_API_KEY='<从控制页复制>'
export DEEKSEEP_BASE_URL='http://127.0.0.1:8765/v1'
```

Chat Completions 非流式：

```bash
curl "$DEEKSEEP_BASE_URL/chat/completions" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $DEEKSEEP_API_KEY" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

Chat Completions SSE：

```bash
curl -N "$DEEKSEEP_BASE_URL/chat/completions" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $DEEKSEEP_API_KEY" \
  -d '{
    "model": "deepseek-chat",
    "stream": true,
    "stream_options": {"include_usage": true},
    "messages": [{"role": "user", "content": "逐步解释快速排序"}]
  }'
```

Responses 非流式：

```bash
curl "$DEEKSEEP_BASE_URL/responses" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $DEEKSEEP_API_KEY" \
  -d '{
    "model": "deepseek-chat",
    "input": "只回复 RESPONSE_OK"
  }'
```

Responses 流式只需增加 `"stream": true`。事件包括 `response.created`、
`response.in_progress`、输出 item/content 生命周期、文本或工具参数 delta，以及唯一的
`response.completed` 或 `response.failed` 终态。

Chat 的 `response_format` 与 Responses 的 `text.format` 支持 `text`、`json_object` 和
`json_schema`。本地模型没有原生结构化输出通道，因此网关会严格校验 schema 请求、把格式约束作为
请求级指令交给模型，并在 Responses 对象中回显规范化后的 `text.format`；调用方仍应校验最终 JSON。

Anthropic 格式先在控制页切换协议，并使用不带 `/v1` 的根地址：

```bash
export DEEKSEEP_ANTHROPIC_URL='http://127.0.0.1:8765'
curl -N "$DEEKSEEP_ANTHROPIC_URL/v1/messages" \
  -H 'Content-Type: application/json' \
  -H 'anthropic-version: 2023-06-01' \
  -H "x-api-key: $DEEKSEEP_API_KEY" \
  -d '{
    "model": "deepseek-chat",
    "max_tokens": 4096,
    "stream": true,
    "messages": [{"role": "user", "content": "只回复 ANTHROPIC_OK"}]
  }'
```

Anthropic SSE 按标准顺序返回 `message_start`、`content_block_start/delta/stop`、
`message_delta`、`message_stop`。工具参数使用 `input_json_delta`；流式错误使用 `event: error`，
不会错误地附加 OpenAI 的 `[DONE]`。网关在排队、限流冷却和 PoW 阶段不会先发假的 thinking；
只有原生 Flow 已进入收集、即请求已经交给模型后，才发送 `message_start` 并打开 thinking block。
每次累计 token usage 更新后，在正文尚未
开始时会关闭并重开 thinking block，使 UI 保持 thinking 状态。

## 4. OpenAI SDK

标准 SDK 只需替换 `baseURL` 和 `apiKey`。Node.js 示例：

```javascript
import OpenAI from "openai";

const client = new OpenAI({
  baseURL: process.env.DEEKSEEP_BASE_URL,
  apiKey: process.env.DEEKSEEP_API_KEY,
});

const response = await client.responses.create({
  model: "deepseek-chat",
  input: "只回复 SDK_OK",
});
console.log(response.output_text);

const stream = await client.chat.completions.create({
  model: "deepseek-chat",
  stream: true,
  messages: [{ role: "user", content: "流式回答" }],
});
for await (const chunk of stream) {
  process.stdout.write(chunk.choices[0]?.delta?.content ?? "");
}
```

请求支持 `Content-Length` 和 HTTP `Transfer-Encoding: chunked`，请求头上限 32 KiB，请求体
上限 1 MiB。

## 5. Codex 配置

当前 Codex 自定义 provider 使用 Responses wire API。在 `~/.codex/config.toml` 中加入：

```toml
model = "gpt-5.4"
model_provider = "deekseep"

[model_providers.deekseep]
name = "Deekseep"
base_url = "http://127.0.0.1:8765/v1"
env_key = "DEEKSEEP_API_KEY"
wire_api = "responses"
```

然后在启动 Codex 的 shell 中设置：

```bash
export DEEKSEEP_API_KEY='<从控制页复制>'
codex
```

如果控制页显示的端口不是 8765，应同步修改 `base_url`。`gpt-5.4` 只是 Codex 工具元数据兼容
别名；所有生成仍由 DeepSeek Android 当前账号完成。普通文本和基础 function 工具也可把
`model` 配为 `deepseek-chat`。

工具是否真正执行仍由 Codex 自己的工作区、sandbox 和 approval policy 决定。网关只负责在
OpenAI 结构与 DeepSeek 文本协议之间传递调用和结果，不会绕过 Codex 的文件或命令权限。

不需要退出当前 Codex 账号也能做兼容测试。仓库的测试会创建独立临时 `CODEX_HOME`，同时使用
`--ignore-user-config`、`--ignore-rules` 和 `--ephemeral`，因此不会读取或改写当前登录：

```bash
python scripts/test-codex-responses-compat.py
```

它驱动本机已安装的真实 Codex CLI，但服务端是只返回一个固定 `apply_patch` 的随机端口 mock，
写入目标也只是随测试销毁的临时工作区。测试中的 bypass 只在这个受控边界内使用，不能照搬到
连接真实模型的重要目录。Codex 0.144.5 在 Termux 上没有可用的原生平台 sandbox；若再把
approval policy 设为 `never`，即使 patch 使用正确的相对路径，也可能显示误导性的
`writing outside of the project`。真实任务应保留人工批准，或仅在你明确隔离好的可丢弃工作区中
自行选择权限策略。

## 6. Claude Code 配置

先在控制页切换到 **Anthropic**。Claude Code 的 base URL 是根地址，不带 `/v1`：

```bash
export ANTHROPIC_BASE_URL='http://127.0.0.1:8765'
export ANTHROPIC_AUTH_TOKEN='<从控制页复制>'
export ANTHROPIC_MODEL='deepseek-chat'
export ANTHROPIC_DEFAULT_SONNET_MODEL='deepseek-chat'
export ANTHROPIC_DEFAULT_OPUS_MODEL='deepseek-chat'
export ANTHROPIC_DEFAULT_HAIKU_MODEL='deepseek-chat'
claude -p --output-format stream-json --verbose '先调用工具读取当前目录，再告诉我目录名'
```

`ANTHROPIC_AUTH_TOKEN` 会由 Claude Code 作为 Authorization 认证发送；网关同时接受标准
`x-api-key`。Messages 转译覆盖 system 文本块、prompt-cache 附加字段、客户端工具定义、
`tool_use`、`tool_result`、`is_error`、并行工具选择、SSE 和 token count。Claude Code 仍负责
真正执行 Bash/Read/Edit 等本地工具及其权限确认。

仓库还提供 `scripts/claude-deekseep`，它从 DeepSeek 私有连接文件读取当前 URL/Key 后只给本次
Claude Code 进程设置环境变量，不会把明文 Key 写进 shell 配置。当前 Termux 环境的 `claude`
别名已指向这个启动器；原来的上游命令保留为 `claude-upstream`。仓库使用隔离安装的 Claude Code
2.1.0 JavaScript CLI，避免当前新版原生可执行文件没有 Android 架构构建时直接启动失败。
启动器会无条件设置 `ENABLE_INCREMENTAL_TUI=0`；2.1.0 明确把 `0` 解析为关闭增量 TUI，使用完整
重绘和清屏序列，且不会被外部遗留的 `ENABLE_INCREMENTAL_TUI=1` 覆盖。

Android 没有普通 Linux 可写的 `/tmp`。Claude Code 2.1.0 会把 Task/子代理状态和 sandbox 的
`TMPDIR` 固定到 `/tmp/claude`，因此启动器会在每次运行前把这几个生成代码常量机械映射到
`$PREFIX/tmp/claude`，并设置 `TMPDIR/TMP/TEMP`。映射只修改仓库隔离安装的 Claude Code，不创建
根目录 `/tmp`、不改系统权限，也不触碰其他应用。升级隔离安装后，下一次启动会自动重新应用映射。

r19 还处理 Claude Code 的几个本地语义：

- Anthropic 静默阶段在标准 `ping` 后发送非终态、累计的 `message_delta.usage.output_tokens`；
  Claude Code 会过滤纯 `ping`，这个合法活动事件用于持续更新底部动画和长思考 token 数，终态值
  不会小于流中已经显示的最大值；
- `/clear`、`/new` 和 `/reset` 是 Claude Code 的本地命令，命令本身不会请求 `/v1/messages`；
  客户端清空 UI 后生成新 UUID，API 只能处理其后的新请求。`metadata.user_id` 的会话 UUID 与
  首条用户消息指纹会一起做单向哈希，同一对话稳定复用分支；即使旧客户端错误复用 UUID，只要
  已提交的是新 transcript，也会切到新的隐藏分支。原始身份和消息都不写入会话映射；
- 工具型回复如果只有“我将读取/执行/修改”而没有实际 `tool_use`，网关会在同一截止时间内要求一次
  最小真实工具调用。仅在私有 reasoning 末尾出现承诺、正文为空的情况同样覆盖；`/init` 在成功
  Read/Write/Edit `CLAUDE.md` 前不会被当成已完成。

当前隔离 CLI 已真机执行 `/help`、`/status`、`/context`、`/cost`、`/todos`、`/stats`、
`/model`、`/permissions`、`/config`、`/memory`、`/mcp`、`/hooks`、`/agents`、`/skills`、
`/resume` 十五项内部命令；另外完成 `/compact`、`/clear`、`/new`、`/init`、`@文件` 和输入行
`!` Bash 模式。这里验证的是当前随仓库隔离的 Claude Code 2.1.0；以后升级 CLI 应重跑该矩阵。

## 7. 微型流式 Agent

不安装任何 Python 依赖即可测试当前协议和 SSE：

```bash
# 使用控制页当前选择的格式，发送一次后退出
python3 scripts/deekseep-agent.py '请回复：流式测试成功'

# 指定 Anthropic、开启深度思考
python3 scripts/deekseep-agent.py --protocol anthropic --think '分析 17*23'

# 不带提示进入多轮命令行对话
python3 scripts/deekseep-agent.py
```

交互模式输入 `/think` 切换深度思考，也支持 `/think on`、`/think off`、`/clear`、`/new`、`/config`
和 `/quit`。脚本自动读取当前私有连接信息；401 且 Key 已轮换时会重新读取一次。只有尚未收到
任何 token 时才自动重试，已有部分 SSE 输出时不会重放请求，以免 Agent 工具产生重复副作用。

需要验证真实工具闭环时，可运行：

```bash
python3 scripts/test-deekseep-live-tools.py --protocol anthropic
python3 scripts/test-deekseep-live-tools.py --protocol openai
```

脚本不会打印 Key。Anthropic 测试覆盖 SSE `tool_use -> tool_result`；OpenAI 测试覆盖 Chat SSE
`tool_calls -> role=tool` 和 Responses SSE `function_call -> previous_response_id ->
function_call_output`。

## 8. Agent 与工具调用

Chat Completions 支持：

- `tools[].type=function`、旧版 `functions`；
- `tool_choice` / `function_call` 和 `parallel_tool_calls`；
- 非流式或 SSE `tool_calls`；
- 后续 `role=tool`、`tool_call_id` 与工具输出。

Responses 支持：

- `function`、`custom`、`shell` / `local_shell`、`apply_patch`；
- Codex 的 `namespace` 嵌套函数和返回的 `namespace` 字段；
- `function_call_output`、`custom_tool_call_output`、`shell_call_output`、
  `apply_patch_call_output`；
- `previous_response_id` 延续工具循环；
- custom tool 的 grammar/format 原样加入约束提示；
- `web_search` / `web_search_preview` 在允许外部访问时映射到 DeepSeek 原生搜索。

宿主没有公开的原生 OpenAI 工具协议，所以工具调用采用 OmniRoute（MIT）的严格
`<tool>{"name":"...","arguments":{...}}</tool>` 协议、增量隔离和一次格式修复。仓库保留
OmniRoute 原始 TypeScript 快照、许可证和独立 Java 适配层；适配层覆盖标准 `<tool>`、宽松 JSON、
DeepSeek 的嵌套/属性/XML 变体、保守的名称纠错与 schema 唯一匹配。旧 JSON 信封和宿主特殊标签
只作为兼容回退，不再写进主提示词。未校验的标签以及 `Tool call:` 文字不会流入 Claude 正文。
有副作用的 shell/custom/apply_patch 调用会串行返回，避免把依赖步骤错误并行化；普通独立 function
仍可并行。模型偶尔仍可能选错工具或参数，客户端应保留自己的校验、拒绝和重试逻辑。

模型可见提示使用服务无关的 `[TOOL USE INSTRUCTIONS]`，不把网关称为某个“本地 API”产品。
请求中存在 Write、Edit、NotebookEdit、apply_patch 等文件工具时，提示会明确要求创建、修改、保存
工作区文件必须在本轮调用工具，不能用代码块或整文件文本冒充已经写盘；旧私有 JSON 包络只保留为
解析历史模型输出的兼容兜底。若模型仍只返回代码，网关会在代码流向客户端前保留该文本，并复用
有限工具修复把它转换成真实文件调用；明确要求“只给示例/不要修改文件”的请求不触发该规则。

网关会从结构化历史中配对 call ID、工具类型/namespace/名称、规范化 JSON 参数和对应 output。
有副作用的调用获得成功 output 后，若模型再次生成完全相同的调用，网关不会把它交给 Agent
执行，而会在同一总 deadline 内要求模型消费已有结果、给出最终回答或改用真正不同的工具/参数。
参数不同的后续步骤、明确标记失败的工具输出，以及 Read/Glob/Grep 等只读验证工具不会被去重；
因此 Agent 可以在 Edit 后再次 Read 核对磁盘结果，而不会重复执行 Write/Edit/Bash 副作用。

Claude Code 的 `Bash` 工具还会把展示用 `description` 或等待用 `timeout` 改写后重复同一命令。
成功结果的等价签名因此以真实 `command` 为准：只有描述/超时变化不会再次执行；命令文本不同，
或上一条工具结果明确失败时仍允许调用。该规则已用真机 Claude Code 验证为一次 `tool_use`、一次
`tool_result`、两轮后直接返回最终答案。

`previous_response_id` 状态最多保留 128 条、6 小时，并存于当前 DeepSeek 进程内。进程重启后旧
ID 会返回 `previous_response_not_found`；Codex 这类会重发完整历史的客户端不受此限制。与正式
Responses 语义一致，`tools` 和 `tool_choice` 不会由 previous ID 自动继承；需要继续调用其他工具时，
客户端必须在新请求中再次发送工具定义。只回传工具结果且不带 `tools` 时，网关会生成最终回答，
不会重复上一条已完成调用。

## 9. 深度思考

`deepseek-chat` 默认关闭深度思考，`deepseek-reasoner` 默认打开。以下任一参数都可显式开启：

```json
{
  "thinking_enabled": true,
  "deep_think": true,
  "enable_thinking": true,
  "thinking": {"type": "enabled"},
  "reasoning_effort": "medium",
  "reasoning": {"effort": "medium"}
}
```

不需要同时发送全部字段。Chat 响应通过 `reasoning_content` 返回已提供的思考片段；Responses
使用 reasoning 兼容字段和对应流式事件。最终是否返回思考内容仍由服务器和模型决定。

## 10. 稳定性与限流

- DeepSeek 账号同一时间只允许一个可靠的原生生成，因此所有普通、辅助、主 Agent 与 Task/子代理
  请求共享一个公平 `Semaphore(1)`。工具型主/Task 请求在排队前登记优先级；不带工具的标题、摘要
  等 `deepseek-aux` 元数据先在 permit 外等待并单独限速，不能占住唯一原生位置；
- 原生请求起始间隔至少 2.5 秒；
- 遇到 DeepSeek `parallel_chat_limit`/限流后按 2/4/8/12 秒递增冷却；单次请求最多重试一次
  `parallel_chat_limit`，其他尚未输出的瞬时错误使用 0/1.5/3.5 秒有限重试；
- PoW、会话创建、SSE 收集和格式修复共享 170 秒总截止时间，不会无限等待；
- 主 Agent/Task 最多等待原生通道 60 秒，普通 Chat 最多 30 秒；不带工具的辅助请求最多 8 秒，
  超时后直接跳过，避免标题/建议请求拖慢交互主轮；
- 每个原生模型按工作负载类型与哈希客户端会话作用域复用隐藏 API 会话，避免反复创建临时会话
  触发 429，同时确保 `/clear`、`/new` 不复用旧上下文；
- 隐藏会话 ID 失效时会自动创建新会话并重放尚未输出内容的当前请求；
- API 会话不会显示在普通侧栏和编辑器中；映射最多保留 32 条、闲置 24 小时，过期项在原生通道
  空闲时分批走 DeepSeek 删除接口，只有删除成功才移除映射；关闭服务时也会集中清理。
- Agent 已成功执行的完全相同工具调用会被结构化去重；畸形的重复工具信封也先在服务端恢复，
  避免客户端重连后重复产生副作用。
- OpenAI SSE 每 5 秒写注释心跳；Anthropic SSE 每 5 秒写标准 `ping`，随后写非终态累计
  `message_delta` 活动事件。后者让 Claude Code 在 PoW、排队和长思考阶段持续刷新动画/token；
- 后台运行复检失败时网关不启动，已运行的网关在 DeepSeek 回到前台复检时会停止；
- 模块前台保活每 5 秒唤醒一次宿主并确认网关状态，宿主进程因系统回收后重新可启动时也会按已保存
  的开关恢复网关；连续 90 秒没有宿主确认则保活自停，不形成无限唤醒循环。

只有在尚未向客户端输出内容时才会自动重试，避免 SSE 产生重复 token。客户端断开后，排队、冷却、
节流与原生 Flow 等待都会在约 250 ms 粒度内停止本地收集；完整工具调用已捕获时仍会排空上游 Flow，避免
服务端留下阻塞下一轮的“幽灵生成”。客户端超时建议设为至少 180 秒；所有生成串行，健康检查和轻量
控制路由仍由独立 worker 即时响应。

## 11. 状态、日志与排错

控制页每秒刷新后台校验、前台保活、协议、监听状态、原生后端、请求/成功/失败/客户端中断/
工具调用数量、工作线程、队列、最近路由和失败原因。详细文件位于 DeepSeek 私有目录：

```text
/data/data/com.deepseek.chat/files/deekseep_api.log
/data/data/com.deepseek.chat/files/deekseep_api.log.1
/data/data/com.deepseek.chat/files/deekseep_api_status.json
/data/data/com.deepseek.chat/files/deekseep_local_api.txt
/data/data/com.deepseek.chat/files/deekseep_local_api_sessions.json
```

主日志超过 2 MiB 后只保留一份轮转文件。连接信息可能同时尝试写入
`/storage/emulated/0/Deekseep_API.txt`，但 Android 分区存储可能阻止该副本；控制页始终是获取
当前 URL 和 Key 的首选位置。连接文件、状态文件和启动日志包含完整 API Key，分享前必须删除；
请求日志还可能包含工具名、错误原文和会话诊断。

常见错误：

| 错误 | 处理 |
| --- | --- |
| `invalid_api_key` / 401 | 从控制页重新复制 Key；自定义或轮换后旧 Key 立即失效 |
| `protocol_mismatch` / 409 | 控制页所选格式与客户端路由不同；切到 OpenAI 或 Anthropic 后重试 |
| `host_not_ready` / 503 | 保持 DeepSeek 打开，等待原生后端显示已就绪 |
| `gateway_busy` / 503 | HTTP 队列已满，降低并发并退避重试 |
| `too_many_requests` / 429 | 原生通道排队过久；稍后重试 |
| `request_deadline_exceeded` / 504 | 170 秒总预算耗尽；检查网络、服务端限流和日志 |
| `previous_response_not_found` / 400 | DeepSeek 已重启或状态过期；重发完整输入历史 |
| `invalid_api_session` | 通常由网关自动重建；若最终仍返回，查看会话创建错误 |

## 12. 当前边界

- 支持本机和私有 IPv4 局域网访问，但不提供 TLS、公网暴露或访问控制列表；
- 当前 API 输入以文本和工具 item 为主，不接受 OpenAI 多模态图片/data URL；
- token usage 是按文本长度估算，不是服务端计费 token；
- `max_tokens`、temperature 等兼容字段不能保证改变 DeepSeek 原生采样行为；
- HTTP 网关和原生 transport 仍位于 DeepSeek 进程；模块前台服务只做保活/恢复心跳，不对外监听，
  也不能在 DeepSeek 被用户强制停止的状态下替代宿主生成内容；
- Anthropic thinking 的签名由本地网关生成，只用于本地会话回放，不是 Anthropic 云端签名；
- Anthropic server tools 只对明确支持的原生搜索做映射，Claude Code 的客户端工具由 Claude Code 执行；
- 无法绕过 DeepSeek 登录失效、账号风控、服务端能力或内容策略。

这些边界不会影响已验证的本机 Codex、OpenAI SDK、Anthropic Messages 和 Claude Code 客户端
工具循环，但应在自动化任务中保留合理的超时、错误处理和幂等设计。

## 13. 兼容实现参考

Claude Code 会在本地执行 `/clear`（以及别名 `/new`、`/reset`），清空消息和当前会话状态后生成
新的 session UUID，并把它放在下一次请求的
`metadata.user_id` 最后的 `_session_<UUID>` 中；网关按该后缀哈希隔离 DeepSeek 隐藏分支，而不
尝试从普通消息中解析斜杠命令。会话提取、断连取消、有限重试与局域网监听设计参考了开源项目
[`musistudio/claude-code-router`](https://github.com/musistudio/claude-code-router) 的提交
`d21950bdbd2b0566355dedda6c10ff7314c6a2b7`，并针对 Android 宿主单并发限制和 Anthropic SSE
block 生命周期进行了本地适配。参考项目只用于协议与生命周期设计，网关不会代理到其服务。

该项目的网关 pipeline 会先解析并转发上游请求，取得 upstream response 后才把响应流 pipe 给
客户端；本实现据此把 Anthropic 生命周期起点放在原生 Flow 已启动之后，而不是 HTTP 请求刚到达时。
另对照了 [`fuergaosi233/claude-code-proxy`](https://github.com/fuergaosi233/claude-code-proxy)；
其转换器的 eager `message_start` 形态没有照搬，因为它正好会复现本项目这次修正的过早 thinking。

对应参考点是插件中的会话 ID 提取、请求 pipeline 的断连取消、upstream retry policy 的有限
指数退避，以及 supervisor 的通配监听/局域网地址展示。固定提交号用于让这份实现说明可复现，
避免上游后续重构后文件路径或默认策略变化造成误解。
