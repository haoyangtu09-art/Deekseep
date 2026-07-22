# Deekseep 1.7.1 功能实现与后续移植指南

## 1. 文档目的

本文记录当前聊天记录编辑器、编辑器本地会话、相册图片、冷启动恢复、
系统提示词清理、专家模式图片中继以及 OpenAI/Anthropic 双格式 DeepSeek 本地 API 的完整实现约束，
供后续整理并发布
GitHub 1.7.1、适配新版 DeepSeek APK 或同步其他构建变体时使用。

当前已经完成真机验收的基线是：

- DeepSeek Android 2.2.2，versionCode 233；
- Deekseep 推荐构建：<code>module/</code>，包名
  <code>com.dsmod.probe</code>，libxposed API 102；传统接口使用
  <code>module-legacy/</code>；
- 验收日期：2026-07-19；
- <code>module/src/com/dsmod/probe</code> 是两个稳定包共用的 canonical
  功能核心；传统构建通过兼容适配器编译该核心，不再手工维护功能分叉。

本文只记录项目自有实现和最低限度的宿主数据契约。DeepSeek APK、反编译
输出、用户数据库、日志、图片和签名密钥均不应提交到公开仓库。

## 2. 最重要的设计不变量

升级或重构时必须先保持下面这些约束。只要其中一项被破坏，通常就会重新出现
“本地会话一闪而过”“对话已删除”“冷启动空白”或“图片突然变成 0 张”等问题。

1. 只保护编辑器自己创建或修改、且已经存在 sidecar 的会话。不能拦截整个
   云端会话目录，也不能把服务器列表直接替换为本地列表。
2. 云端同步结果与本地 sidecar 会话做并集：服务器新建的对话必须正常进入，
   服务器普通会话的正常删除也必须保留原行为。
3. 本地写入必须先在一个数据库事务中完成，再刷新 sidecar；sidecar 成功发布后，
   立即把 SID 和当前消息头登记到运行时缓存，不能等待下一次磁盘扫描。
4. 图片选择动作本身就是一次保存动作。不能只更新编辑器内存，等用户再点顶部
   “保存”；追加 USER 或 ASSISTANT 消息时还必须保存尚未落库的图片选择作为兜底。
5. 图片只能附加到 USER 消息的 FILE fragment。追加 AI 回复不得重建、覆盖或删除
   前一条 USER 消息的 FILE fragment。
6. 宿主 WCDB 启动以后，不从延迟后台任务使用 Android SQLite 与它争抢同一数据库。
   sidecar 恢复和消息头修复只能在包加载早期、WCDB 仓库启动前执行；编辑器中的
   明确用户操作除外。
7. 冷启动时不仅要保证 SQLite 表存在，还要保证
   <code>current_message_id</code> 有效，并把 WCDB 行装载到侧栏选中的原生
   <code>tp</code> 对象中。
8. 本地图片使用 DeepSeek 自己的 FileProvider URI。只能对 Deekseep 私有 URI
   前缀短路宿主 URL 构造器，普通服务器图片必须继续走宿主原逻辑。
9. 所有宿主 hook 都应 fail-open。类、签名或字段契约不匹配时记录日志并执行
   原方法，不能因为模块适配失败而破坏 DeepSeek 的普通聊天和云同步。
10. 编辑器弹窗继续使用项目自己绘制的 Dialog/View 组件，不退回系统
    AlertDialog；手机端新建入口在左上角抽屉，消息追加入口固定在对话底部。
11. 本地 API 使用独立 Gateway Key 保护本机/局域网监听；全部生成共享一个公平原生 permit，
    工具型 Agent/Task 优先，无工具辅助流量在 permit 外 pacing；隐藏会话按 workload 与哈希客户端
    会话隔离，PoW 串行且全局限流退避，已向 SSE 客户端输出内容后不得自动重放请求。

## 3. 代码入口与职责

两个稳定接口共用的 canonical 关键文件如下：

| 文件 | 主要职责 |
|---|---|
| [ChatEditorUi.java](../module/src/com/dsmod/probe/ChatEditorUi.java) | 会话列表、编辑器 UI、SQLite 读写、USER/AI 追加、fragment 无损变换、sidecar、图片选择即时保存 |
| [Main.java](../module/src/com/dsmod/probe/Main.java) | 宿主 hook、启动恢复、云同步保护、原生列表并集、原生会话装载、FileProvider URI、专家模式中继 |
| [HistoryBridge.java](../module/src/com/dsmod/probe/HistoryBridge.java) | 在线 <code>pw0</code> 历史清理、内存快照、原生消息转换、完整性和版本判定 |
| [relay/](../module/src/com/dsmod/relay/) | 专家请求模型判定和图片中继的可测试逻辑 |
| [DeekseepUi.java](../module/src/com/dsmod/probe/DeekseepUi.java) | 设置入口、功能页及项目自绘界面 |
| [AccountManager.java](../module/src/com/dsmod/probe/AccountManager.java) | MMKV 当前账号、账号槽、宿主账号表、切号和服务器凭证验真 |
| [AccountUi.java](../module/src/com/dsmod/probe/AccountUi.java) | 多账号列表、自绘勾选/确认/进度弹窗，以及 SAF 导入导出 |
| [AccountCredentialCodec.java](../module/src/com/dsmod/probe/AccountCredentialCodec.java) | 无副作用的严格 JSON 校验、批量导入解析和版本化导出格式 |
| [GoogleLoginUnlock.java](../module/src/com/dsmod/probe/GoogleLoginUnlock.java) | 幂等保留国内登录方式并插入宿主原生 Google 项的无 Android 依赖列表变换 |
| [LocalApiGateway.java](../module/src/com/dsmod/probe/LocalApiGateway.java) | 本机/局域网 HTTP、认证、Chat/Responses JSON 与 SSE、状态、日志和 Responses 延续 |
| [OpenAiToolBridge.java](../module/src/com/dsmod/probe/OpenAiToolBridge.java) | function/custom/shell/apply_patch/namespace 工具约束、解析、校验与结果映射 |
| [test-thinking-regression.sh](../module/test-thinking-regression.sh) | THINK、历史新鲜度、空白会话和图片 fragment 回归 |
| [test-expert-relay-regression.sh](../module/test-expert-relay-regression.sh) | 专家模式首轮与后续轮模型解析回归 |

1.7.1 只发布两个稳定接口包：

| 构建 | 主源码包 |
|---|---|
| 稳定 API 102 | <code>module/src/com/dsmod/probe/</code> |
| 稳定传统 Xposed | 同一 canonical 源码 + <code>module-legacy/compat/</code> |

原测试版已经停止发布，历史源码不再作为 1.7.1 能力声明依据。涉及聊天编辑器的
改动通常至少横跨 <code>ChatEditorUi</code>、<code>Main</code> 和
<code>HistoryBridge</code>；传统构建脚本负责入口和 Hooker API 的机械转换。

## 4. DeepSeek 本地数据契约

### 4.1 数据库和账号

DeepSeek 的账号数据库位于：

~~~text
/data/data/com.deepseek.chat/databases/
~~~

编辑器优先从 DeepSeek MMKV 的 <code>key_user_info.id</code> 解析当前账号，
数据库修改时间只作为旧版本兼容回退。每个会话必须始终绑定原账号数据库，
不能因为“最新修改的数据库”变化而写到另一个账号。

多账号槽位于：

~~~text
/data/data/com.deepseek.chat/files/dsmod_accounts.json
~~~

DeepSeek 2.2.2 的实际登录态是 MMKV <code>key_user_info</code> 中的完整 JSON，核心
是 <code>token</code>，没有发现需要另行拼接的独立 Cookie。账号导出必须原样保留
该对象的全部已知和未知字段，不能只导出 token，也不能臆造浏览器 Cookie。

会话目录表为 <code>chat_session_list</code>。当前 sidecar 保存的 11 列顺序是：

~~~text
id, title, titleType, cache_version, cache_reset_at,
inserted_at, updated_at, current_message_id, schema_version,
pinned, model_type
~~~

每个会话的消息表名为：

~~~text
chat_session_messages_<sid>
~~~

当前标准消息行是 13 列：

~~~text
message_id, parent_id, role, thinking_enabled, status,
inserted_at, feedback_type, accumulated_token_usage,
ban_edit, ban_regenerate, tips, fragments, conversation_mode
~~~

动态表名只能来自通过校验的 SID，并由项目的标识符引用函数处理；内容值继续使用
参数绑定。

### 4.2 fragment

<code>fragments</code> 是 JSON 数组，常见类型包括：

- REQUEST：用户文本；
- THINK：推理内容，必须包含数字类型的唯一 <code>id</code> 和字符串
  <code>content</code>，可选数字 <code>elapsed_secs</code>；
- RESPONSE：正常 AI 回复；
- TEMPLATE_RESPONSE：模板回复；
- FILE：附件描述符数组。

