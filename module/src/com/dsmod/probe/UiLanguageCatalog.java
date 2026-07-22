package com.dsmod.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** English copy catalog used by the manually drawn module UI. */
final class UiLanguageCatalog {
    private static final Map<String, String> EXACT = new HashMap<>();
    private static final List<Entry> FRAGMENTS = new ArrayList<>();

    static {
        // The complete catalog is grouped below by screen. Longer fragments are applied first so
        // formatted module status strings can safely reuse the same translations.
        add("Deekseep", "Deekseep");
        add("导入提示词", "Import prompt");
        add("还原设置", "Reset settings");
        add("系统提示词注入", "System prompt injection");
        add("去他妈的安全审查", "Prevent response replacement");
        add("聊天记录多选", "Multi-select chats");
        add("解锁 Google 登录", "Unlock Google sign-in");
        add("解锁微信与手机号登录", "Unlock WeChat and phone sign-in");
        add("记录服务器返回（诊断）", "Log server responses (diagnostics)");
        add("编辑聊天记录", "Edit chat history");
        add("多账号管理", "Multiple accounts");
        add("导出会话为 Markdown", "Export chats as Markdown");
        add("全局搜索聊天记录", "Search all chats");
        add("会话数据统计", "Chat statistics");
        add("立即备份聊天数据库", "Back up chat database now");
        add("自动备份聊天数据库", "Automatic chat database backup");
        add("实验性功能", "Experimental Features");
        add("帮助与问题", "Help & Questions");
        add("语言", "Language");
        add("选择 Deekseep 语言", "Choose Deekseep language");
        add("跟随 DeepSeek（自动）", "Follow DeepSeek (Auto)");
        add("DeepSeek 为中文时使用中文；其他任何语言使用英文。",
                "Use Chinese when DeepSeek is Chinese; use English for every other language.");
        add("始终显示中文", "Always display Chinese");
        add("始终显示英文", "Always display English");
        add("语言设置保存失败", "Could not save language");
        add("DeepSeek 私有目录暂时不可写，请完整重启后重试。",
                "DeepSeek's private directory is temporarily unavailable. Fully restart the app and try again.");
        add("提示", "Notice");
        add("确定", "OK");
        add("取消", "Cancel");
        add("关闭", "Close");
        add("知道了", "Got it");

        // Standalone module status surface (system language and system color scheme).
        add("运行状态", "Module status");
        add("构建信息", "Build information");
        add("DeepSeek 增强模块", "DeepSeek enhancement module");
        add("状态正常", "Ready");
        add("目标已验证", "Target verified");
        add("等待验证", "Verification pending");
        add("尚未连接", "Not connected");
        add("已连接并生效", "Connected and active");
        add("已在 DeepSeek 生效", "Active in DeepSeek");
        add("等待 DeepSeek 启动", "Waiting for DeepSeek");
        add("等待模块激活", "Waiting for activation");
        add("DeepSeek 目标进程已确认模块注入，当前作用域可以正常使用。",
                "The DeepSeek target process confirmed module injection. The configured scope is working.");
        add("DeepSeek 最近已确认注入；框架服务暂未连接，恢复可用后会自动重连。",
                "DeepSeek recently confirmed injection. The framework service will reconnect when available.");
        add("框架已经连接。请启动一次 DeepSeek，以完成目标作用域验证。",
                "The framework is connected. Launch DeepSeek once to verify the target scope.");
        add("请在 Xposed 管理器中启用 Deekseep、勾选 DeepSeek，然后启动一次 DeepSeek。无需勾选模块应用自身。",
                "Enable Deekseep in your Xposed manager, select DeepSeek as its scope, then launch DeepSeek once. Do not select the module app itself.");
        add("Xposed 框架", "Xposed framework");
        add("DeepSeek 作用域", "DeepSeek scope");
        add("传统框架管理", "Managed by legacy framework");
        add("已连接", "Connected");
        add("未连接", "Not connected");
        add("已验证 · ", "Verified · ");
        add("已验证", "Verified");
        add("等待启动", "Waiting for launch");
        add("模块版本", "Module version");
        add("框架接口", "Framework API");
        add("模块编译时间", "Module build time");
        add("编译时间", "Built on");
        add("模块本体跟随系统语言与深浅色；DeepSeek 内的 Deekseep 页面单独跟随 DeepSeek 语言。",
                "This module app follows the system language and color scheme. The Deekseep page inside DeepSeek follows DeepSeek's language separately.");
        add("Deekseep 模块激活状态", "Deekseep module activation status");
        add("已激活", "Activated");
        add("等待 DeepSeek", "Waiting for DeepSeek");
        add("未激活", "Not activated");
        add("框架已连接，启动 DeepSeek 后验证",
                "Framework connected; launch DeepSeek to verify");
        add("请先在 Xposed 管理器中启用模块",
                "Enable the module in your Xposed manager");
        add("强制执行", "Enforcing");
        add("宽容模式", "Permissive");
        add("未安装", "Not installed");
        add("待验证", "Waiting for verification");
        add("启动 DeepSeek 后确认", "Launch DeepSeek to confirm");
        add("已禁用", "Disabled");

        // Main settings page and Experimental Features.
        add("回答流完后，服务端会追加一帧把整段内容替换成模板回复",
                "After an answer finishes streaming, the server may append a frame that replaces the entire response with the template ");
        add("\u201C这个问题我暂时无法回答\u201D。开启后，模块直接丢弃这帧",
                "\u201cI can't answer that right now.\u201d When enabled, the module discards that frame ");
        add("（带 CONTENT_FILTER 标记的替换帧），已生成的内容原样留在屏幕上。",
                "(the replacement frame marked CONTENT_FILTER), leaving text already received on screen unchanged.");
        add("开启后，长按左侧聊天记录进入多选模式；关闭后使用 DeepSeek 原本的重命名/删除菜单。",
                "When enabled, long-press a chat in the left sidebar to enter multi-select. When disabled, DeepSeek's original rename/delete menu is used.");
        add("国内登录页默认隐藏 Google。开启后保留微信、手机号等入口，并把 DeepSeek 自带的",
                "The mainland sign-in page hides Google by default. Enabling this keeps WeChat, phone, and other options and restores DeepSeek's ");
        add("原生 Google 登录项恢复到列表；点击仍走宿主 Credential Manager 和官方登录接口。",
                "native Google item to the list. Tapping it still uses the host Credential Manager and official sign-in endpoint. ");
        add("请在进入登录页前开启，切换后建议完整重启 DeepSeek。",
                "Enable it before opening sign-in; a full DeepSeek restart is recommended after changing it.");
        add("海外登录页默认隐藏微信和短信手机号。此开关会同时恢复这两个 DeepSeek 原生入口，",
                "The overseas sign-in page hides WeChat and SMS phone sign-in by default. This switch restores both native DeepSeek entries ");
        add("不会联动 Google 开关；点击后仍走宿主自己的微信 SDK、验证码页和官方登录接口。",
                "without changing the Google switch. They still use the host WeChat SDK, verification page, and official sign-in endpoints. ");
        add("把服务器返回的每一条 SSE 原始事件写到日志，用于排查内容为何被替换。",
                "Write every raw server SSE event to a log to diagnose response replacement. ");
        add("日志：/data/data/com.deepseek.chat/files/deekseep_srv.log",
                "Log: /data/data/com.deepseek.chat/files/deekseep_srv.log");
        add("（也会尽量写一份到 /sdcard/deekseep_srv.log）。仅诊断时打开。",
                " (with a best-effort copy at /sdcard/deekseep_srv.log). Enable only for diagnostics.");
        add("长按可修改用户输入、模型回答和思考内容；没有思考链时可新增，",
                "Long-press to edit user input, model answers, and reasoning. You can add missing reasoning ");
        add("并可自定义思考用时（改后重启 DeepSeek 生效）。",
                "and set a custom reasoning duration (restart DeepSeek after changes).");
        add("添加、切换和移除账号；可严格验真导入 JSON，也可勾选账号导出明文凭证。",
                "Add, switch, and remove accounts; strictly validate imported JSON or select accounts to export plaintext credentials.");
        add("把全部本地会话导出成 .md 文件到应用外部目录，可用文件管理器查看/分享。",
                "Export all local chats as .md files to app external storage for viewing or sharing with a file manager.");
        add("检索用户输入、模型回答和深度思考内容，点击进入原生会话。",
                "Search user input, model answers, and deep reasoning, then tap a result to open the native chat.");
        add("统计本地会话数、消息数、总字数，并按账号分组。",
                "Count local chats, messages, and characters, grouped by account.");
        add("把全部 deepseek_chat 数据库复制到应用外部目录，重装前手动留底。",
                "Copy all deepseek_chat databases to app external storage as a manual backup before reinstalling.");
        add("开启后，每次启动 DeepSeek 若距上次备份超过 24 小时，自动把数据库复制到",
                "When enabled, each DeepSeek start copies databases when the last backup is over 24 hours old to the ");
        add("应用内部目录（仅保留最近 5 份）。",
                "app's internal directory (keeping only the latest five backups).");
        add("专家模式图片中继、本地 API 服务及其独立帮助；首次进入需确认风险说明。",
                "Expert image relay, the local API service, and dedicated help. First entry requires risk acknowledgement.");
        add("实验性功能免责声明", "Experimental Features Disclaimer");
        add("以下功能会深度修改 DeepSeek 的模型能力、网络请求和后台运行方式，属于高风险的大工程，不保证稳定，也不保证在宿主升级后继续可用。\n\n",
                "The following features deeply modify DeepSeek model capabilities, network requests, and background behavior. They are high-risk, may be unstable, and may stop working after a host update.\n\n");
        add("• 账号风险：修改客户端能力和以当前账号提供 API 可能触发服务条款或风控，严重时可能导致账号限制或封禁。\n",
                "• Account risk: changing client capabilities or exposing an API through the current account may trigger terms enforcement or risk controls, including account restriction or suspension.\n");
        add("• 数据风险：实验性 Hook、图片中继、隐藏 API 会话和工具循环出现异常时，可能造成聊天记录、缓存或工作区文件损坏、覆盖或丢失。\n",
                "• Data risk: failures in experimental hooks, image relay, hidden API sessions, or tool loops may corrupt, overwrite, or lose chats, caches, or workspace files.\n");
        add("• 隐私与执行风险：专家模式图片可能先交给视觉模型生成描述；本地 API 密钥一旦泄露，其他程序可使用当前账号发起请求。Agent 工具还可能创建、修改文件或执行命令。\n",
                "• Privacy and execution risk: expert images may first be sent to a vision model for description. A leaked local API key lets other programs make requests with the current account. Agent tools may also create or modify files and run commands.\n");
        add("• 使用限制：不要在重要账号上启用，也不要在保存有重要聊天记录或唯一数据副本的设备上测试。请先备份数据库和重要文件，并保留客户端的沙箱、确认和权限隔离。\n\n",
                "• Usage limits: do not enable these features on an important account or test on a device holding important chats or the only copy of data. Back up databases and files first, and keep client sandboxing, confirmations, and permission isolation enabled.\n\n");
        add("功能可能随时失败、产生不完整结果或导致数据丢失。继续表示你已理解上述风险并自行承担后果。",
                "Features may fail at any time, return incomplete results, or cause data loss. Continuing means you understand and accept these risks.");
        add("退出", "Exit");
        add("确认（5）", "Confirm (5)");
        add("确认（", "Confirm (");
        add("确认并进入", "Confirm and enter");
        add("无法保存确认状态", "Could not save acknowledgement");
        add("DeepSeek 私有目录暂时不可写，因此没有进入实验性功能。请完整重启应用后重试。",
                "DeepSeek's private directory is temporarily unavailable, so Experimental Features was not opened. Fully restart the app and try again.");
        add("这些功能可能触发账号风控、造成聊天或文件数据丢失，也可能在 DeepSeek 更新后失效。请勿用于重要账号或保存重要聊天记录的设备。",
                "These features may trigger account controls, cause chat or file loss, or stop working after a DeepSeek update. Do not use them with important accounts or on devices holding important chats.");
        add("解锁专家模式与图片上传", "Unlock expert mode and image upload");
        add("点亮专家模式的思考、搜索和文件能力；图片会先由视觉模型识别，再把描述中继给专家模型。切换后需重进应用或重选模型。",
                "Enable expert reasoning, search, and file capabilities. Images are recognized by the vision model first, then their descriptions are relayed to the expert model. Reopen the app or reselect the model after changing this.");
        add("本地 API 服务", "Local API service");
        add("配置 OpenAI / Anthropic 格式、后台保活、API Key、监听地址与请求统计。",
                "Configure OpenAI/Anthropic formats, background keepalive, API key, listening address, and request statistics.");
        add("仅包含专家模式图片中继和本地 API 的说明、风险与排障。",
                "Help, risks, and troubleshooting for expert image relay and the local API only.");

        // Local API control page.
        add("本地 API 运行在 DeepSeek 进程内。若系统限制后台活动，Termux、",
                "The local API runs inside the DeepSeek process. If the system restricts background activity, DeepSeek SSE and upstream networking may pause or disconnect while Termux, ");
        add("Codex 或 Claude Code 在前台时，DeepSeek 的 SSE 和上游网络会被暂停或中断。\n\n",
                "Codex, or Claude Code is in the foreground.\n\n");
        add("\n\n请在系统页面把 DeepSeek 的电池使用设为“不限制/允许高耗电”，并允许后台活动。",
                "\n\nIn system settings, set DeepSeek battery usage to Unrestricted/Allow high power usage and permit background activity. ");
        add("返回后模块会自动复检；只有两项都通过才会启动监听。启用 API 后还会启动一个",
                "The module rechecks automatically when you return and starts listening only after both checks pass. Enabling the API also starts a ");
        add("前台保活任务，专门防止 Android Cached Apps Freezer 冻结监听和 SSE。",
                "foreground keepalive task to prevent Android Cached Apps Freezer from freezing the listener and SSE.");
        add("先允许 DeepSeek 后台运行", "Allow DeepSeek background activity first");
        add("打开电池设置", "Open battery settings");
        add("校验并进入", "Verify and continue");
        add("无法打开设置", "Could not open settings");
        add("系统没有可用的电池设置入口。请手动进入：设置 → 应用 → DeepSeek → 电池 → 不限制，然后重新点本功能。",
                "No battery settings page is available. Open Settings → Apps → DeepSeek → Battery → Unrestricted manually, then select this feature again.");
        add("后台权限仍未通过", "Background permission still not approved");
        add("\n\n请确认电池使用为“不限制”，且没有关闭后台活动。",
                "\n\nConfirm that battery use is Unrestricted and background activity is not disabled.");
        add("再次打开设置", "Open settings again");
        add("DeepSeek 本地 API", "DeepSeek Local API");
        add("后台运行校验", "Background operation check");
        add("重新校验", "Check again");
        add("启用本地 API 服务", "Enable local API service");
        add("监听本机和局域网；局域网调用同样必须携带 API Key。启用时前台保活会防止后台冻结；彻底退出 DeepSeek 后监听会停止，关闭时会清理复用的服务端会话。",
                "Listen on this device and the LAN; LAN calls also require the API key. A foreground keepalive prevents background freezing while enabled. Listening stops when DeepSeek fully exits, and disabling the service cleans reusable server sessions.");
        add("格式", "Format");
        add("连接配置", "Connection settings");
        add("一键复制 URL", "Copy URL");
        add("一键复制 API Key", "Copy API key");
        add("自定义 API Key", "Custom API key");
        add("8-256 位无空格 ASCII 字符", "8–256 printable ASCII characters without spaces");
        add("保存自定义 Key", "Save custom key");
        add("生成随机 Key", "Generate random key");
        add("Agent / Codex / Claude Code 兼容", "Agent / Codex / Claude Code compatibility");
        add("深度思考参数", "Deep reasoning parameters");
        add("默认关闭。请求中附加任一参数即可让原生请求设置 thinking_enabled=true：\n",
                "Disabled by default. Add any of these request parameters to set thinking_enabled=true on the native request:\n");
        add("• \"thinking\": true 或 {\"type\":\"enabled\"}\n",
                "• \"thinking\": true or {\"type\":\"enabled\"}\n");
        add("Responses 也支持 \"reasoning\": {\"effort\":\"medium\"}。",
                "Responses also supports \"reasoning\": {\"effort\":\"medium\"}. ");
        add("模型使用 deepseek-reasoner 时会自动开启；不附加且使用 deepseek-chat 时保持关闭。",
                "It is enabled automatically for deepseek-reasoner and remains off for deepseek-chat when no parameter is supplied.");
        add("实时监听与请求统计", "Live listener and request statistics");
        add("后台运行校验未通过，监听未启动", "Background check failed; listener not started");
        add("正在启动监听…", "Starting listener…");
        add("服务已关闭，正在清理复用会话…", "Service disabled; cleaning reusable sessions…");
        add("无法打开系统电池设置，请从系统应用设置手动进入",
                "Could not open system battery settings; open them manually from app settings");
        add("后台运行校验通过", "Background operation check passed");
        add("校验未通过，请设为不限制后台活动", "Check failed; allow unrestricted background activity");
        add("已切换为 ", "Switched to ");
        add(" 格式", " format");
        add("URL 已复制", "URL copied");
        add("API Key 已复制", "API key copied");
        add("已生成并启用新的随机 Key", "Generated and enabled a new random key");
        add("Anthropic 格式已选中。base URL 使用页面显示的本机或局域网根地址（不附加 /v1），",
                "Anthropic format is selected. Use the displayed local or LAN root as the base URL (without /v1). ");
        add("提供 POST /v1/messages 与 /v1/messages/count_tokens；支持普通 JSON、",
                "It provides POST /v1/messages and /v1/messages/count_tokens with JSON, ");
        add("SSE、tool_use / tool_result 和 thinking 参数。OpenAI 路由在此模式下会明确返回协议不匹配。",
                "SSE, tool_use/tool_result, and thinking parameters. OpenAI routes explicitly report a protocol mismatch in this mode.");
        add("OpenAI 格式已选中。base URL 以 /v1 结尾，提供 /models、",
                "OpenAI format is selected. The base URL ends in /v1 and provides /models, ");
        add("/chat/completions 与 /responses；支持普通 JSON、SSE 和 Agent 工具循环。",
                "/chat/completions, and /responses with JSON, SSE, and Agent tool loops. ");
        add("Anthropic 路由在此模式下会明确返回协议不匹配。",
                "Anthropic routes explicitly report a protocol mismatch in this mode.");
        add("Anthropic Messages API 已启用：\n", "Anthropic Messages API is enabled:\n");
        add("• Claude Code：ANTHROPIC_BASE_URL 设为上方地址，ANTHROPIC_AUTH_TOKEN 设为上方 Key\n",
                "• Claude Code: set ANTHROPIC_BASE_URL to the address above and ANTHROPIC_AUTH_TOKEN to the key above\n");
        add("• 支持 message_start / content_block_* / message_delta / message_stop SSE\n",
                "• Supports message_start / content_block_* / message_delta / message_stop SSE\n");
        add("• 支持客户端 tools、tool_use、tool_result、并行工具选择与重复副作用抑制\n",
                "• Supports client tools, tool_use, tool_result, parallel tool choice, and duplicate side-effect suppression\n");
        add("• thinking={\"type\":\"enabled\"} 或 adaptive 会打开 DeepSeek 深度思考\n",
                "• thinking={\"type\":\"enabled\"} or adaptive enables DeepSeek deep reasoning\n");
        add("• 每 5 秒发送 ping、累计 token，并在正文开始前恢复 thinking 状态\n",
                "• Sends a ping every 5 seconds, accumulates tokens, and restores thinking state before answer text starts\n");
        add("模型名可使用 deepseek-chat；Claude / sonnet / opus / haiku 名称会作为兼容别名映射到 DeepSeek 默认模型。",
                "Use deepseek-chat as the model name; Claude/sonnet/opus/haiku names map to the default DeepSeek model as compatibility aliases.");
        add("OpenAI Chat Completions 与 Responses API 已启用：\n",
                "OpenAI Chat Completions and Responses APIs are enabled:\n");
        add("• Chat：function tools / tool_calls / tool 结果回传\n",
                "• Chat: function tools / tool_calls / tool-result continuation\n");
        add("• Responses：function、custom、shell、apply_patch 与 previous_response_id\n",
                "• Responses: function, custom, shell, apply_patch, and previous_response_id\n");
        add("• 成功工具按名称与规范化参数去重，避免 Agent 重复执行副作用\n",
                "• Deduplicates successful tools by name and normalized arguments to avoid repeated side effects\n");
        add("• 支持 chunked 请求体、stream_options.include_usage 与 5 秒 SSE 心跳\n",
                "• Supports chunked request bodies, stream_options.include_usage, and 5-second SSE heartbeats\n");
        add("Codex 自定义提供商请把 base_url 设为上方地址、wire API 设为 responses。",
                "For a Codex custom provider, set base_url to the address above and wire API to responses. ");
        add("普通对话可用 deepseek-chat；需要 Codex 完整内建工具目录时可用兼容别名 gpt-5.4。",
                "Use deepseek-chat for normal conversations or the gpt-5.4 compatibility alias when Codex needs its full built-in tool catalog.");
        add("选择 API 格式", "Choose API format");
        add("Chat Completions、Responses 与 /v1/models", "Chat Completions, Responses, and /v1/models");
        add("Messages、count_tokens 与 Claude Code", "Messages, count_tokens, and Claude Code");
        add("服务绑定本机与局域网地址。API 密钥等同 DeepSeek 调用权限；仅在可信网络使用，",
                "The service binds to local and LAN addresses. The API key grants DeepSeek request access; use it only on trusted networks ");
        add("不要公开或转发。DeepSeek 被彻底退出后服务会随进程停止。",
                "and never publish or forward it. The service stops with the process when DeepSeek fully exits.");
        add("复制连接信息", "Copy connection info");
        add("已复制", "Copied");
        add("轮换密钥", "Rotate key");
        add("监听本机与局域网地址，所有业务请求均需 API Key；支持非流式/SSE。",
                "Listens on local and LAN addresses; every generation request requires an API key. Supports JSON and SSE. ");
        add("点本行查看地址、密钥与连接方法。\n",
                "Tap this row to view addresses, the key, and connection instructions.\n");

        add("模块版本：", "Module version: ");
        add("\n编译时间：", "\nBuild time: ");
        add("\nDeepSeek 版本：", "\nDeepSeek version: ");
        add("未知", "Unknown");
        add("读取失败", "Could not read");
        add("功能说明、常见提示与对应解决办法", "Feature notes, common prompts, and solutions");
        add("包含最新功能说明与常见问题。点一下条目展开；问题条目下方均给出解决办法。",
                "Includes current feature notes and common questions. Tap an item to expand it; every question includes a solution.");
        add("【功能】语言自动检测与手动选择", "[Feature] Automatic and manual language selection");
        add("默认在每次 DeepSeek 启动或回到前台时读取宿主当前语言：中文使用中文，任何其他语言使用英文。",
                "By default, the module reads the host language whenever DeepSeek starts or returns to the foreground: Chinese uses Chinese, and every other language uses English. ");
        add("也可在 Deekseep 首页的“语言”中固定为 Chinese 或 English；选择“跟随 DeepSeek（自动）”可恢复自动检测。",
                "You can also lock the module to Chinese or English from Language on the Deekseep page. Select Follow DeepSeek (Auto) to resume automatic detection.");

        // Experimental help.
        add("【功能】专家模式图片上传", "[Feature] Expert-mode image upload");
        add("开启后，专家模式可选择相册图片。图片会先保存到 DeepSeek 私有目录，并由视觉模型生成客观描述，再把描述交给专家模型；同一会话后续轮会继续捕获图片上下文。视觉识别结果和服务器能力不作保证。",
                "When enabled, expert mode can select gallery images. An image is saved to DeepSeek's private directory, objectively described by the vision model, and then relayed to the expert model. Later turns in the same chat continue to capture image context. Vision results and server capabilities are not guaranteed.");
        add("【功能】本地 API 服务", "[Feature] Local API service");
        add("首次进入会校验 DeepSeek 已设为不限制电池优化且允许后台活动，未通过时不会启动监听。OpenAI 格式提供 /v1/models、/v1/chat/completions 和 /v1/responses；Anthropic 格式提供 /v1/messages 与 /v1/messages/count_tokens。两种格式均支持普通 JSON、SSE、深度思考和 Agent 工具结果回传。",
                "On first entry the module verifies that DeepSeek is exempt from battery optimization and allowed to run in the background; listening does not start until both pass. OpenAI format provides /v1/models, /v1/chat/completions, and /v1/responses. Anthropic format provides /v1/messages and /v1/messages/count_tokens. Both support JSON, SSE, deep reasoning, and Agent tool-result continuation.");
        add("【问题】为什么专家模式第一轮能发图，后续轮却提示不支持？",
                "[Question] Why can expert mode send an image on the first turn but reject later turns?");
        add("解决办法：新版会按会话捕获每一轮完整图片 fragment，并在发送点识别专家模型。安装后先冷启动，再新建专家会话测试；服务器若调整模型能力仍可能拒绝，此时可关闭功能改用普通视觉模型。",
                "Solution: the current build captures complete image fragments for every turn and identifies the expert model at the send point. Cold-start after installation and test in a new expert chat. The server can still reject images after a capability change; disable the feature and use the normal vision model in that case.");
        add("【问题】为什么本地 API 返回 401、503 或连接被拒绝？",
                "[Question] Why does the local API return 401, 503, or connection refused?");
        add("解决办法：401 表示 Authorization: Bearer 后的密钥不匹配，可从控制页重新复制；503 表示原生传输或 PoW 尚未初始化，保持应用前台数秒后重试。连接被拒绝通常表示 DeepSeek 已被彻底退出、开关关闭或端口被占用；重新打开应用后查看控制页中的实际端口。",
                "Solution: 401 means the key after Authorization: Bearer does not match; copy it again from the control page. 503 means native transport or PoW is not initialized; keep the app foregrounded for a few seconds and retry. Connection refused usually means DeepSeek fully exited, the switch is off, or the port is occupied. Reopen the app and check the actual port on the control page.");
        add("【问题】为什么本地 API 遇到 429 后会等待一段时间？",
                "[Question] Why does the local API wait after a 429?");
        add("解决办法：这是原生上游限流。网关会串行发送并进行有限冷却；不要让客户端立即高频重试，客户端超时建议至少 180 秒。控制页和私有诊断日志会显示排队、限流与恢复原因。",
                "Solution: this is native upstream rate limiting. The gateway serializes requests and applies a bounded cooldown. Do not make the client retry rapidly; use a client timeout of at least 180 seconds. The control page and private diagnostic log show queueing, rate-limit, and recovery reasons.");
        add("【问题】为什么 Codex 能聊天但没有完整 apply_patch 工具？",
                "[Question] Why can Codex chat but not expose the complete apply_patch tool?");
        add("解决办法：自定义 provider 的 wire_api 使用 responses；需要 Codex 完整内建工具目录时把 model 设为 gpt-5.4。它只是兼容别名，实际仍调用本机默认模型。若工具已返回但 Codex 拒绝执行，请检查工作区、sandbox 和 approval 权限。",
                "Solution: set wire_api to responses for the custom provider. Set model to gpt-5.4 when Codex needs its full built-in tool catalog. This is only a compatibility alias; generation still uses the local default model. If a tool is returned but Codex refuses to execute it, check workspace, sandbox, and approval permissions.");
        add("【问题】为什么 API 调用没有出现在聊天列表？",
                "[Question] Why don't API calls appear in the chat list?");
        add("解决办法：这是预期行为。API 复用独立隐藏会话以降低会话创建限流，但侧栏、编辑器和云目录会过滤它们；关闭服务时才集中走原生删除链清理，不会用每次创建和删除污染正常聊天。",
                "Solution: this is expected. The API reuses separate hidden sessions to reduce session-creation rate limits. The sidebar, editor, and cloud directory filter them. They are removed through the native deletion chain when the service is disabled, avoiding create/delete noise in normal chats.");
        add("【问题】Claude Code 的 /clear 或 /new 为什么看起来还在旧对话？",
                "[Question] Why do Claude Code /clear or /new still look like the old conversation?");
        add("解决办法：这两个命令由 Claude Code 本地处理，不会请求 /v1/messages；API 只能隔离命令成功后的下一次请求。新版会同时按 Claude 会话 UUID 和首条用户消息指纹隔离隐藏分支。若命令后旧内容仍显示，先确认只输入命令并单独按一次回车；某些粘贴/补全场景第一次回车只是确认候选。清屏后询问一个仅旧对话知道的随机词即可判断是否真的串上下文。",
                "Solution: Claude Code handles both commands locally and does not call /v1/messages. The API can isolate only the next request after the command succeeds. The current build isolates hidden branches using both the Claude session UUID and the first-user-message fingerprint. If old content remains visible, enter only the command and press Enter once by itself; in some paste/completion flows the first Enter merely accepts a suggestion. After the screen clears, ask for a random word known only to the old chat to determine whether context truly leaked.");
        add("【问题】为什么请求开始后不会立刻显示 thinking？",
                "[Question] Why doesn't thinking appear immediately after a request starts?");
        add("解决办法：这是修正后的正常顺序。服务会先完成排队、PoW 和原生请求启动，再发送 Anthropic message_start 与 thinking；这样等待本地处理时不会伪装成模型已经开始思考。",
                "Solution: this is the corrected order. The service completes queueing, PoW, and native request startup before sending Anthropic message_start and thinking, so local preprocessing is not misrepresented as model reasoning.");
        add("【风险】怎样降低账号、聊天和文件损失风险？",
                "[Risk] How can I reduce account, chat, and file-loss risk?");
        add("不要使用重要账号或保存唯一聊天记录的设备；开启前备份数据库和工作区文件，不共享 API Key，保留 Agent 沙箱与操作确认。遇到宿主更新、异常重复工具调用或数据不同步时立即关闭实验性开关。",
                "Do not use an important account or a device containing the only copy of chats. Back up databases and workspace files before enabling, never share the API key, and keep Agent sandboxing and confirmations. Disable experimental switches immediately after a host update, abnormal repeated tool calls, or data desynchronization.");
        add("实验性功能 · 帮助与问题", "Experimental Features · Help & Questions");
        add("这里只收录专家模式图片中继和本地 API 的说明。点一下条目展开。",
                "This page covers only expert image relay and the local API. Tap an item to expand it.");

        // General Help & Questions feature notes.
        add("【功能】系统提示词注入", "[Feature] System prompt injection");
        add("选择完整的 TXT/MD 文本后开启开关，模块会在发送请求时把它作为系统指令附加到用户原文前。",
                "Select a complete TXT/MD file and enable the switch. The module prepends it to the user's original input as a system instruction when sending a request. ");
        add("在线历史、数据库写入和旧数据迁移会清理这段包装，因此正常聊天页只应显示用户真正输入的内容。",
                "Online history, database writes, and legacy migration remove this wrapper, so the normal chat page should show only what the user actually entered.");
        add("【功能】回复保留（去安全审查替换）", "[Feature] Preserve replies (prevent safety-template replacement)");
        add("开启后会识别 CONTENT_FILTER 等替换事件，保留本机已经观察到的原始回复，并在冷启动同步时继续保护。",
                "When enabled, the module recognizes replacement events such as CONTENT_FILTER, preserves the original reply already observed on this device, and continues protecting it during cold-start sync. ");
        add("它不能改变服务器规则，也不能恢复开关启用前已经丢失的回答。",
                "It cannot change server rules or recover answers lost before the switch was enabled.");
        add("【功能】聊天记录多选删除", "[Feature] Multi-select chat deletion");
        add("打开开关后，在 DeepSeek 左侧会话列表长按进入多选，勾选后走宿主原生删除事件，同时清理本地会话表、",
                "After enabling, long-press a chat in the DeepSeek sidebar to enter multi-select. Confirmed items use the host's native delete event and also clean local session tables, ");
        add("消息表和 Deekseep 恢复副本。关闭开关会恢复宿主原来的长按菜单。",
                "message tables, and Deekseep recovery copies. Disabling restores the host's original long-press menu.");
        add("【功能】编辑聊天记录", "[Feature] Edit chat history");
        add("每次打开都会重新合并当前账号数据库、宿主已加载会话和最新内存历史。可修改标题、用户消息、AI 回复、",
                "Each opening remerges the current account database, host-loaded sessions, and latest in-memory history. You can edit titles, user messages, AI replies, ");
        add("思考内容和思考用时；图片选择会立即保存，不必再点顶部保存。",
                "reasoning content, and reasoning duration. Image selections save immediately without using the top Save button.");
        add("【功能】新建对话与追加消息", "[Feature] Create chats and append messages");
        add("手机端点编辑器左上角菜单，再点“新建对话”即可建立空白会话；进入任意会话后，底部可直接追加用户消息",
                "On a phone, open the editor's top-left menu and tap Create chat to make a blank chat. In any chat, use the bottom controls to append a user message ");
        add("或 AI 回复。追加动作属于当前会话，不是只能添加首条消息。",
                "or AI reply. Appends belong to the current chat and are not limited to the first message.");
        add("【功能】编辑器相册图片", "[Feature] Gallery images in the editor");
        add("在用户消息的图片管理中点“从相册选择并上传”。模块把长期副本保存到 DeepSeek files 目录，并建立",
                "In a user message's image manager, tap Select from gallery and upload. The module stores a durable copy in the DeepSeek files directory and creates a ");
        add("FileProvider 可读镜像；重启或缓存被清理时会尝试从长期副本重建。AI 消息不能附加用户图片。",
                "FileProvider-readable mirror. It attempts to rebuild the mirror from the durable copy after restart or cache clearing. AI messages cannot attach user images.");
        add("【功能】多账号管理与凭证导入导出", "[Feature] Multiple accounts and credential import/export");
        add("多账号页可添加、切换、移除账号，也可勾选单个或多个账号导出。导入会严格解析完整 JSON，逐个请求",
                "The account page can add, switch, and remove accounts and export one or more selected accounts. Import strictly parses complete JSON and requests ");
        add("DeepSeek 当前用户接口，只有外层和业务层都成功时才整包写入；响应若带账号 ID 还必须一致。导出 TXT 含明文 token，绝不能分享。",
                "DeepSeek's current-user endpoint for every candidate. Nothing is written until both transport and business layers succeed, and any returned account ID must match. Exported TXT contains plaintext tokens and must never be shared.");
        add("【功能】解锁 Google 登录", "[Feature] Unlock Google sign-in");
        add("开启后，模块只把国内登录页隐藏的 DeepSeek 原生 Google 项插回登录方式列表，微信、手机号等国内入口仍保留。",
                "When enabled, the module only inserts DeepSeek's hidden native Google item back into the mainland sign-in list. WeChat, phone, and other mainland options remain. ");
        add("点击后继续使用宿主 Credential Manager 获取 Google ID Token，并交给 DeepSeek 官方 Google 登录接口换票；模块不会读取或记录该 token。",
                "Tapping it still uses the host Credential Manager for a Google ID token and exchanges it through DeepSeek's official Google sign-in endpoint. The module does not read or log that token.");
        add("【功能】解锁微信与手机号登录", "[Feature] Unlock WeChat and phone sign-in");
        add("这是独立于 Google 的一个联合开关。开启后会在海外登录方式列表中同时补回 DeepSeek 原生微信项和短信手机号项，",
                "This combined switch is independent of Google. It restores both DeepSeek's native WeChat and SMS phone items to the overseas sign-in list, ");
        add("保留 Google、密码和注册等已有选项；模块不接管凭证，也不会伪造登录成功。",
                "while preserving Google, password, registration, and other existing options. The module neither handles credentials nor fakes successful sign-in.");
        add("【功能】Markdown、搜索与统计", "[Feature] Markdown, search, and statistics");
        add("Markdown 工具把本地会话导出到应用外部目录；全局搜索覆盖用户输入、AI 回复和思考内容；",
                "The Markdown tool exports local chats to app external storage. Global search covers user input, AI replies, and reasoning. ");
        add("会话统计按账号汇总会话数、消息数和字数。外部导出文件需自行保护。",
                "Chat statistics summarize chat, message, and character counts by account. Protect external export files yourself.");
        add("【功能】手动与自动数据库备份", "[Feature] Manual and automatic database backup");
        add("“立即备份”会复制聊天数据库到应用外部目录。自动备份开启后按启动时间间隔保存到应用内部备份目录，",
                "Back up now copies chat databases to app external storage. Automatic backup saves to the internal backup directory at startup-based intervals ");
        add("并限制保留数量。数据库仍可能在复制时变化，重要操作前建议退出聊天页后再手动备份。",
                "and limits retained copies. A database can still change while being copied; before important work, leave the chat page and make a manual backup.");
        add("【功能】记录服务器返回（诊断）", "[Feature] Log server responses (diagnostics)");
        add("只在排查时开启。它会记录 SSE 事件和部分网络诊断信息，日志可能包含聊天内容或服务器错误；",
                "Enable only while troubleshooting. It records SSE events and some network diagnostics; logs may include chat content or server errors. ");
        add("问题确认后应立即关闭，并在分享日志前自行脱敏。",
                "Disable it immediately after diagnosis and redact logs before sharing.");

        // General Help & Questions troubleshooting.
        add("【问题】为什么会提示“当前显示最新内存记录，等待 DeepSeek 落库后再编辑”？",
                "[Question] Why does it say ‘Showing the latest in-memory record; wait for DeepSeek to save it before editing’?");
        add("解决办法：这是为了避免把不完整的内存快照强行写进数据库。先回到原生会话页等待消息加载和落库，",
                "Solution: this prevents an incomplete in-memory snapshot from being forced into the database. Return to the native chat and wait for messages to load and persist, ");
        add("再重新点选该会话或关闭后重开编辑器。仍提示时，保持网络可用并冷启动 DeepSeek 后再试。",
                "then reselect the chat or reopen the editor. If it persists, keep networking available, cold-start DeepSeek, and retry.");
        add("【问题】为什么会提示“完整在线历史仍在加载”？",
                "[Question] Why does it say ‘Complete online history is still loading’?");
        add("解决办法：当前只拿到增量消息，还没有完整基线，因此禁止追加或物化。先在原生界面打开该对话并等待加载完成，",
                "Solution: only incremental messages are available without a complete baseline, so appending or materializing is blocked. Open the chat in the native UI and wait for loading to finish, ");
        add("然后回到编辑器重新选择；不要连续点击添加按钮。",
                "then return to the editor and reselect it. Do not repeatedly tap an Add button.");
        add("【问题】为什么编辑器提示“未找到聊天数据库”或“没有本地或已加载的对话”？",
                "[Question] Why does the editor say ‘Chat database not found’ or ‘No local or loaded chats’?");
        add("解决办法：确认 DeepSeek 已登录正确账号，并在原生会话列表打开一次目标对话；随后重新进入编辑器。",
                "Solution: confirm DeepSeek is signed into the correct account and open the target chat once from the native list, then reopen the editor. ");
        add("刚切号时需要等待宿主建立该账号数据库。不要清除 DeepSeek 应用数据。",
                "After switching accounts, wait for the host to create that account's database. Do not clear DeepSeek app data.");
        add("【问题】为什么保存或添加时提示“在线历史刚刚更新，请重新打开后再试”？",
                "[Question] Why does save/add say ‘Online history just changed; reopen and try again’?");
        add("解决办法：保存前服务器同步了更新版本，模块为防止覆盖新消息而回滚了整个事务。重新点选对话，核对最新内容后",
                "Solution: the server synchronized a newer version before saving, so the module rolled back the whole transaction to avoid overwriting new messages. Reselect the chat, verify the latest content, ");
        add("再编辑；本次失败不会只写一半。",
                "and edit again. This failure does not leave a partial write.");
        add("【问题】为什么新建对话短暂出现后消失，或点开提示“对话已删除”？",
                "[Question] Why does a new chat briefly appear and disappear, or open as ‘Chat deleted’?");
        add("解决办法：新版通过 sidecar 和原生列表并集保护编辑器本地会话，同时允许服务器新增会话进入。请确认安装的是",
                "Solution: the current build protects editor-local chats using the union of sidecar and native lists while still accepting new server chats. Confirm you installed ");
        add("同一最新版模块并完整冷启动；旧版本创建的异常条目可在编辑器打开并重新保存一次。",
                "the same latest module build and cold-started fully. Open and save an abnormal entry created by an old build once in the editor.");
        add("【问题】为什么点一次“新建对话”偶尔出现两个同名对话？",
                "[Question] Why can one tap on ‘Create chat’ occasionally produce two chats with the same name?");
        add("解决办法：新版有点击防抖和在途锁。出现时先不要重复点击，关闭编辑器再打开确认；若仍为两个真实条目，",
                "Solution: the current build has click debouncing and an in-flight lock. Do not tap again; close and reopen the editor to verify. If two real entries remain, ");
        add("勾选多余的一条删除。安装新版后必须完整重启 DeepSeek，不能只覆盖安装后继续旧进程。",
                "select and delete the extra one. Fully restart DeepSeek after installing the new build instead of continuing the old process after an overwrite install.");
        add("【问题】为什么相册图片提示保存失败、写入失败或“对话已经切换”？",
                "[Question] Why does a gallery image report save failure, write failure, or ‘Chat changed’?");
        add("解决办法：保持目标对话不变，确认系统文件选择器授予了读取权限，并重新选择图片。“对话已经切换”是防止异步",
                "Solution: remain in the target chat, confirm the system picker granted read access, and select the image again. ‘Chat changed’ prevents an asynchronous ");
        add("上传把图片写到错误会话；文件可能已保存，但聊天记录不会被误改。",
                "upload from writing the image into the wrong chat. The file may be saved, but chat history is not modified incorrectly.");
        add("【问题】为什么旧图片提示“图片凭证刷新失败”？",
                "[Question] Why does an old image say ‘Image credential refresh failed’?");
        add("解决办法：旧服务器图片可能只有短期访问凭证。先在 DeepSeek 原生聊天页打开该图片并保持网络可用，再回编辑器重试。",
                "Solution: an older server image may have only a short-lived access credential. Open it in the native DeepSeek chat with networking available, then retry in the editor. ");
        add("从新版相册入口添加的图片使用本地长期副本，不依赖服务器长期凭证。",
                "Images added through the current gallery entry use a durable local copy and do not depend on long-lived server credentials.");
        add("【问题】为什么追加 AI 回复后，用户消息的附带图片消失？",
                "[Question] Why does a user message's image disappear after appending an AI reply?");
        add("解决办法：新版在图片选择时立即保存 FILE fragment，追加 USER/AI 前还会再次保存未提交选择。若是旧版本产生的数据，",
                "Solution: the current build immediately saves the FILE fragment on image selection and saves any pending selection again before appending USER/AI. For data from an older build, ");
        add("重新选择图片并等待“已持久保存并附加”提示后，再追加回复。",
                "select the image again, wait for the durable-save-and-attach confirmation, and only then append the reply.");
        add("【问题】为什么重启后打开带图片的对话变成空白？",
                "[Question] Why does a chat with images become blank after restart?");
        add("解决办法：新版会在启动早期恢复 sidecar、消息头和图片镜像。确认未清除 DeepSeek 私有 files 目录，并安装后完整冷启动。",
                "Solution: the current build restores sidecars, message heads, and image mirrors early at startup. Confirm the private DeepSeek files directory was not cleared and perform a full cold start after installation. ");
        add("若旧版本已经把消息表覆盖为空，模块无法凭空恢复没有备份的数据，可检查数据库备份。",
                "If an older build already emptied the message table, the module cannot recreate unbacked data; check database backups.");
        add("【问题】为什么重启后系统提示词出现在用户消息里？",
                "[Question] Why does the system prompt appear inside a user message after restart?");
        add("解决办法：新版会在在线历史、仓库写入和启动迁移三层清理。保持系统提示词文件不变，完整重启一次让迁移执行；",
                "Solution: the current build cleans it at online history, repository write, and startup migration layers. Keep the system prompt file unchanged and fully restart once to run migration. ");
        add("若数据库正被占用，下一次启动会继续重试。",
                "If the database is busy, the next start retries.");
        add("【问题】为什么原回复重启后又变成“这个问题我暂时无法回答”？",
                "[Question] Why does the original answer become ‘I can't answer that right now’ again after restart?");
        add("解决办法：必须在回复第一次生成时已开启回复保留，模块才能记录原内容并保护冷启动同步。更新后完整重启；",
                "Solution: reply preservation must already be enabled when the answer is first generated so the module can record the original and protect cold-start sync. Fully restart after updating. ");
        add("已经只剩模板且没有本地原文副本的旧消息无法恢复。",
                "An old message that contains only the template and has no local original copy cannot be recovered.");
        add("【问题】为什么多选删除显示已提交，但本地删除为 0 或重启后又出现？",
                "[Question] Why does multi-delete say submitted but remove zero locally or reappear after restart?");
        add("解决办法：最新版先发送宿主真实 h61 删除事件，再按账号数据库清理会话、消息表和恢复副本。确认当前账号正确并重新打开",
                "Solution: the current build first sends the host's real h61 delete event, then cleans sessions, message tables, and recovery copies in the account database. Confirm the current account and reopen ");
        add("编辑器刷新；若服务器删除失败，云端副本仍可能重新同步，应在网络恢复后从原生列表再删一次。",
                "the editor to refresh. If server deletion failed, a cloud copy may sync again; delete it once more from the native list after networking recovers.");
        add("【问题】为什么账号 JSON 导入失败或提示凭证无效？",
                "[Question] Why does account JSON import fail or say the credential is invalid?");
        add("解决办法：只能导入完整 UTF-8 JSON；id、token、email、mobile_number、status、chat_status、id_profiles 和",
                "Solution: only complete UTF-8 JSON is accepted. The id, token, email, mobile_number, status, chat_status, id_profiles, and ");
        add("need_birthday 字段及类型必须齐全。过期 token、外层/业务 code 非 0、网络失败或服务器返回的 ID 不一致都会整包拒绝。",
                "need_birthday fields and types must all be present. Expired tokens, nonzero transport/business codes, network failure, or a mismatched server ID reject the entire package. ");
        add("请从仍正常登录的设备重新导出，不要手工拼 token。",
                "Export again from a device that is still signed in; do not assemble tokens manually.");
        add("【问题】为什么导出账号时反复提示明文凭证风险？",
                "[Question] Why does account export repeatedly warn about plaintext credentials?");
        add("解决办法：这是有意的安全提示，不应关闭。导出文件等同于登录钥匙；只保存到你控制的位置，不通过聊天软件或网盘分享，",
                "Solution: this intentional safety warning should not be disabled. An export file is equivalent to a sign-in key. Store it only somewhere you control and never share it through messaging or cloud drives. ");
        add("导入完成后删除文件。模块不会把 token 写进诊断日志。",
                "Delete the file after importing. The module does not write tokens to diagnostic logs.");
        add("【问题】为什么添加或切换账号后必须重启 DeepSeek？",
                "[Question] Why must DeepSeek restart after adding or switching accounts?");
        add("解决办法：宿主会在进程启动时缓存 key_user_info 和账号仓库，运行中只改文件不能保证所有页面一致。模块只重启",
                "Solution: the host caches key_user_info and the account repository at process startup, so changing files at runtime cannot keep every page consistent. The module restarts only ");
        add("DeepSeek 自己的进程，让宿主按新凭证冷启动；不会停止其他应用。",
                "the DeepSeek process so the host cold-starts with the new credential; other apps are not stopped.");
        add("【问题】为什么开启后仍看不到 Google 登录，或点击后提示不可用？",
                "[Question] Why is Google sign-in still missing or unavailable after enabling it?");
        add("解决办法：先在已登录状态开启“解锁 Google 登录”，再从多账号页添加账号并完整重启 DeepSeek。设备需要可用的",
                "Solution: while signed in, enable Unlock Google sign-in, then add an account from Multiple accounts and fully restart DeepSeek. The device needs working ");
        add("Google Play 服务和网络环境。模块只恢复客户端原生入口，不绕过 DeepSeek 服务器的地区、账号或风控判断；",
                "Google Play services and network access. The module only restores the native client entry and does not bypass DeepSeek server region, account, or risk decisions. ");
        add("若官方接口明确拒绝，请勿反复提交，关闭开关后改用手机号、微信等正常入口。",
                "If the official endpoint explicitly rejects it, do not submit repeatedly. Disable the switch and use a normal option such as phone or WeChat.");
        add("【问题】为什么海外环境仍看不到微信或手机号登录？",
                "[Question] Why are WeChat or phone sign-in still missing in an overseas environment?");
        add("解决办法：开启“解锁微信与手机号登录”后完整重启 DeepSeek，再进入登录页；它不会随 Google 开关自动开启。",
                "Solution: enable Unlock WeChat and phone sign-in, fully restart DeepSeek, and then open the sign-in page. It is not enabled automatically with the Google switch. ");
        add("微信入口还需要设备安装可用的微信客户端，短信入口需要官方服务支持当前号码与地区。服务器拒绝时模块不会绕过。",
                "WeChat also requires a working WeChat client on the device, and SMS requires official support for the number and region. The module does not bypass a server rejection.");
        add("【问题】为什么模块启动页显示“待验证”，LSPosed 明明已经启用？",
                "[Question] Why does the module launch page say ‘Pending verification’ when LSPosed is enabled?");
        add("解决办法：现代 libxposed 不再把模块注入模块应用自身，因此无需在作用域勾选 Deekseep。最新版通过官方 XposedService",
                "Solution: modern libxposed no longer injects a module into its own app, so Deekseep itself does not need to be selected in scope. The current build uses the official XposedService ");
        add("连接判断模块启用，并由 DeepSeek 目标进程回报实际注入。请只确认模块总开关已开、作用域勾选 DeepSeek，然后启动一次",
                "connection to detect enablement and the DeepSeek target process to report actual injection. Confirm the module master switch is on and DeepSeek is selected in scope, then start ");
        add("DeepSeek 再返回模块页；不要用旧版的“自我 Hook”状态作为判据。",
                "DeepSeek once and return to the module page. Do not rely on the old self-hook state.");
        add("【问题】为什么搜索、统计或编辑器显示的账号不对？",
                "[Question] Why do search, statistics, or the editor show the wrong account?");
        add("解决办法：先在多账号页确认“当前”标记，完成切号重启后再打开工具。编辑器默认只显示当前账号；若启用“显示所有账号”，",
                "Solution: first confirm the Current marker on the account page, finish the restart after switching, and only then open tools. The editor shows only the current account by default. If Show all accounts is enabled, ");
        add("保存前必须核对顶部账号和目标数据库。",
                "verify the account and target database at the top before saving.");

        // Chat editor.
        add("已思考", "Reasoned");
        add("无法打开聊天编辑器: ", "Could not open chat editor: ");
        add("已上传图片", "Uploaded image");
        add("新建用户对话", "Create user chat");
        add("新建 AI 对话", "Create AI chat");
        add("新对话", "New chat");
        add("未找到聊天数据库", "Chat database not found");
        add("对话历史", "Chat history");
        add("＋新建对话", "+ Create chat");
        add("选择", "Select");
        add("当前账号", "Current account");
        add("（显示所有账号）", " (showing all accounts)");
        add("只显示当前账号", "Current account only");
        add("显示所有账号", "Show all accounts");
        add("聊天记录范围", "Chat history scope");
        add("请先新建或选择一个对话", "Create or select a chat first");
        add("添加用户消息", "Add user message");
        add("添加 AI 回复", "Add AI reply");
        add("直接输入要追加到当前对话的内容（可留空）",
                "Enter content to append to the current chat (may be empty)");
        add("创建后可点消息下方的“图片”入口，直接从系统相册上传并附加。 ",
                "After creating it, tap Images under the message to upload and attach directly from the system gallery. ");
        add("添加", "Add");
        add("新建对话失败，请确认数据库可写", "Could not create chat; confirm the database is writable");
        add("已新建空白对话，可在底部添加用户消息或 AI 回复",
                "Created a blank chat; add a user message or AI reply at the bottom");
        add("完整在线历史仍在加载，请稍后重新点选后再添加",
                "Complete online history is still loading; reselect the chat later before adding");
        add("添加失败或在线历史刚刚更新，请重新点选对话后再试",
                "Add failed or online history just changed; reselect the chat and try again");
        add("已追加到当前对话", "Appended to the current chat");
        add("删除(", "Delete (");
        add("删除", "Delete");
        add("已选择 ", "Selected ");
        add("选择对话", "Select chats");
        add("先选择要删除的对话", "Select chats to delete first");
        add("删除 ", "Delete ");
        add(" 个对话", " chats");
        add(" 个", " items");
        add("会先走 DeepSeek 原生删除链路提交服务器删除，再清理本机会话、",
                "DeepSeek's native deletion chain submits server deletion first, then cleans local sessions, ");
        add("消息表和 Deekseep 恢复副本。", "message tables, and Deekseep recovery copies.");
        add("已请求 DeepSeek 删除 ", "Asked DeepSeek to delete ");
        add(" 个，本地已移除 ", "; removed locally: ");
        add("，未取得原生链路 ", "; native path unavailable: ");
        add("，本地失败 ", "; local failures: ");
        add("聊天记录", "Chats");
        add("帮助与反馈", "Help & feedback");
        add("保存", "Save");
        add("＋ 用户消息", "+ User message");
        add("＋ AI 回复", "+ AI reply");
        add("没有本地或已加载的对话", "No local or loaded chats");
        add("未命名对话", "Untitled chat");
        add("这是一个空白对话\n请用底部按钮添加用户消息或 AI 回复",
                "This is a blank chat\nUse the bottom buttons to add a user message or AI reply");
        add("正在从 DeepSeek 加载该对话记录…", "Loading this chat from DeepSeek…");
        add("暂时无法请求该云端对话\n请先返回 DeepSeek 主界面刷新侧栏后重试",
                "This cloud chat is temporarily unavailable\nReturn to the DeepSeek home screen, refresh the sidebar, and try again");
        add("该对话没有消息记录", "This chat has no messages");
        add("未能取得该对话的在线记录\n请检查网络后重新点选此对话",
                "Could not obtain this chat's online history\nCheck networking and reselect the chat");
        add("该对话目前只有云端目录，尚未取得在线消息记录",
                "Only the cloud directory entry is available; online messages have not loaded yet");
        add("当前显示的是 DeepSeek 内存记录（只读）\n完整在线历史返回后即可编辑保存",
                "Showing DeepSeek's in-memory record (read-only)\nEditing is available after complete online history returns");
        add("已刷新到 DeepSeek 最新内存记录（暂时只读）\n",
                "Refreshed to DeepSeek's latest in-memory record (temporarily read-only)\n");
        add("等待宿主落库后重新打开即可编辑",
                "Reopen after the host persists it to enable editing");
        add("添加思考内容", "Add reasoning");
        add(" 秒", " sec");
        add("在此输入思考内容（长按进入编辑）", "Enter reasoning here (long-press to edit)");
        add("思考用时（秒）", "Reasoning time (seconds)");
        add("例如 12.5", "For example, 12.5");
        add("图片 0 张 · 从相册添加", "0 images · Add from gallery");
        add("图片 ", "Images: ");
        add(" 张 · 相册 / 管理", " · Gallery / Manage");
        add("当前显示最新内存记录，等待 DeepSeek 落库后再修改图片",
                "Showing the latest in-memory record; wait for DeepSeek to persist it before changing images");
        add("用户消息图片", "User message images");
        add("从相册选择并上传", "Select from gallery and upload");
        add("没有旧图片。可直接从相册选择一张新图片。",
                "There are no existing images. You can select a new image from the gallery.");
        add("也可以勾选本机聊天记录中已上传过的图片：",
                "You can also select images previously uploaded in local chats:");
        add("全部移除", "Remove all");
        add("应用", "Apply");
        add("当前记录还不能附加图片", "Images cannot be attached to the current record yet");
        add("正在保存图片", "Saving image");
        add("正在保存到 DeepSeek 私有目录，并同步登记图片信息…",
                "Saving to DeepSeek's private directory and registering image metadata…");
        add("图片保存失败", "Image save failed");
        add("无法从系统相册读取或复制这张图片；聊天记录没有改变。",
                "Could not read or copy this image from the system gallery; chat history was not changed.");
        add("对话已经切换", "Chat changed");
        add("图片已上传，但为了避免加到错误对话，本次没有写入聊天记录。请回到目标消息重新选择。 ",
                "The image was uploaded but was not written to chat history to avoid attaching it to the wrong chat. Return to the target message and select it again. ");
        add("图片写入失败", "Image write failed");
        add("图片文件已保存，但未能附加到这条用户消息；原聊天记录没有改变，请重新打开后再试。",
                "The image file was saved but could not be attached to this user message. The original chat was unchanged; reopen and try again.");
        add("图片已持久保存并附加到用户消息", "Image saved durably and attached to the user message");
        add("正在准备图片", "Preparing image");
        add("正在向 DeepSeek 获取新的图片访问凭证…", "Requesting a new image access credential from DeepSeek…");
        add("图片凭证刷新失败", "Image credential refresh failed");
        add("无法刷新“", "Could not refresh ‘");
        add("”。请确认网络可用，返回 DeepSeek 聊天页一次后再打开编辑器重试；原聊天记录没有改变。",
                "’. Confirm networking is available, visit the DeepSeek chat page once, then reopen the editor and retry. The original chat was unchanged.");
        add("图片凭证已准备完成，但未能写入这条用户消息；原聊天记录没有改变。",
                "The image credential is ready but could not be written to this user message. The original chat was unchanged.");
        add("图片已刷新并保存", "Image refreshed and saved");
        add("当前显示最新内存记录，等待 DeepSeek 落库后再编辑",
                "Showing the latest in-memory record; wait for DeepSeek to persist it before editing");
        add("最新记录尚未落库，请重新打开编辑器后再保存",
                "The latest record is not persisted yet; reopen the editor before saving");
        add("请先输入思考内容，再设置思考用时", "Enter reasoning before setting its duration");
        add("思考用时必须是大于或等于 0 的秒数", "Reasoning duration must be a number of seconds greater than or equal to zero");
        add("保存失败或在线历史已更新，请重新打开后再试",
                "Save failed or online history changed; reopen and try again");
        add("已保存 ", "Saved ");
        add(" 处，重启 DeepSeek 生效", " changes; restart DeepSeek to apply");
        add("无改动", "No changes");
        add("请先长按一条消息进入编辑，再插入格式",
                "Long-press a message to edit it before inserting formatting");
        add("插入 Markdown 格式", "Insert Markdown formatting");
        add("加粗文字", "Bold text");
        add("斜体文字", "Italic text");
        add("粗斜体文字", "Bold italic text");
        add("删除线文字", "Strikethrough text");
        add("行内代码", "Inline code");
        add("代码块", "Code block");
        add("一级标题", "Heading 1");
        add("二级标题", "Heading 2");
        add("三级标题", "Heading 3");
        add("四级标题", "Heading 4");
        add("五级标题", "Heading 5");
        add("六级标题", "Heading 6");
        add("\u2022 无序列表", "• Bulleted list");
        add("1. 有序列表", "1. Numbered list");
        add("引用文字", "Quoted text");
        add("分割线 \u2500\u2500\u2500", "Divider ───");
        add("链接", "Link");
        add("图片", "Image");
        add("加粗", "Bold");
        add("斜体", "Italic");
        add("粗斜体", "Bold italic");
        add("删除线", "Strikethrough");
        add("无序列表", "Bulleted list");
        add("有序列表", "Numbered list");
        add("引用", "Quote");
        add("内容", "Content");
        add("插入", "Insert");
        add("完成", "Done");
        add("语言（可留空，如 java）", "Language (optional, e.g. java)");
        add("代码", "Code");
        add("插入代码块", "Insert code block");
        add("图片描述（可留空）", "Image description (optional)");
        add("显示文字", "Display text");
        add("链接地址", "Link URL");
        add("插入图片", "Insert image");
        add("插入链接", "Insert link");

        // Multiple-account UI and credential validation.
        add("多账号", "Multiple accounts");
        add("未检测到已登录账号。请先在 DeepSeek 正常登录一个账号。",
                "No signed-in account was detected. Sign in normally to one account in DeepSeek first.");
        add("＋  添加账号", "+  Add account");
        add("导入账号", "Import accounts");
        add("导出账号", "Export accounts");
        add("点击账号切换（会重启 DeepSeek）。切换前当前账号自动备份，长按可移除已保存账号。",
                "Tap an account to switch (DeepSeek will restart). The current account is backed up automatically before switching; long-press to remove a saved account. ");
        add("添加账号会登出当前账号进入登录页。导入会先严格校验 JSON，再逐个请求 DeepSeek ",
                "Adding an account signs out the current account and opens sign-in. Import strictly validates JSON first, then requests DeepSeek ");
        add("确认凭证和账号 ID；请求身份与当前安装的宿主版本一致，批量校验会自动控制频率。",
                "to verify every credential and account ID. Requests match the installed host version and batch validation is automatically paced. ");
        add("全部有效后才一次性写入。导出文件含明文登录凭证，请勿分享。",
                "Nothing is written until every candidate is valid. Export files contain plaintext sign-in credentials and must not be shared.");
        add("当前", "Current");
        add("不能移除当前登录账号", "The currently signed-in account cannot be removed");
        add("切换账号", "Switch account");
        add("切换到「", "Switch to ‘");
        add("」？\n将重启 DeepSeek 以新账号启动，当前账号已自动备份。",
                "’?\nDeepSeek will restart with the new account. The current account has been backed up automatically.");
        add("切换并重启", "Switch and restart");
        add("切换失败", "Switch failed");
        add("无法写入 DeepSeek 登录态，未执行重启。",
                "Could not write DeepSeek sign-in state; restart was not performed.");
        add("正在切换…", "Switching…");
        add("添加账号", "Add account");
        add("将登出当前账号并进入原生登录页以登录新账号（支持微信、手机号等；若已开启“解锁 Google 登录”，",
                "This signs out the current account and opens the native page to sign in to a new account (WeChat, phone, and other methods are supported; if Unlock Google sign-in is enabled, ");
        add("登录页也会显示宿主原生 Google 入口）。\n",
                "the host's native Google entry also appears).\n");
        add("当前账号已自动备份，登录新号后可在多账号里切回。是否继续？",
                "The current account has been backed up automatically and can be restored from Multiple accounts after signing in to the new one. Continue?");
        add("登出并登录新号", "Sign out and add new account");
        add("操作失败", "Operation failed");
        add("无法清除当前登录态，未执行重启。",
                "Could not clear the current sign-in state; restart was not performed.");
        add("正在进入登录页…", "Opening sign-in…");
        add("移除已保存账号", "Remove saved account");
        add("从多账号列表移除「", "Remove ‘");
        add("仅删除本模块保存的凭证备份，不影响服务器数据和本地聊天记录。",
                "Only the credential backup saved by this module is removed. Server data and local chats are not affected.");
        add("移除", "Remove");
        add("已移除", "Removed");
        add("移除失败", "Removal failed");
        add("账号槽文件未能更新，请稍后重试。",
                "The account-slot file could not be updated. Try again later.");
        add("导入账号凭证", "Import account credentials");
        add("只接受完整 JSON。模块会先检查全部账号的字段和类型，再使用每个候选 token 请求 ",
                "Only complete JSON is accepted. The module checks fields and types for every account before using each candidate token to request ");
        add("DeepSeek 当前用户接口；请求会使用当前安装版本的宿主身份并自动限速，且必须同时通过外层和业务层校验。",
                "DeepSeek's current-user endpoint. Requests use the installed host identity, are automatically paced, and must pass both transport and business validation. ");
        add("服务器若返回账号 ID，还必须与文件一致，",
                "Any account ID returned by the server must also match the file, ");
        add("之后才会一次性加入多账号列表。\n\n",
                "and only then are all accounts added to the list in one transaction.\n\n");
        add("校验前不会写入 MMKV、数据库或账号槽。请只导入你本人合法持有的凭证。",
                "Nothing is written to MMKV, databases, or account slots before validation. Import only credentials you lawfully own.");
        add("选择 JSON/TXT", "Choose JSON/TXT");
        add("无法打开文件选择器", "Could not open file picker");
        add("请确认系统文件选择器可用后重试。",
                "Confirm the system file picker is available and try again.");
        add("没有可导出的账号", "No accounts to export");
        add("请先登录或添加至少一个账号。", "Sign in or add at least one account first.");
        add("选择要导出的账号", "Select accounts to export");
        add("导出的是可登录账号的明文凭证。拿到文件的人可能直接使用你的账号，请勿分享，使用后及时删除。",
                "The export contains plaintext credentials that can sign in to your accounts. Anyone with the file may be able to use them. Never share it and delete it promptly after use.");
        add("  · 当前", "  · Current");
        add("导出所选", "Export selected");
        add("请至少勾选一个账号", "Select at least one account");
        add("无法导出", "Could not export");
        add("已选择", "Selected");
        add("未选择", "Not selected");
        add("无法打开保存位置", "Could not open save location");
        add("导出内容已失效，请重新选择账号", "Export selection expired; select accounts again");
        add("目标文件不可写", "Destination file is not writable");
        add("目标文件写入失败", "Could not write destination file");
        add("导出完成", "Export complete");
        add("已保存：", "Saved: ");
        add("\n\n文件含明文登录凭证，请妥善保管且不要分享。",
                "\n\nThe file contains plaintext sign-in credentials. Protect it and do not share it.");
        add("导出失败", "Export failed");
        add("正在读取并校验账号文件…", "Reading and validating account file…");
        add("正在向 DeepSeek 验证 ", "Validating with DeepSeek: ");
        add("账号「", "Account ‘");
        add("」验证失败：", "’ validation failed: ");
        add("全部凭证有效，正在写入账号列表…", "All credentials are valid; writing account list…");
        add("账号槽文件写入失败，未完成导入", "Account-slot write failed; import was not completed");
        add("格式错误：", "Format error: ");
        add("文件不是完整有效的 UTF-8 文本", "The file is not complete valid UTF-8 text");
        add("导入失败", "Import failed");
        add("导入完成", "Import complete");
        add("已验证并加入 ", "Validated and added ");
        add(" 个账号。当前登录账号未改变，可在列表中随时切换。",
                " accounts. The currently signed-in account was not changed; switch from the list at any time.");
        add("\n\n没有写入任何候选登录凭证。",
                "\n\nNo candidate sign-in credentials were written.");
        add("无法读取所选文件", "Could not read selected file");
        add("文件超过 1 MiB 上限", "File exceeds the 1 MiB limit");
        add("凭证格式校验失败", "Credential format validation failed");
        add("账号校验已取消", "Account validation was cancelled");
        add("连接 DeepSeek 服务器超时", "Connection to the DeepSeek server timed out");
        add("无法连接或解析 DeepSeek 校验结果", "Could not connect to or parse the DeepSeek validation result");
        add("DeepSeek 暂时限流，请稍后重试", "DeepSeek is temporarily rate limiting; try again later");
        add("DeepSeek 暂时限流（HTTP 429），请稍后再导入",
                "DeepSeek is temporarily rate limiting (HTTP 429); import again later");
        add("服务器校验失败（HTTP ", "Server validation failed (HTTP ");
        add("凭证已失效或被服务器拒绝（code=", "Credential expired or was rejected by the server (code=");
        add("服务器未确认该凭证有效（code=", "The server did not confirm this credential as valid (code=");
        add("服务器校验响应缺少 biz_code", "Server validation response is missing biz_code");
        add("服务器未确认该凭证有效（biz_code=", "The server did not confirm this credential as valid (biz_code=");
        add("服务器返回的账号与文件中的账号不一致", "The account returned by the server does not match the file");
        add("无法解析 DeepSeek 校验结果", "Could not parse the DeepSeek validation result");

        // Search, export, statistics, activation, and keepalive surfaces.
        add("输入关键词", "Enter keywords");
        add("搜索聊天记录", "Search chats");
        add("搜索", "Search");
        add("搜索中…", "Searching…");
        add("用户输入", "User input");
        add("模型回答", "Model answer");
        add("深度思考", "Deep reasoning");
        add("未找到「", "No results for ‘");
        add("」命中 ", "’: ");
        add(" 条", " matches");
        add("当前登录账号的原生会话列表中没有该对话",
                "This chat is not in the native chat list for the signed-in account");
        add("立即备份", "Back up now");
        add("失败: ", "Failed: ");
        add("**用户**", "**User**");
        add("**助手**", "**Assistant**");
        add("正在导出…", "Exporting…");
        add("没有可导出的本地会话", "No local chats to export");
        add("已导出 ", "Exported ");
        add(" 个会话到\n", " chats to\n");
        add("我 · ", "Me · ");
        add("正在备份…", "Backing up…");
        add("没有可备份的数据库", "No databases to back up");
        add("已备份 ", "Backed up ");
        add(" 个数据库到\n", " databases to\n");
        add("统计中…", "Calculating…");
        add(" 会话 / ", " chats / ");
        add(" 消息\n", " messages\n");
        add("本地账号数：", "Local accounts: ");
        add("会话总数：", "Total chats: ");
        add("消息总数：", "Total messages: ");
        add("正文+思考总字数：", "Answer + reasoning characters: ");
        add("按账号：\n", "By account:\n");
        add("DeepSeek 模块", "DeepSeek module");
        add("版本", "Version");
        add("　·　编译于 ", " · Built ");
        add("\u25CF  已激活", "●  Active");
        add("\u25CB  待验证", "○  Pending verification");
        add("DeepSeek 目标进程最近已验证传统 Xposed 注入。",
                "The DeepSeek target process recently verified traditional Xposed injection.");
        add("尚未收到 DeepSeek 目标回报。请在传统 Xposed/FPA 中启用模块、勾选 ",
                "No report has been received from the DeepSeek target. Enable the module and select ");
        add("DeepSeek，然后启动一次 DeepSeek。",
                "DeepSeek in traditional Xposed/FPA, then start DeepSeek once.");
        add("LSPosed 服务已连接，DeepSeek 目标进程也已验证注入。",
                "LSPosed service is connected and the DeepSeek target process verified injection.");
        add("DeepSeek 目标进程最近已验证注入；框架服务会在可用时自动重连。",
                "The DeepSeek target process recently verified injection; the framework service reconnects automatically when available.");
        add("\u25CF  已启用", "●  Enabled");
        add("LSPosed 已连接本模块。启动一次 DeepSeek 后，将进一步验证目标作用域。 ",
                "LSPosed is connected to this module. Start DeepSeek once to verify target scope. ");
        add("尚未收到现代 Xposed 服务或 DeepSeek 目标回报。请在 LSPosed 启用模块、",
                "No modern Xposed service or DeepSeek target report has been received. Enable the module in LSPosed, ");
        add("勾选 DeepSeek，然后启动一次 DeepSeek。无需勾选模块应用自身。",
                "select DeepSeek, and start DeepSeek once. The module app itself does not need to be selected.");
        add("请授予 Deekseep 储存权限", "Grant Deekseep storage permission");
        add("储存权限已授予", "Storage permission granted");
        add("未授予储存权限", "Storage permission not granted");
        add("DeepSeek 长时间未确认保活，服务已自动停止",
                "DeepSeek did not confirm keepalive for an extended period; the service stopped automatically");
        add("发送保活心跳失败：", "Keepalive heartbeat failed: ");
        add("模块上下文不可用", "Module context is unavailable");
        add("启动", "Start");
        add("停止", "Stop");
        add("前台保活失败：", "Foreground keepalive failed: ");
        add("DeepSeek 已关闭本地 API", "DeepSeek disabled the local API");
        add("前台保活初始化失败：", "Foreground keepalive initialization failed: ");
        add("拒绝了无效的保活启动请求", "Rejected an invalid keepalive start request");
        add("保持本地 API 与 SSE 流在后台可用",
                "Keep the local API and SSE streams available in the background");
        add("DeepSeek 本地 API 正在运行", "DeepSeek Local API is running");
        add("正在保持后台监听与流式响应稳定",
                "Keeping background listening and streaming responses stable");
        add("CPU 保活不可用：", "CPU keepalive unavailable: ");

        // Account JSON codec validation.
        add("文件为空", "File is empty");
        add("JSON 末尾含有多余内容", "JSON has trailing content");
        add("不是完整有效的 JSON", "Not complete valid JSON");
        add("第 ", "Account #");
        add(" 个账号不是 JSON 对象", " is not a JSON object");
        add("JSON 根节点必须是账号对象或账号数组",
                "The JSON root must be an account object or an account array");
        add("文件中没有账号", "The file contains no accounts");
        add("单次最多导入 ", "At most ");
        add(" 个账号", " accounts");
        add("个账号", "accounts");
        add("文件中包含重复账号：", "The file contains a duplicate account: ");
        add("不支持的账号文件版本", "Unsupported account-file version");
        add("缺少 accounts 数组", "Missing accounts array");
        add(" 个账号缺少 credential 对象", " is missing a credential object");
        add("账号", "Account");
        add("不是 JSON 对象", " is not a JSON object");
        add("的 id 长度不正确", " has an invalid id length");
        add("的 token 长度不正确", " has an invalid token length");
        add("的 chat_status 必须是对象", " chat_status must be an object");
        add("的 id_profiles 必须是数组", " id_profiles must be an array");
        add("的 id_profiles[", " id_profiles[");
        add("] 必须是对象", "] must be an object");
        add("的 need_birthday 必须是布尔值", " need_birthday must be boolean");
        add("没有选择账号", "No accounts selected");
        add("选择的账号过多", "Too many accounts selected");
        add("无法生成账号 JSON", "Could not generate account JSON");
        add("DeepSeek账号", "DeepSeekAccount");
        add("_等", "_and_");
        add("_DeepSeek账号.txt", "_DeepSeekAccounts.txt");
        add("缺少字符串字段 ", "Missing string field ");
        add("的 ", " ");
        add(" 不能为空", " must not be empty");
        add("缺少字段 ", "Missing field ");
        add(" 必须是字符串或 null", " must be a string or null");
        add("缺少数字字段 ", "Missing numeric field ");
        add(" 必须是整数", " must be an integer");
        add(" 必须是字符串", " must be a string");
        add("未知账号", "Unknown account");

        Collections.sort(FRAGMENTS, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                return right.zh.length() - left.zh.length();
            }
        });
    }

    private UiLanguageCatalog() {}

    static boolean mightTranslate(String value) {
        if (value == null || value.length() == 0) return false;
        if (EXACT.containsKey(value)) return true;
        for (Entry entry : FRAGMENTS) {
            if (value.contains(entry.zh)) return true;
        }
        return false;
    }

    static String toEnglish(String value) {
        if (value == null || value.length() == 0) return value == null ? "" : value;
        String exact = EXACT.get(value);
        if (exact != null) return exact;
        String translated = value;
        for (Entry entry : FRAGMENTS) {
            if (translated.contains(entry.zh)) {
                translated = translated.replace(entry.zh, entry.en);
            }
        }
        return translated;
    }

    private static void add(String zh, String en) {
        if (zh == null || zh.length() == 0 || en == null) return;
        EXACT.put(zh, en);
        FRAGMENTS.add(new Entry(zh, en));
    }

    private static final class Entry {
        final String zh;
        final String en;
        Entry(String zh, String en) { this.zh = zh; this.en = en; }
    }
}