所有编辑都必须克隆并修改目标 JSON 对象，保留未知字段、未知 fragment、非图片
附件和原来的相对顺序。没有显式修改的 Markdown 源文本不能被查看态渲染结果覆盖。

### 4.3 本地会话 sidecar

编辑器拥有的本地会话写入：

~~~text
/data/data/com.deepseek.chat/files/deekseep_local_sessions/
~~~

文件名：

~~~text
<db_id>__<sid>.json
~~~

内容包含：

- <code>db_id</code>：所属账号数据库标识；
- <code>sid</code>：会话 ID；
- <code>session</code>：上面的 11 列会话行；
- <code>messages</code>：完整的 13 列消息行数组。

写入过程先生成临时文件，完整关闭后再 rename 发布，避免读取到半截 JSON。
数据库事务提交后立即刷新 sidecar；rename 成功后调用
<code>Main.registerEditorLocalSession(sid, currentHead)</code>，让同一进程中已经
开始的云目录同步也能马上识别新会话。删除本地会话时同步删除 sidecar 并调用
<code>unregisterEditorLocalSession</code>。

sidecar 既是“该 SID 允许被本地保护”的权限边界，也是冷启动修复来源。不能因为
某个会话恰好有 <code>cache_version = Integer.MAX_VALUE</code> 就把所有同类行当作
编辑器本地会话。

### 4.4 编辑器相册图片

新选图片保存两份：

~~~text
长期主副本：
/data/data/com.deepseek.chat/files/deekseep_editor_images/

DeepSeek FileProvider 可见镜像：
/data/data/com.deepseek.chat/cache/captured/
~~~

FILE 描述符中的 <code>signed_path</code> 使用：

~~~text
content://com.deepseek.chat.provider/tmp_captured_images/<encoded-name>
~~~

主副本负责长期保存；cache 镜像只负责满足宿主 FileProvider 的既有路径约定。
进程启动时如果镜像被系统清理，就从主副本重建。图片历史不能再依赖服务器短期
凭证，也不能把 Termux 或公共相册的临时 URI 直接写进消息。

## 5. 功能实现

### 5.1 系统提示词不进入可见历史

请求发送前仍可把导入提示词包装为：

~~~text
<system>
...
</system>

用户原文
~~~

但包装只服务于请求，不应成为用户聊天记录。修复由三层组成：

1. <code>pw0</code> 在线历史对象构造时，同步清理每条消息中的包装前缀，再让
   DeepSeek 渲染或写库；
2. <code>gm8/fm8</code> 仓库写入前再次清理行 JSON，作为防御层；
3. 启动迁移扫描旧数据库，幂等删除历史遗留和重复堆叠的包装。只有全部账号数据库
   都成功处理才写“迁移完成”标记；遇到锁或异常，下次启动继续重试。

编辑器显示和保存前也会清理包装，防止旧数据再次扩散。冷启动后原生聊天页只显示
用户真正输入的内容。

### 5.2 编辑器每次打开都读取最新对话

不能只读 <code>chat_session_list</code>。DeepSeek 可能已经在内存渲染新对话，
但还没有建立对应消息表。编辑器采用三路合并：

1. 当前账号 SQLite 目录和已有消息表；
2. 侧栏捕获的原生 <code>List&lt;tp&gt;</code>，用于补充最新标题、时间、模型和
   SQLite 尚未出现的会话；
3. <code>HistoryBridge</code> 从 <code>pw0</code> 捕获的有界、带版本在线快照。

REPLACE 历史建立完整基线，MERGE 按 message ID 合并。没有完整基线时只读展示，
不能直接物化和编辑。用户确实修改时，编辑器在同一个事务里重新校验快照身份和
版本、建立标准消息表、写入宿主转换后的行、应用编辑并冻结会话；如果宿主先写入
了更新版本，本次操作回滚并要求重新打开。

因此“APP 里能正常打开但编辑器说没有找到聊天记录”的修复重点不是放宽空表判定，
而是桥接宿主已经拥有的内存历史。

### 5.3 新建对话和追加消息

手机端从编辑器左上角三条杠打开抽屉，点击“新建对话”后直接创建空白会话，
不弹系统主题输入框。默认标题为“新对话”，之后可像普通标题一样编辑。

创建操作在一个事务中：

1. 生成 UUID SID；
2. 插入一条 <code>chat_session_list</code> 行；
3. 设置 <code>cache_version = Integer.MAX_VALUE</code>；
4. 创建标准 13 列消息表；
5. 提交后马上生成 sidecar 并登记运行时 SID。

USER 和 AI 入口只保留在对话底部。它们总是向当前会话末端追加普通消息，不存在
“添加首条”的特殊流程，也不会因为已有内容而另建会话。

### 5.4 防止一次点击创建两个相同对话

重复创建可能来自 Compose 重组、重复监听器或短时间双击，因此使用两层幂等保护：

- UI 层用 <code>createConversationInFlight</code> 和时间窗口禁用重复点击；
- 数据层用进程级锁、数据库路径和最近创建记录做 2.5 秒去重。如果最近 SID 仍存在
  且消息表为空，第二次调用直接复用该 SID。

数据层保护不能省略，因为将来 UI 入口变动或其他调用点绕过按钮时，仍可能发生
重复写入。日志分别记录 <code>created blank conversation</code> 和
<code>reused recent blank conversation</code>。

### 5.5 USER 图片选择必须即时持久化

相册入口使用 Android 系统照片选择器读取用户选中的原图，并复制到 DeepSeek 私有
目录。得到本地 FILE 描述符后，<code>persistImageSelectionNow</code> 立即：

1. 开启数据库事务；
2. 只替换目标 USER 消息中的图片描述符；
3. 保留该 FILE fragment 内的非图片附件以及其他全部 fragments；
4. 更新 <code>cache_version</code> 和 <code>updated_at</code>；
5. 提交后刷新 sidecar；
6. 把编辑器内选择签名标记为已保存。

这意味着相册选择成功后，即使不点顶部“保存”，重新打开编辑器也应显示图片。

<code>appendCurrentMessage</code> 在追加 USER 或 ASSISTANT 前，还会把任何
<code>edited</code> 且签名变化的 ImageEdit 放进同一事务保存。这是兼容旧状态或
异步回调边界的最后保险，确保“先选图，再添加 AI 回复”不会因为刷新当前会话而把
图片恢复成 0 张。

删除全部图片也属于一次明确编辑，应由相同的 fragment 变换和保存路径处理。

### 5.6 图片冷启动和点击查看

宿主通常把服务器相对路径交给 <code>us.a(host)</code>，由它拼成 HTTPS URL。
如果把 Deekseep 的 <code>content://</code> 地址也交给这段代码，就会得到无效
网络地址，虽然私有图片和 cache 镜像都存在，点击仍会提示“图片加载失败”。

<code>hookLocalEditorImageUris</code> 只在 <code>signed_path</code> 以
Deekseep 私有 FileProvider 前缀开头时直接返回 <code>Uri.parse(raw)</code>。
其他所有服务器图片继续执行原来的 <code>us.a</code>，不能按“所有 content URI”
或“所有本地图片”做宽泛拦截。

冷启动顺序必须先恢复 <code>cache/captured</code> 镜像，再让历史渲染器解析
FILE 描述符。

### 5.7 本地会话与云目录同步共存

DeepSeek 的 <code>p68</code> 云目录事务会读取本地目录，并删除服务器结果里不存在
的会话表。这对普通云会话是正常校验，但编辑器新建的 SID 天生不在服务器结果里。

正确处理是：

1. 进入 <code>p68</code> 的特定同步事务时设置线程上下文；
2. 在 <code>aw.a</code> 返回的“参与云端缺失比较”的本地行中，只移除 sidecar
   拥有的 SID；
3. 不修改服务器返回的新会话，不跳过整个事务；
4. 在延迟服务器刷新 <code>ed0.h</code> 的每个协程阶段前缓存 sidecar 对应的
   <code>tp</code>，阶段结束后把缺失项补回同一个 <code>ed0.e</code>
   <code>uo7</code> 状态源；
5. <code>mc.f</code> 渲染侧栏时仍做一次只补缺失项的并集，作为重映射失败时的
   展示兜底；
6. 每次本地提交完成后立刻更新运行时 SID 缓存，关闭 sidecar 已写但 1.2 秒磁盘
   缓存尚未刷新的竞态。

由此得到预期语义：

~~~text
最终原生状态 = 最新服务器会话 ∪ 有有效 sidecar 的本地会话
~~~

这不是“关闭云同步”。服务器新建会话继续进入，普通服务器会话删除也继续生效；
只有明确由编辑器 sidecar 所有的本地 SID 不参与“服务器缺失即删除”的比较。

### 5.8 修复本地对话“已删除”和冷启动空白

仅让目录行留下还不够。点击本地对话后，DeepSeek 会尝试拉取云端详情；服务器对
本地 SID 返回业务码 1，宿主把它解释为“对话已删除”，弹 Toast 并切到空会话。

当前保护包含：

- 在原生点击回调识别 sidecar SID，记录短期 pending local open；
- 对 <code>za1.E</code> 的本地详情重载做窄范围保护；
- 在 <code>za1.N</code> ViewModel 事件边界拦截本地 SID 的精确业务码 1；
- 以 <code>at0.a</code> 作为未内联版本的补充保护；
- 对服务器普通 SID、其他错误码和其他事件全部执行原逻辑。

另一个空白来源是云端轻量目录行没有 <code>current_message_id</code>，却覆盖了
本地冻结会话的有效消息头。修复分为：

1. 包加载早期运行 <code>repairFrozenCurrentMessageIds</code>：仅检查
   <code>cache_version = Integer.MAX_VALUE</code> 且消息头为空或不存在的会话，
   从实际消息表恢复最大 message ID；
2. <code>p68</code> 合并前由 <code>preserveFrozenDirectoryHeads</code> 把有效
   本地头复制到仅缺失头的 incoming 目录对象；
3. 点击时 <code>hydrateFrozenNativeSession</code> 复用 DeepSeek 自己的
   <code>gm8 → sl8 → ve1 → ie</code> WCDB 管线，把行装载到当前选中的原生
   <code>tp</code>，而不是手工伪造宿主消息对象。

必须先成功装载本地原生状态，再阻止远端详情结果；否则虽然不再弹“已删除”，页面
仍然会是空白。

### 5.9 THINK、Markdown 和无损保存

新建 THINK 时分配未被其他 fragment 使用的数字 ID；修改时只定位相同 ID 的对象。
<code>elapsed_secs</code> 必须保持数字类型。修复旧损坏记录时只处理 ID 缺失或
非数字的 THINK，不改变 RESPONSE、FILE 或未知 fragment。

查看态 Markdown 只负责显示，编辑态保留源码。保存逻辑使用“原始签名”和明确
edited 状态判断，避免用户只改标题或图片时把未触碰的 Markdown 替换为渲染后的
纯文本。

### 5.10 专家模式后续轮图片

DeepSeek 只在首轮请求的 <code>ew0</code> 中可靠写入 <code>model_type</code>，
后续轮可能为 null。仅检查请求字段会导致第一张图片可用、同一会话再次发图却提示
当前模型不支持上传。

修复在真实发送点 <code>fu0/uu0</code> 捕获：

- 本轮完整 <code>List&lt;fp&gt;</code> 图片对象；
- 当前原生会话 <code>tp.f()</code> 的有效模型。

随后把两者绑定到紧接着生成的同一个 <code>ew0</code>。transport 中模型解析优先
使用请求显式值，没有时使用该请求绑定的有效模型，不能用全局“最近模型”替代。

专家图片中继继续按以下流程工作：

1. 捕获完整图片元数据；
2. 获取新的 completion PoW；
3. 每张图创建独立临时视觉会话；
4. 多图并行描述但按输入顺序汇总；
5. 删除临时会话；
6. 把描述追加进专家提示词，并从专家请求移除图片文件；
7. 把原始图片 fragment 保留在本地历史。

### 5.11 侧栏与编辑器批量删除

DeepSeek 2.2.2 的会话行删除回调会构造 <code>h61(tp)</code>，交给
<code>mc.f</code> 参数中的中央 <code>ib3</code> 事件处理器。宿主随后调用
<code>/api/v0/chat_session/delete</code>，成功后从原生列表和 WCDB 目录删除。

旧实现只要反射调用回调没有同步抛异常，就显示“已提交”，并且跳过本地清理。
这会产生两个问题：

1. 异步服务器请求是否成功完全没有被验证，却被描述成已删除；
2. 编辑过的会话即使被宿主删掉，Deekseep sidecar 仍在，冷启动恢复任务会把它
   重新插回数据库。

当前侧栏多选和编辑器多选共用以下语义：

1. 从 <code>mc.f</code> 同时捕获原生 <code>List&lt;tp&gt;</code>、点击处理器和
   中央事件处理器；
2. 对每个 SID 找到精确 <code>tp</code>，构造并提交 <code>h61(tp)</code>；
3. 无论原生异步链稍后是否已删过目录行，都幂等执行本地 DELETE 和
   DROP TABLE；
4. 删除或改名停用精确 <code>&lt;db_id&gt;__&lt;sid&gt;.json</code> sidecar；
5. 清除运行时本地 SID、冻结 head、HistoryBridge 快照和缓存 <code>tp</code>；
6. 使用两分钟进程内 tombstone，避免请求进行中时旧原生目录立即重新合并；
7. 分开显示“已请求 DeepSeek 删除”和“本地已移除”，不再把反射调用等同于结果。

编辑器里的 native-only 会话也进入相同流程；本地删除是幂等的，因此目录行已经
不存在时仍会清理消息表和 sidecar。其他账号若没有当前登录账号的原生
<code>tp</code>，只能完成本地清理，并明确报告未取得原生链路。

### 5.12 审查替换后的回复跨冷启动保留

只拦截实时 <code>mv.i</code>、<code>mv.S/R</code> 和最终 <code>tp</code> 合并只能
保护当前进程。彻底退出后，服务器历史会再次下发已经定稿的
<code>CONTENT_FILTER + TEMPLATE_RESPONSE</code>；原来的 <code>mv</code> 已不存在，
因此内存对象比较无法恢复正文。

当前实现以“确实观察到替换事件”为写入凭证：

1. 在跳过替换前把原 <code>mv</code> 放入弱引用待保存集合；
2. 取得所属 <code>tp.a</code> SID 后，调用宿主 <code>mv.a()</code> 得到静态
   <code>kv</code>，再用 <code>x94.a + hv.a</code> 原样序列化；
3. 按 SID 和 message ID 写入私有
   <code>files/deekseep_preserved_responses</code>，正文更完整者优先；正文相同时
   <code>FINISHED</code> 优先于 <code>STREAMING</code>；
4. 冷历史 <code>pw0.b</code> 离开构造 Hook 前恢复消息对象；
5. <code>gm8/fm8.b</code> 写 WCDB 前恢复对应 <code>sl8/rl8</code> 行；
6. <code>tp.u/q/p/a</code> 仍保留最终运行时兜底；
7. 显式删除会话时同步删除该 SID 的回复快照。

恢复条件必须同时满足：同一 SID、同一 message ID、ASSISTANT、已有有效私有快照，
且这次服务端对象本身带 <code>CONTENT_FILTER</code> 或
<code>TEMPLATE_RESPONSE</code>。普通服务端更新、普通拒答和没有替换凭证的消息不得
覆盖。功能启用前已经被替换且原文已丢失的旧消息无法反推，不能伪造恢复内容。

### 5.13 多账号凭证导入与导出

账号导出使用系统 Storage Access Framework 的 <code>ACTION_CREATE_DOCUMENT</code>
选择目标位置，但账号勾选和风险确认使用项目自绘 Dialog。单账号和多账号统一写成
带版本号的 JSON 文档，文件扩展名为 <code>.txt</code>：

~~~json
{
  "format": "deekseep_account_export",
  "version": 1,
  "exported_at": 1784390000000,
  "accounts": [
    {
      "name": "账号显示名",
      "credential": {
        "token": "此处仅示意，真实文档保留完整值",
        "id": "账号 UUID",
        "email": null,
        "mobile_number": null,
        "status": 1,
        "chat_status": {},
        "id_profiles": [],
        "need_birthday": false
      }
    }
  ]
}
~~~

文件名由选中账号的显示名组成，过滤路径分隔符和控制字符；超过三个账号时追加总数。
导出正文和 token 不得进入日志。拿到导出文件的人可能直接使用账号，因此界面和首次
免责声明都必须明确提示这是明文登录凭证。

导入使用 <code>ACTION_OPEN_DOCUMENT</code>。整个处理顺序是：

1. 限制文件最大 1 MiB，并用严格 UTF-8 解码；
2. 使用 <code>JSONTokener</code> 读取一个完整根值，根值之后只允许空白，拒绝尾随文本；
3. 支持版本化导出封装、单个原始 <code>key_user_info</code> 对象，以及旧账号槽数组；
4. 要求每个账号同时包含正确类型的 <code>id</code>、<code>token</code>、
   <code>email</code>、<code>mobile_number</code>、<code>status</code>、
   <code>chat_status</code>、<code>id_profiles</code> 和
   <code>need_birthday</code>；未知字段原样保留；
5. 拒绝空账号、超过 32 个账号、重复 ID，以及批次中任意一条格式错误；
6. 逐个用候选 token 请求 DeepSeek 官方只读接口
   <code>/api/v0/users/current</code>；只有 HTTP 200、外层
   <code>code = 0</code> 和内层 <code>biz_code = 0</code> 才通过。
   <code>HTTP 200 + code 40002</code> 仍是无效凭证。该接口允许
   <code>biz_data</code> 为空；如果响应实际带 ID，还必须与文件中的 ID 相同。请求的
   鉴权必须沿用宿主 Ktor bearer provider 的
   <code>Authorization: Bearer &lt;token&gt;</code>，不能使用遥测栈的
   <code>x-auth-token</code>，否则有效 token 也会返回 <code>code 40002</code>。
   同时带上宿主的 <code>x-rangers-id</code> 和当前
   <code>x-client-timezone-offset</code>。<code>User-Agent</code> 必须沿用宿主网络层的
   <code>DeepSeek/&lt;version&gt; Android/&lt;sdk&gt;</code> 形状，不能写成
   <code>okhttp/4.12.0</code>，否则当前边缘校验会固定返回 HTTP 429；
7. 所有账号都完成验真以后，才在内存中与旧槽合并，写临时文件、同步刷盘并原子
   rename 发布；随后更新 <code>app_user_info</code>。任意账号失败时，候选凭证一条
   也不写；
8. 导入只加入多账号列表，不自动改变当前 MMKV 指向。用户显式点账号切换时，才写
   <code>key_user_info</code> 并只重启 DeepSeek 自身进程。

网络校验必须运行在后台线程，连接和读取都设置超时，不跟随重定向，不接受仅有 HTTP
状态成功的响应，也不能把 token、完整响应或导入文件正文写入诊断日志。批次请求起始时间
至少间隔 1.5 秒；真实 HTTP 429 最多自动重试一次，只接受不超过 10 秒的数字
<code>Retry-After</code>，更长或无法解释的等待要求直接提示稍后重试。响应流必须只写入一次
有界缓冲区，避免重复 JSON 导致解析失败。服务器限流、无网络、响应结构变化都应
fail-closed：提示失败且不写入候选账号。

### 5.14 “帮助与问题”和新版免责声明

设置页入口统一改为“帮助与问题”。页面前半部分说明系统提示词、回复保留、专家图片、
多选删除、聊天编辑器、新建/追加消息、相册图片、多账号、Google 登录解锁、Markdown、
搜索、统计、备份和诊断日志；后半部分的问题标题统一采用“为什么会提示……？”并紧跟解决办法，覆盖：

- 内存历史尚未落库、完整在线基线仍在加载；
- 未找到数据库、会话为空、同步期间保存回滚；
- 本地会话短暂出现后消失、提示已删除、重复新建；
- 相册保存、图片凭证、追加 AI 后图片丢失、冷启动空白；
- 专家模式后续轮图片、系统提示词可见、审查模板跨冷启动；
- 多选删除本地为 0、账号导入失败、账号明文导出和切号重启；
- Google 入口仍未显示、设备没有可用 Google Play 服务，以及服务器地区/风控拒绝。

首次免责声明不再使用系统 AlertDialog，而是使用项目自绘且不可点外部取消的 Dialog。
标记文件继续放在 DeepSeek 私有 files 目录，但内容改为明确版本串；旧时间戳不等于新版
标记，因此既有用户升级后会看到一次补全后的免责声明，接受后同一版本不再重复弹出。
风险说明至少包含账号封禁/协议、数据库和云同步冲突、回复保留边界、图片视觉中继、
明文账号凭证、地区登录凭证经宿主提交给官方登录接口、外部备份和诊断日志隐私、
宿主版本兼容，以及合法使用和风险自担。当前免责声明版本为
<code>2026-07-18-v4</code>。

### 5.15 国内登录页恢复原生 Google 登录

DeepSeek 2.2.2 会先由 <code>qt6</code> 取得地区。<code>cn8.d() = true</code> 时，
<code>dy4.y</code> 构造国内登录方式列表，保留一键登录、微信、短信/密码等项，但不加入
<code>px4.a</code>。国际分支使用的 <code>px4.a</code> 正是宿主原生 Google 项；其点击链为：

~~~text
px4.a -> z40 -> gy4.g(sx4.g) -> kg3.c(Credential Manager)
      -> Google ID Token -> /api/v0/users/oauth/google/login
~~~

因此不能把整个设备地区强改成海外，也不应自己拼 OAuth 页面。稳定核心使用私有
开关文件：

~~~text
/data/data/com.deepseek.chat/files/deekseep_google_login_unlock
~~~

开关启用时，<code>Main.hookRegionalLoginUnlock</code> 同时 hook <code>cy4</code> 的构造器和
静态 copy 方法，在已经填充且全部元素均为 <code>px4</code> 的列表前插入同一个
<code>px4.a</code> 单例。必须遵守以下约束：

1. 空列表是 ViewModel 启动占位，不能提前插入；
2. 列表已有 <code>px4.a</code> 时原样返回，不能出现两个 Google 按钮；
3. 新列表只做浅复制，国内入口的对象和原顺序必须完整保留；
4. 列表元素类型不符合预期、字段/签名变化或 hook 失败时执行宿主原方法；
5. 对构造器和 copy 方法都 deoptimize，覆盖解释/JIT/内联状态创建路径；
6. 点击事件不得被模块接管，继续走宿主 Credential Manager、验证码配置和官方换票接口；
7. 不读取、不保存、不记录 Google ID Token，也不把服务器业务错误伪装成成功；
8. 功能只恢复客户端入口，不能绕过 Google Play 服务可用性、DeepSeek 的地区规则、账号限制
   或服务器风控。官方接口拒绝时应提示用户停止重试并使用正常登录方式。

设置页开关默认关闭。建议用户在仍处于已登录状态时打开，再从多账号页执行“添加账号”；
退出登录和冷启动仍走现有的原生账号备份流程。关闭开关后应完整重启 DeepSeek，不能为了
即时移除按钮而误删海外地区本来就应显示的原生 Google 项。

### 5.16 海外登录页联合恢复微信与手机号

<code>dy4.y</code> 的国际分支默认列表为 Google、密码和注册，即
<code>[px4.a, px4.d, px4.e]</code>；国内分支使用的宿主原生微信和短信手机号单例分别是
<code>px4.f</code> 与 <code>px4.b</code>。<code>z40/gy4</code> 已为它们保留微信 SDK 和短信验证码
点击链，所以不需要改地区值或重做登录 UI。

稳定构建增加一个独立于 Google 的联合开关：

~~~text
/data/data/com.deepseek.chat/files/deekseep_wechat_mobile_login_unlock
~~~

开关打开时，沿用 5.15 的 <code>cy4</code> 构造/copy hook，在同一次参数变换里同时补齐
<code>px4.f</code> 和 <code>px4.b</code>：

1. 国际列表从 <code>[Google, password, register]</code> 变为
   <code>[Google, WeChat, mobile, password, register]</code>；
2. 已存在任一地区项时只补缺项，不复制已有对象；两个都存在时返回原列表；
3. 没有 Google 但已有国内一键/微信项时，把短信放在微信之后，不改变一键登录顺序；
4. 空启动列表、非 <code>px4</code> 元素和反射异常继续 fail-open；
5. Google 开关和联合开关互不联动，用户可分别启用；
6. 只恢复入口，不接管微信 token、短信验证码或服务器业务判断。

纯 JVM 回归覆盖不可变国际列表、顺序、缺一补一、完整列表幂等、国内一键顺序、空列表和
混入非登录类型等情况。

### 5.17 现代 LSPosed 激活检测与页脚版本信息

旧实现把 <code>com.dsmod.probe</code> 自身加入作用域，再尝试 hook
<code>SettingsActivity.isModuleActive()</code>。这与现代 libxposed 契约冲突：
[LSPosed 现代 API 文档](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
明确说明模块应用不再被自身 hook，应用侧应注册 Xposed service 监听；
[libxposed service](https://github.com/libxposed/service) 的官方实现使用
<code>&lt;applicationId&gt;.XposedService</code> ContentProvider 接收 <code>SendBinder</code>。

稳定 API 102 构建现在使用两层证据：

1. Manifest 导出 <code>com.dsmod.probe.XposedService</code>，接收到描述符为
   <code>io.github.libxposed.service.IXposedService</code> 的活 Binder 后显示“已启用”；
2. DeepSeek 的 Activity 恢复时调用同一 Provider 的目标回报方法。Provider 不信任 Bundle
   中的包名，而用 <code>Binder.getCallingUid()</code> 和 <code>getPackagesForUid()</code> 确认 UID
   属于 <code>com.deepseek.chat</code>，再把时间和宿主版本写入模块私有 SharedPreferences；
3. 框架连接和目标新鲜心跳同时存在时显示“已激活”；只有目标心跳时仍可判定实际注入；只有框架
   Binder 时显示“已启用，等待 DeepSeek 验证”；两者暂时都没有时显示“待验证”，不再武断显示
   “未激活”；
4. modern <code>scope.list</code> 只保留 <code>com.deepseek.chat</code>，不要求用户勾选模块应用；
5. 旧共享存储 marker 继续只作日志诊断，不再是启动页主判据，避免权限、文件所有者和时效误判。

DeepSeek 内的 Deekseep 页在“帮助与问题”下面追加构建页脚，显示
<code>BuildInfo.MODULE_VERSION</code>、libxposed API、编译时间，以及运行时读取的 DeepSeek
<code>versionName/versionCode</code>。<code>build.sh</code> 每次构建从 Manifest 和 module.prop
生成这些常量，截图即可定位 APK 与宿主组合。

### 5.18 DeepSeek 本地 API 与 Codex 工具闭环

稳定核心已把之前的假设性计划落地。<code>LocalApiGateway</code> 在 DeepSeek 目标进程中监听
本机和局域网地址，默认端口 8765，并用独立随机或自定义 Gateway Key 保护
<code>/v1/models</code>、<code>/v1/chat/completions</code> 和 <code>/v1/responses</code>。
Chat 和 Responses 都支持普通与 SSE，HTTP 同时接受 Content-Length 和 chunked 请求体。

原生生成全部进入一个账号级公平 permit；工具型主/Task 请求登记优先级，无工具辅助元数据在持有
permit 前等待并 pacing。每个模型按 workload 与哈希客户端会话复用一个被侧栏/编辑器过滤的隐藏
分支，请求仍提交完整上下文和空 parent；失效 SID 会在尚未输出 delta 时创建新会话并重试。
PoW 串行签发，起始间隔全局至少 2.5 秒，真实 429 使用 15/25/40 秒冷却，队列、PoW、重试、SSE
和一次工具格式修复共享 170 秒 deadline。

<code>OpenAiToolBridge</code> 支持 Chat function 和 Responses function/custom/shell/apply_patch/
namespace，保留 Codex custom grammar，并把 tool output 继续送回下一轮。<code>gpt-5.4</code> 是让
Codex 加载完整工具目录的兼容别名，实际仍映射 DeepSeek 默认模型；网关只传递工具结构，不绕过
Codex sandbox 或 approval。完整 Agent 历史会按 call ID 配对成功 output，并对类型、namespace、
名称和稳定排序参数完全相同的重复调用做恢复，防止同一 shell/patch 副作用被重复执行；不同参数和
失败结果仍可继续调用。

控制页、使用示例、已知边界见 [DeepSeek 本地 API 使用说明](LOCAL_DEEPSEEK_API.md)，实现取舍与
真机验收矩阵见 [本地 API 实现状态与计划](LOCAL_DEEPSEEK_API_GATEWAY_PLAN.md)。两个 1.7.1
稳定接口包都从同一 canonical 源码编译本功能。

## 6. 已修复问题对照表

| 现象 | 根因 | 修复 |
|---|---|---|
| 发消息后彻底退出，再打开能看到系统提示词 | 请求包装被在线历史渲染或写库 | <code>pw0</code> 同步清理、仓库写入防御、可重试旧库迁移 |
| 实时拦截后答案还在，彻底重启又变成“暂时无法回答” | 原文只存在旧进程的 <code>mv</code>，冷历史再次下发定稿模板 | 替换事件驱动的精确 <code>kv</code> 私有快照；<code>pw0</code>、仓库写入和 <code>tp</code> 三层按 SID/message ID 恢复 |
| 原生 APP 有记录，编辑器提示没有找到聊天记录 | 只读 SQLite，忽略了 <code>tp/pw0</code> 内存历史和账号归属 | MMKV 当前账号、原生目录合并、HistoryBridge 完整快照和按需物化 |
| 新建对话在侧栏出现几秒后消失，重启又短暂出现 | <code>p68/aw</code> 虽保住数据库，但延迟 <code>ed0.h</code> 刷新仍裁掉原生状态里的本地 <code>tp</code> | 云目录排除 sidecar SID，并在 <code>ed0.h</code> 后补回同一 <code>ed0.e</code> 状态源；<code>mc.f</code> 仅作展示兜底 |
| 点击本地对话提示“对话已删除”并跳到空白 | 本地 SID 的云详情返回业务码 1 | <code>za1.E/N</code> 与 <code>at0.a</code> 的精确本地保护 |
| 本地对话重启后保留，但打开内容全空 | 消息头被目录写空，原生 <code>tp</code> 没从 WCDB 装载 | 启动修头、同步保头、点击时复用 <code>ve1/ie</code> 原生管线装载 |
| 点一次新建偶尔生成两个同名对话 | UI 重入或短时间重复调用 | UI in-flight 防抖 + 数据层锁和 2.5 秒空会话幂等复用 |
| USER 选图后追加 AI，图片从 1 张变 0 张 | 图片只存在编辑器内存，追加消息后重新加载旧数据库 | 选择成功即时事务保存；append 前再保存 pending ImageEdit |
| 多选 8 个只提示“已提交 8 个，本地删除 0 个”，重启后仍存在 | 把同步反射返回当作异步删除成功，并在原生请求后跳过 sidecar 清理 | 提交真实 <code>h61(tp)</code> 事件，同时幂等删除本地行/表并停用 sidecar |
| 编辑器第一次称删除成功，第二次称云端目录未删除 | native-only 被跳过，或云端又同步回只做本地 DELETE 的会话 | 编辑器也走中央原生删除链；请求数与本地最终状态分别计数 |
| 自定义图片提示没有长期可用图片凭证 | 把本地历史图片依赖于服务器临时凭证 | 私有长期主副本 + FileProvider cache 镜像 + 本地 FILE 描述符 |
| 重启后缩略图存在但点击提示图片加载失败 | <code>us.a</code> 把 content URI 拼成 HTTPS URL | 仅对私有 URI 前缀直接返回 <code>Uri</code> |
| 带图本地会话冷启动后整段消息空白 | sidecar、消息头、运行时 SID 和原生状态之间存在竞态 | 提交后 sidecar、即时注册、启动恢复/修头、原生装载组成完整链路 |
| 专家模式第一轮图片有效，后续轮提示不支持 | 后续 <code>ew0.model_type</code> 为空 | 在 <code>fu0/uu0</code> 捕获 <code>tp.f()</code> 并按请求绑定 |
| 账号 JSON 只要能解析就被写入，过期 token 也出现在列表 | 只做语法解析，没有服务器业务码验证 | 严格字段/类型校验；逐个验证 <code>users/current</code> 的外层和内层业务码；全通过后原子写入 |
| 多账号只能切换，无法安全迁移到另一设备 | 没有版本化凭证文件和 SAF 流程 | 自绘多选导出、账号名文件名、完整凭证封装、严格批量导入和明文风险提示 |
| 国内登录页只显示微信/手机号，无法选择 Google | 国内 <code>dy4</code> 分支从 <code>cy4.b</code> 登录方式列表省略 <code>px4.a</code> | 可选地把宿主原生 Google 项幂等插回列表；保留所有国内入口并继续走原生 Credential Manager/官方换票链 |
| 海外登录页只有 Google/密码，没有微信和手机号 | 国际 <code>dy4</code> 分支省略 <code>px4.f/px4.b</code> | 一个独立联合开关同时补齐宿主微信和短信项，保持 Google 等原项与原生点击链 |
| LSPosed 已启用，模块启动页仍显示“未激活” | 现代模块应用不会被自身 hook，旧判据永远无法成立 | 官方 XposedService Binder 判断框架连接，DeepSeek UID 心跳判断目标注入；无证据时显示待验证而非误报 |
| 本地 API 大部分请求报 <code>session_create_failed</code>，偶尔才成功 | 每个请求创建/删除临时会话触发服务端会话限流 | 每个 native model 按 workload 与哈希客户端会话复用隐藏分支；失效映射自动重建，关闭服务时再集中走原生删除链 |
| Codex 文本能回复，但没有完整工具或工具结果断链 | 只实现 Chat 文本/SSE，未实现当前 Codex 的 Responses item 与 namespace/custom grammar | 完整 Responses 生命周期、previous response、function/custom/shell/apply_patch/namespace 和 output 映射 |
| Claude 已拿到成功 Bash 结果，却把同一命令换个 description/timeout 又执行一次 | 完成调用签名把展示/等待元数据也当成副作用参数 | Bash 成功签名按真实 command 归一；失败结果或不同命令仍允许执行，并加入 Anthropic 端到端回归 |
| Claude Code 长期停在 0 token、动画冻结，或出现 <code>parallel_chat_limit</code> | 纯 ping 被 CLI 过滤，且旧双 lane 会同时启动账号级原生 generation | Anthropic ping 后追加累计 usage 活动 delta；所有生成共享单一公平 permit，Agent/Task 优先，辅助元数据在 permit 外 pacing |
| Edit 后最终 Read 被当作已完成重复而不执行 | 副作用防重没有区分只读验证工具 | Read/Glob/Grep 等只读工具允许再次调用，Write/Edit/Bash 等副作用仍按稳定签名防重 |
| 连续 API 调用遇到 429 后持续失败 | 客户端和网关同时立即重试，形成请求风暴 | 单一公平原生 permit、全局 2.5 秒 pacing、15/25/40 秒原生限流冷却和一个共享 deadline |
| 帮助页仍把专家图片写成开发中，且提示没有解决办法 | 功能说明未随修复同步 | 改名“帮助与问题”，功能和 FAQ 分区；每个问题明确给出解决办法 |
| 老用户看不到新增风险说明 | 免责声明只检查旧标记文件是否存在 | 版本化标记让升级用户仅补看一次；改为自绘弹窗并覆盖账号、数据、图片、导出和日志风险 |

## 7. DeepSeek 2.2.2 混淆符号地图

这些名字只适用于当前基线，升级时必须按角色、字段布局、构造签名和调用链重新确认，
不能看到同名就直接认为兼容。

| 当前符号 | 当前角色 / 识别依据 |
|---|---|
| <code>pw0</code> | 在线历史响应，持有会话与消息列表；构造后可做清理和快照 |
| <code>gm8</code> / <code>fm8</code> | 2.2.2 / 2.2.1 历史 WCDB 仓库候选 |
| <code>sl8</code> / <code>rl8</code> | 每会话消息表或持久化行相关对象 |
| <code>rs7</code> / <code>qs7</code> | 2.2.2 / 2.2.1 FILE 图片 fragment |
| <code>tp</code> | 原生会话状态，包含 SID、模型、消息映射和当前头 |
| <code>uo</code> | 原生消息对象，可转换为编辑器快照行 |
| <code>mv.i / mv.S / mv.R</code> | 动态回复的 fragment/status 补丁入口；观察到审查替换时保存原对象 |
| <code>kv</code> | 静态消息；<code>mv.a()</code> 可生成适合跨进程持久化的精确副本 |
| <code>x94.a / hv.a</code> | 宿主 JSON codec 与 <code>kv</code> serializer，避免自行猜测消息字段 |
| <code>qt6 / cn8 / dy4</code> | 地区状态及登录方式列表构建；CN 分支只是不加入 Google 项，不能全局伪造地区 |
| <code>cy4.b / px4.a / px4.b / px4.f</code> | LoginUiState 登录方式列表 / Google / 短信手机号 / 微信原生项 |
| <code>gy4 / kg3</code> | 地区登录点击事件；Google 分支经 Credential Manager 取 ID Token 和官方换票链 |
| <code>mc.f</code> | 侧栏根 Composable，参数中包含 <code>List&lt;tp&gt;</code> 和点击处理器 |
| <code>ib3.g(tp)</code> | 侧栏原生会话点击回调 |
| <code>p68</code> | 云会话目录合并/清理事务 |
| <code>aw.a</code> | 读取参与目录比较的本地行列表 |
| <code>am8.h</code> | 轻量目录对象的当前消息头字段 |
| <code>za1.E</code> | 会话详情远端重载路径 |
| <code>za1.N</code> | ViewModel 会话事件入口，真实 UI 删除分支经过此处 |
| <code>at0.a</code> | 业务错误处理辅助方法；可能被 ART 内联 |
| <code>ve1</code> | 从 WCDB 读取某 SID 原始消息行的协程状态机 |
| <code>ie</code> | 把消息行和 head 应用到选中 <code>tp</code> 的映射状态机 |
| <code>us.a</code> | 根据 FILE 的 <code>signed_path</code> 解析图片 URI |
| <code>sf5</code> | 模型功能配置，包含模型、思考、搜索和文件能力 |
| <code>gf5</code> | 文件上传能力配置，最大文件数是关键识别字段 |
| <code>y91.a</code> | 上传门禁消费真实 <code>sf5</code> 的路径 |
| <code>fu0</code> / <code>uu0</code> | 图片发送点，可取本轮 <code>fp</code> 与当前 <code>tp</code> |
| <code>ew0</code> | completion 请求对象 |
| <code>r92</code> / <code>s92</code> | 2.2.1 / 2.2.2 completion transport 候选 |
| <code>q71</code> | completion PoW 管理器 |

适配新版时优先建立“调用者 → 参数签名 → 返回类型 → 字段结构 → 下游调用”的证据链。
Kotlin suspend 状态机和 Compose synthetic 方法很容易改名或增加默认参数掩码，仅凭
方法名不够。

## 8. 未来 1.7.1 适配流程

### 8.1 准备

1. 给当前可工作的项目源码做 Git 提交或补丁备份。
2. 备份 DeepSeek 私有数据库、sidecar 和编辑器图片；不要把备份放进仓库。
3. 在本地反编译目标 APK，只用于定位契约，不提交 APK 或生成源码。
4. 记录目标 DeepSeek 版本名、versionCode、CPU ABI、数据库 schema 和
   FileProvider path 配置。

### 8.2 先验证稳定数据层

依次确认：

1. <code>chat_session_list</code> 11 个字段的语义和类型；
2. 每会话消息表 13 个字段的语义和类型；
3. REQUEST、THINK、RESPONSE、TEMPLATE_RESPONSE、FILE 的序列化 JSON；
4. <code>current_message_id</code> 与父链规则；
5. FileProvider authority 和 <code>cache/captured</code> 映射；
6. 当前账号 MMKV 键；
7. 宿主是继续使用 WCDB，还是改用了新 DAO/数据库引擎。

任何 schema 不一致都应先升级 sidecar 格式或增加版本字段，不能把旧数组位置直接
写进新表。

### 8.3 再映射 hook

推荐按以下顺序映射和验证：

1. 在线历史和仓库写入：<code>pw0</code>、<code>gm8/fm8</code>；
2. 原生会话与侧栏点击：<code>tp</code>、<code>uo</code>、
   <code>mc.f</code>、<code>ib3</code>；
3. 云目录与运行时状态合并：<code>p68</code>、<code>aw</code>、
   <code>ed0.h/e</code>、<code>uo7</code>、目录 row 的 head；
4. 本地详情加载和删除事件：<code>za1</code>、<code>at0</code>；
5. WCDB 原生装载：<code>ve1</code>、<code>ie</code>；
6. 图片 fragment 与 URI：<code>rs7/qs7</code>、<code>us</code>；
7. 专家能力和 transport：<code>sf5</code>、<code>gf5</code>、
   <code>fu0/uu0</code>、<code>ew0</code>、<code>r92/s92</code>、
   <code>q71</code>。

每个 hook 匹配后都要保留失败时 <code>chain.proceed()</code> 的路径。不要为了适配
一个本地会话问题而宽泛吞掉所有云端错误或返回空列表。

### 8.4 先修改 canonical，再验证两个接口

先在 <code>module/</code> 的 canonical 源码完成和验证。稳定实现确认后：

1. 运行 canonical JVM 回归；
2. 构建 modern API 102 包；
3. 由 <code>module-legacy/build.sh</code> 生成传统入口并通过兼容适配器构建；
4. 对两个接口重新检查方法是否会被内联，以及是否需要 deoptimize；
5. 更新 [Variant matrix](VARIANTS.md)，不再为已停止发布的测试版声明能力。

### 8.5 编译、安装和启动必须分开

先单独完成编译和安装，确认安装命令已经返回 Success，再执行启动。不要把安装、
强制停止和启动并行放在同一段自动化里，否则 DeepSeek 可能先启动并继续使用旧模块
进程或旧 APK。

未来真机验证可按这种顺序执行，但每条命令应单独等待完成：

~~~sh
bash module/build.sh

su -c 'pm install -r /absolute/path/to/ds-probe.apk'

su -c 'am force-stop com.deepseek.chat'

su -c 'monkey -p com.deepseek.chat 1'
~~~

只允许停止 <code>com.deepseek.chat</code> 这个目标包。不要使用
<code>killall</code>、宽泛 <code>pkill</code>、停止系统服务、清除应用数据、
禁用应用、卸载 DeepSeek 或删除整个私有目录。需要观察冷启动时，先确认用户数据
已有备份。

## 9. 回归测试清单

### 9.1 自动测试

至少运行：

~~~sh
ANDROID_SDK_ROOT="$PREFIX/tmp/android-sdk" bash module/test-thinking-regression.sh
bash module/test-expert-relay-regression.sh
~~~

预期覆盖：

- THINK 创建、ID 修复、内容和时长修改；
- 未触碰 fragment 无损；
- 最新历史选择、空白会话和图片变换；
- 在线 HistoryBridge 清理和快照；
- 原生延迟目录刷新后的本地会话状态并集合并；
- 专家模式显式首轮模型与后续轮捕获模型。

### 9.2 新建会话

1. 打开编辑器，记下原会话数。
2. 快速双击一次“新建对话”入口。
3. 只能增加 1 条目录行、1 个消息表和 1 个 sidecar。
4. 关闭编辑器再打开，仍只有 1 条。
5. 冷启动 DeepSeek 后，本地会话不能先显示再消失。
6. 同时从服务器同步一个新会话，服务器新会话必须正常出现。
7. 至少等待一次目录网络请求完成（建议 10 秒），再次点击本地会话仍应显示原消息，
   不能只验证启动后的第一帧。

### 9.3 图片即时保存

1. 在本地会话追加 USER 文本。
2. 从系统相册选一张从未保存到 Deekseep 的图片。
3. 不点顶部保存，立即重新打开编辑器；USER 应显示 1 张图片。
4. 再次选图后，直接从底部追加 AI 回复，不点顶部保存。
5. USER 的 FILE fragment 仍是 1 张，AI 回复存在，顺序和父链正确。
6. 冷启动后对话仍包含 USER、图片和 AI。
7. 在原生 DeepSeek 页面点击缩略图，应打开图片查看页，不出现“图片加载失败”。

### 9.4 本地会话冷启动

1. 新建本地会话并至少追加 USER 和 AI 两条消息。
2. 完全退出并重新启动 DeepSeek。
3. 会话列表稳定显示，不闪退、不消失。
4. 点击后不出现“对话已删除”。
5. 原生页面显示完整消息，不是空白。
6. <code>current_message_id</code> 指向实际存在的最后消息。
7. 普通云端会话仍可打开、同步和删除。

### 9.5 系统提示词和专家图片

1. 开启提示词，发送普通用户消息，冷启动后用户气泡只显示原文。
2. 检查旧对话，不能出现一层或多层 system 包装。
3. 专家会话首轮上传图片应可发送。
4. 在同一会话后续轮再次上传图片也应可发送，不能提示模型不支持。
5. 多图描述顺序与输入顺序一致，临时视觉会话完成后被正常清理。

### 9.6 多选删除

1. 在原生侧栏多选至少 3 个普通云端会话并确认删除。
2. 提示必须分别显示 DeepSeek 删除请求数和本地移除数，不能再显示模糊的
   “已提交 N 个，本地删除 0 个”。
3. 立即打开编辑器，被删会话不得从旧 <code>tp</code> 或 HistoryBridge 快照出现。
4. 冷启动后 sidecar 不得恢复被删会话。
5. 在编辑器中同时选择 SQLite 会话和 native-only 会话，二者都必须尝试原生链路
   并完成幂等本地清理。
6. 对同一批 SID 再执行一次本地清理应保持成功的“已不存在”状态，不能因
   DELETE 影响 0 行而误报整体失败。
7. 若网络导致服务器删除失败，宿主可显示原生错误；之后服务器重新同步回来是
   真实服务器状态，不能伪报为云端已成功。

### 9.7 审查替换跨冷启动

1. 开启回复保留后触发一次实际 <code>CONTENT_FILTER</code> 替换，实时界面仍显示原文。
2. 日志应出现 <code>preserved original response</code>，但不得记录正文。
3. 彻底停止并重开 DeepSeek，等待服务器历史同步完成后再次进入同一会话。
4. 原文仍存在，且日志出现 online-history、repository-write 或 final-apply 的恢复标记。
5. 同 message ID 的普通 FINISHED 服务端消息不得被快照覆盖。
6. 删除该会话后，相同 SID 的私有回复文件必须清理。
7. JVM 回归必须覆盖 STREAMING 快照被等长 FINISHED 快照升级的情况。

### 9.8 多账号导入导出、帮助与免责声明

自动测试至少覆盖：

1. 单个原始凭证和版本化多账号文档往返后未知字段不丢失；
2. 根 JSON 后有尾随文本时拒绝；
3. 八个必需字段缺失或类型错误时拒绝；
4. 批次中只有一个坏账号时整批拒绝；
5. 重复 ID 拒绝，文件名含账号显示名且过滤非法字符；
6. 验证请求必须使用 <code>Authorization: Bearer</code> 且不含
   <code>x-auth-token</code>，设备 ID、时区和 User-Agent 与宿主形状一致，不能退回 okhttp；
7. HTTP 429 是唯一可重试的 HTTP 校验结果，长 Retry-After 不立即重试；
8. 有界响应正文只读取一份，过大、畸形、缺 biz_code、过期 token 和账号 ID 不一致均拒绝。

真机检查按以下顺序进行：

1. 打开多账号页，当前账号应自动快照；点“导出账号”出现项目自绘勾选弹窗；
2. 单选导出后，TXT 是可重新解析的完整 JSON；多选后只包含勾选账号；
3. 用原文件导入：服务器返回外层和业务层成功后，账号出现在当前页面且不自动切号；
4. 把 token 改为随机值后导入：即使 HTTP 为 200，只要返回 code 40002 就必须拒绝，
   并确认 <code>dsmod_accounts.json</code> 没有任何变化；
5. 制造尾随文本、缺字段和一好一坏批次，均应在写盘前失败；
6. 导出、导入、切号、移除和首次免责声明均不得出现系统 AlertDialog；
7. 设置入口显示“帮助与问题”，功能条目包含最新能力，问题条目采用“为什么…… / 解决办法”；
8. 旧时间戳免责声明标记会触发一次新版内容；同意后冷启动不重复弹出。

### 9.9 地区登录入口与现代激活状态

自动测试至少覆盖：

1. 不可变的国内登录列表能生成新列表，Google 位于首项，原国内入口对象和顺序不变；
2. 已含 Google 的国际列表原样返回且不重复；
3. 空启动列表和混入非登录类型的列表保持不变；
4. 开关关闭时 hook 不修改任何参数。
5. 国际 <code>[Google,password,register]</code> 在联合开关打开后严格变为
   <code>[Google,WeChat,mobile,password,register]</code>；
6. 微信或手机号只缺一项时只补缺项，完整列表保持同一引用，国内一键登录顺序不变。

真机检查不得擅自退出用户账号，分为无损检查和用户确认两段：

1. 安装后完整冷启动，日志应出现
   <code>hooked native regional login options: cy4 ctors=1, copies=1</code>，且没有 hook 失败；
2. 设置页存在“解锁 Google 登录”开关；开启后私有标记文件存在，下一次冷启动日志显示
   <code>enabled=true</code>；
3. 在用户允许改变登录态后，从多账号页添加账号；国内登录页应同时显示原生 Google 与原有
   微信/手机号入口，日志只出现一次 <code>native Google login option injected</code>；
4. 点击 Google 后应打开系统/Google 账号选择界面，不得打开模块自制 OAuth WebView；取消后
   应安全回到登录页；
5. 服务器拒绝、设备无 Google Play 服务或用户取消时，不得写入伪账号槽，也不得记录 ID Token；
6. 关闭开关并完整重启后，CN 登录页恢复宿主原列表；非 CN 地区原生 Google 项不得被删除。
7. 海外环境开启联合开关并完整重启后，同时出现微信与短信手机号，Google/密码/注册不丢失；
8. 安装后打开模块启动页，官方 Xposed service 连接时显示“已启用”；启动 DeepSeek 后私有心跳更新并
   显示“已激活”。modern 作用域只勾选 DeepSeek 时检测仍应工作；
9. 停止 DeepSeek 后重开模块不能因暂时没有 Binder 回调直接声称“未激活”，应显示最近验证或待验证；
10. Deekseep 页滑到底部，模块版本、API、编译时间和 DeepSeek versionName/versionCode 与实际 APK 一致。

### 5.19 DeepSeek 本地 API：后台门控、Anthropic 与 CLI Agent

稳定 API 102 从 1.7-r12 起不再把功能名限定为“本地 OpenAI API”；1.7-r16 完成真实增量
Anthropic 工具流和只读复验，1.7-r19 又把原生生成收敛到单一公平 permit，并加入 Claude 客户端
会话隔离与活动事件。HTTP 与原生 transport 仍位于
目标进程内并以独立 Gateway Key 保护本机/可信局域网监听，但控制面增加两个必须一起保留的约束：

1. 首次进入使用模块自绘弹窗说明后台冻结风险；系统设置只用于修改 Android 电池策略。返回后用
   <code>PowerManager.isIgnoringBatteryOptimizations</code> 和
   <code>ActivityManager.isBackgroundRestricted</code> 自动复检。只有校验通过并写入私有 ready marker，
   enabled marker 才能真正启动监听；复检失败时停止现有网关。
2. OpenAI / Anthropic 是互斥的真实路由格式并持久化到私有文件。OpenAI base URL 以
   <code>/v1</code> 结尾；Anthropic 使用根 URL，开放 <code>/v1/messages</code> 与
   <code>/v1/messages/count_tokens</code>。错误格式返回 <code>protocol_mismatch</code>，不能让客户端
   用错误 SSE 解码器“碰运气”。

Anthropic 翻译必须覆盖 system 数组、text/thinking、客户端工具 schema、tool choice、
<code>tool_use</code>/<code>tool_result</code> 和 <code>is_error</code>。非流式返回 Message content block；
SSE 严格按 message_start、content_block_start/delta/stop、message_delta、message_stop 排列，工具参数
使用 input_json_delta。工具仍复用同一个结构化信封、一次修复和完成副作用去重，不在网关内执行。
thinking 签名是带 SHA-256 的本地签名，只服务本地回放，不能伪称 Anthropic 云端签名。

OpenAI SSE 在静默阶段每 5 秒写标准注释心跳；Anthropic 写标准 <code>ping</code> 后还要写
stop reason 为 null 的累计 usage <code>message_delta</code>。Claude Code 会在 UI 活动检测前过滤纯
ping，后一事件用于保持 spinner 和长思考 token 计数，终态 usage 不得小于流内活动峰值。写端断开
使用专门异常和 cancelled 计数，不得继续记为 DeepSeek 上游失败。已有部分 token 的请求绝不自动
重放。仓库中的
<code>scripts/deekseep-agent.py</code> 提供 argv prompt、交互多轮、<code>/think</code>、OpenAI/Anthropic
SSE 解析、Key 轮换重读和“仅无输出时重试”；<code>scripts/claude-deekseep</code> 只给子进程注入
ANTHROPIC_BASE_URL/AUTH_TOKEN，不把明文 Key 写进 shell 配置。

DeepSeek 服务端的 <code>parallel_chat_limit</code> 是账号级而非旧实现中的 lane 级。普通、主 Agent、
带工具 Task/子代理与辅助生成必须共享一个公平 <code>Semaphore(1)</code>；工具型请求先登记 waiter，
无工具标题/摘要必须在持有 permit 前等待并 pacing。隐藏 session key 继续区分 native model 与
workload，并附加 Claude <code>metadata.user_id</code> 的单向哈希。<code>/clear</code> 及其
<code>/new</code> 别名旋转 UUID 后必须进入新分支，原 metadata 不得记录或持久化。

工具型回复只有“我将读取/修改/执行”而未产生真实调用时，应在同一 deadline 内做一次最小工具修复；
正文为空而承诺只存在于 reasoning 末尾也要覆盖。<code>/init</code> 必须等到成功 Read/Write/Edit
<code>CLAUDE.md</code> 才允许最终结束，第二次仍不给可用工具时返回结构化错误，不能伪装完成。

真机定位确认“不限制电池”之后的 SSE 中断来自 Cached Apps Freezer，而不是 HTTP parser：宿主进入
cached adj 后 `/proc/.../wchan` 落在 freezer trap，连 `/healthz` 也同步超时。r12 因此增加
<code>LocalApiKeepAliveActivity</code> 和 <code>LocalApiKeepAliveService</code>。前者是用户操作触发、
无动画且立即返回的 deep-link 桥，后者是模块私有 <code>specialUse</code> 前台服务，持有局部唤醒锁并
每 5 秒向宿主发送带令牌的显式有序心跳。宿主 hook 在原 ShareResultReceiver 逻辑之前消费心跳、
确认 enabled/running，并在进程可重新启动时恢复已保存网关；90 秒无确认或 API 关闭时服务自停。
不得用关闭全局 freezer、杀系统进程或保活其他应用替代这条链路。

## 10. 日志定位

稳定构建的主要私有日志位于：

~~~text
/data/data/com.deepseek.chat/files/dsprobe.log
~~~

常用标记：

| 日志片段 | 含义 |
|---|---|
| <code>created blank conversation sid=</code> | 真正新建了一个空白会话 |
| <code>reused recent blank conversation sid=</code> | 重复调用被数据层幂等复用 |
| <code>hooked native regional login options</code> | 登录状态构造器/copy hook 数量及 Google、微信+手机号两个开关状态 |
| <code>native Google login option injected</code> | 国内登录方式列表已保留原项并插入宿主 Google 单例；不含任何 token |
| <code>native WeChat + mobile login options enabled</code> | 海外列表已补齐宿主微信与短信项；不含凭证 |
| <code>activation heartbeat accepted by module provider</code> | DeepSeek UID 已通过 Provider 校验并写入模块私有激活心跳 |
| <code>persisted image selection</code> | FILE fragment 已提交并刷新 sidecar |
| <code>excluded editor-local sessions from cloud prune</code> | 云目录只排除了编辑器 SID |
| <code>restored editor-local sessions into native state</code> | 延迟刷新后本地 <code>tp</code> 已补回真实 <code>ed0.e</code> 状态源 |
| <code>preserved local native sessions</code> | 侧栏服务器列表补入本地 <code>tp</code> |
| <code>preserved frozen conversation heads</code> | 云目录缺失 head 被本地有效 head 补齐 |
| <code>repaired frozen conversation heads</code> | 启动前从消息表修复无效 head |
| <code>frozen native hydration ... ok=true</code> | WCDB 行已装载到选中的原生会话 |
| <code>suppressed ... deleted ... editor-local</code> | 仅本地 SID 的业务码 1 被抑制 |
| <code>resolved local editor image uri=</code> | FileProvider URI 绕过了 HTTPS 拼接 |
| <code>preserved original response</code> | 实时替换前的原始 <code>kv</code> 已按 SID/message ID 保存 |
| <code>restored preserved responses in online history</code> | 冷启动 <code>pw0</code> 模板已在渲染前恢复 |
| <code>restored preserved responses before history write</code> | 模板行已在进入 WCDB 前恢复 |

公开 issue 前必须删去提示词、回复正文、SID、账号标识、图片名和数据库路径。调试用
真实会话探针、自动导航标记和高频 WCDB 日志在公开 1.7.1 构建前应关闭或改为显式
诊断开关，不能默认持续采集用户聊天内容。

## 11. 发布 1.7.1 前的仓库检查

- 两个稳定包均从 canonical 源码编译，legacy Hook API 转换正确；
- 测试版和 load probe 未进入 1.7.1 发布目录；
- 所有自动回归通过；
- 每个实际发布的变体都完成最小冷启动回归；
- README、FEATURES、ARCHITECTURE、VARIANTS 和 CHANGELOG 与真实能力一致；
- 版本名、versionCode、APK 文件名和校验和已更新；
- 没有提交 APK、反编译目录、数据库、日志、图片、临时 SID 文件或签名密钥；
- 诊断探针默认关闭；
- 安装与启动脚本按顺序等待，不会提前启动旧模块进程；
- 所有 root 命令只作用于明确目标文件或 <code>com.deepseek.chat</code>，不存在
  宽泛进程终止和数据删除。

## 12. 验收标准

1. 新建一次只产生一个会话。
2. 本地会话在云同步和冷启动后稳定存在，同时服务器新会话仍会同步进入。
3. 点击本地会话不会提示已删除，也不会进入空白页。
4. 相册图片选择完成即落库；随后追加 AI 不会覆盖图片。
5. 冷启动后 USER、图片和 AI 全部存在，缩略图和大图都可读取。
6. 系统提示词永远不出现在用户可见历史。
7. 编辑器每次打开都能看到宿主最新对话和最新消息，不再误报无聊天记录。
8. THINK、Markdown、FILE 和未知 fragment 在非目标编辑中保持无损。
9. 专家模式同一会话首轮和后续轮都能正确识别图片能力。
10. 服务器普通同步、服务器新建会话和服务器普通删除行为没有被本地保护逻辑破坏。
11. 账号导出可单选和多选，TXT 保留完整凭证和未知字段，文件名来自账号显示名。
12. 账号导入严格拒绝损坏 JSON、缺字段、重复 ID、过期 token 和任一账号验真失败；
    失败批次不写入任何候选凭证。
13. 导入成功只加入多账号列表，不自动切号；用户显式切换后只重启 DeepSeek 自身。
14. “帮助与问题”覆盖最新功能和常见提示的解决办法；新版风险弹窗为自绘界面且升级后只补看一次。
15. 开启 Google 解锁后，国内登录页保留原有入口并只新增一个宿主原生 Google 项；点击走
    Credential Manager 和官方接口，失败时不伪造账号、不泄露 ID Token。
16. 联合登录开关独立于 Google；海外登录页只补齐一个微信和一个短信手机号项，并继续走原生链路。
17. modern 启动页以官方 Xposed service + DeepSeek UID 心跳检测激活，不要求 self-scope，不再误报
    “未激活”；页面底部四项版本信息与安装包一致。
18. 本地 API 只监听回环地址并强制 Key；Chat/Responses 非流和 SSE、chunked body、结构化错误、
    深度思考和 SDK 调用全部通过。
19. Codex Responses provider 能完成工具调用、接收工具结果并继续生成；隐藏 API 会话不出现在普通
    列表，冷启动和无效 SID 均能自动恢复，真实 429 不产生重试风暴。
20. 首次后台校验失败时端口不监听；OpenAI 与 Anthropic 格式切换、Messages 普通/SSE、thinking、
    tool_use/tool_result、count_tokens、微型 Agent 和 Claude Code 客户端工具闭环全部通过，SSE 静默时
    有心跳，客户端主动断开不再污染失败统计。
21. Claude Code 长思考 token 与底部动画在十秒静默窗口后继续更新；应用退到后台后多步 Agent
    仍完成真实文件修改；<code>/clear</code>、<code>/new</code>、<code>/compact</code>、<code>/init</code>、
    <code>@文件</code>、输入行 <code>!</code> 和至少十五项当前 CLI 内部指令完成真机验收。

满足以上条件后，才可以把这一批实现标记为 GitHub 1.7.1 的完整聊天编辑器修复。
