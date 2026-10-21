# MyAgent 架构说明

版本：`0.1.0`　包名：`com.mynok.myagent`

手机端 AI Agent harness。核心目标：**本地模型与在线模型可自由切换**，具备工具调用、MCP 接入、会话分支、多模态识图能力，并尽量把可测逻辑放进纯 JVM 模块。

---

## 1. 模块划分

```
mobile-agent/
├── core/        纯 Kotlin JVM 模块（无 Android 依赖，可 JVM 单测）
└── app/         Android 应用（Compose + Room + DataStore）
```

### core

| 包 | 职责 |
|---|---|
| `core.model` | 模型档案、协议适配器、SSE 解析、思考强度、模型目录探测、附件内容分流（`ContentParts`） |
| `core.agent` | `AgentLoop`：请求 → 工具调用 → 结果回灌 → 再请求的闭环 |
| `core.context` | 消息树、上下文裁剪与摘要 |
| `core.tools` | 工具抽象、注册表、风险分级、审批接口、内置工具 |
| `core.mcp` | 自研 MCP 客户端（JSON-RPC over stdio/HTTP、工具发现） |
| `core.net` | `Http` 抽象 + OkHttp 实现，便于测试替换 |
| `core.skills` | `SKILL.md` 解析（kaml）、技能目录渲染 |
| `core.schedule` | 5 段 cron 表达式解析与下次触发时间计算 |

`core` 不引用任何 Android API，网络与文件系统都通过接口注入（`Http`、`FileWorkspace`、`ToolApprover`），所以测试里可以直接用 `FakeHttp` 驱动完整协议逻辑。

---

## 2. 模型层

### 2.1 模型档案

`ModelProfile` 描述"一个可用的模型"：

- 连接：`providerType`（`OPENAI_COMPAT` / `ANTHROPIC` / `GEMINI`）、`baseUrl`、`modelId`、`secretId`
- 能力：`supportsTools`、`supportsVision`、`supportsFileInput`、`supportsStreaming`
- 限制：`contextWindow`、`maxOutputTokens`
- 采样：`temperature`、`topP`、`extraBody`（原样透传的额外字段）
- 思考：`reasoningEfforts`（该模型支持的强度等级列表）

API key 不落库：`ModelProfileEntity.secretId` 指向 `SecretStore`，由 Android Keystore 里的 AES-GCM 密钥加密保存。

### 2.2 协议适配器

`ModelProvider` 是统一接口，三个实现各自处理请求体、SSE 事件与工具调用格式：

| 实现 | 思考参数 | 工具调用 |
|---|---|---|
| `OpenAICompatProvider` | `reasoning_effort` | `tools` / `tool_calls` |
| `AnthropicProvider` | `thinking.budget_tokens`（自动抬高 `max_tokens`） | `tools` / `tool_use` |
| `GeminiProvider` | `generationConfig.thinkingConfig.thinkingBudget` | `functionDeclarations` / `functionCall` |

`SseParser` 负责 `data:` 帧切分（含跨 chunk 拼接、尾随换行、`[DONE]`），`ToolCallAccumulator` 负责把流式分片的工具调用参数拼回完整 JSON。

**增量输出**：`Http.streamText` 的 `onChunk` 是同步回调，不能在里面 `emit`。`streamSseEvents()` 把「读协程」和「解析协程」拆开——读协程只往无界通道塞原始文本，解析协程逐条 `emit`，所以界面是逐字出字，而不是等整段响应结束再一次性蹦出来。非 2xx 的响应体不进解析器，直接作为错误信息返回；用户点「停止」时会 cancel 掉 OkHttp 连接，避免阻塞读拖到超时。

三个协议适配器都用这条路径，错误统一以 `ModelEvent.Failed` 收尾（而不是抛异常），否则 `AgentLoop` 拿不到收尾事件会一直挂着。

### 2.3 思考强度

`ReasoningEffort` 是**归一化枚举**：`OFF / MINIMAL / LOW / MEDIUM / HIGH`。
各家能力不同（OpenAI 有 minimal，o 系列只有 low/high，Gemini 2.5 Pro 甚至不能关闭），因此：

- 档位集合由**模型档案**声明，而不是全局固定；
- 对话页只展示当前模型声明支持的档位；换模型时若当前档位不被支持，自动回落为 `OFF`；
- `OFF` 表示**不发送任何思考字段**，对本地小模型完全透明。

### 2.4 模型目录探测（`ModelCatalog`）

"这个模型上下文多大、支不支持思考"没有统一接口，因此分三层处理：

1. **服务端能给的就要**（`ModelCatalog`）：
   - OpenAI 兼容 `GET {base}/v1/models` → 仅 id
   - Anthropic `GET /v1/models` → id + 展示名
   - Gemini `GET /v1beta/models` → 额外有 `inputTokenLimit` / `outputTokenLimit`
   - OpenRouter `GET /api/v1/models` → 额外有 `context_length`、`top_provider.max_completion_tokens`、`architecture.input_modalities`、`supported_parameters`
2. **服务端不给的按模型名猜**（`ModelPresets`）：`gpt-5*`、`o3/o4`、`claude*`、`gemini-2.5-*`、`deepseek-r1`、`qwen*` 等有预设表，填进去后用户仍可改。
3. **兜底**：界面上的上下文窗口 / 最大输出始终可手填，保存的是用户看到的值。

### 2.5 附件的编码

图片早已有之，这一版加了文件。规则是**按内容分流，而不是一律按二进制塞**：

| 附件 | 编码 | 谁能读 |
|---|---|---|
| 文本类（代码、配置、Markdown…） | base64 解回文本，发普通文本块 | 全都行 |
| PDF | Gemini `inlineData` / Anthropic `document` / OpenAI 兼容 `file` | 需模型档案声明支持 |
| 其他二进制 | 同上，发不出去时降级成一句说明文本 | 看各家 |

几个决定：

- **文本文件转文本**由 `ContentParts.asTextContent()` 统一决定，三个适配器共用一份判断，不会出现「同一条消息在 Anthropic 那转了、Gemini 那没转」。
- 判断顺序是**文件名与扩展名优先、MIME 兜底**：内容管理器给的类型经常是 `application/octet-stream`，等于没说。判错了也不要紧，`asTextContent()` 会按实际字节再确认一次（解不出 UTF-8 就退回文档格式）。
- 附件在库里存的是 `ContentPart.File`（原始字节 + 文件名 + MIME），**转文本只发生在发请求的那一刻**。这样消息气泡显示的是文件名而不是被摊开的正文，而且下一轮从历史里重新发出去时结果一致——不会出现「第一轮发文本、第二轮发文档」的抖动。
- 二进制要不要发出去由模型档案的 `supportsFileInput` 决定。默认关：本地推理服务收到 `type: "file"` 会直接 400，与其报一个看不懂的错，不如发送前就拦住并提示换模型。Gemini / Claude / OpenAI 官方的预设里是开着的。
- Gemini 的 `inlineData` 只认有限的 MIME 白名单，白名单外的类型直接发会被拒，所以降级成一句「这个附件没发出去」的说明文本，让模型知道少了东西。

---

## 3. Agent 循环

`AgentLoop` 把一次用户输入跑成事件流（`Flow<AgentEvent>`）：

```
用户消息
  → 组装上下文（ContextEngine）
  → provider.stream()                       ← 文本/推理增量
  → 若出现 tool_call：
      ToolRegistry 查找 → 风险检查 → ToolApprover 审批
      → 执行 → ToolResult 回灌为 tool 消息
      → 再次请求（直到无 tool_call 或达到最大步数）
  → 落库（助手消息 + 推理内容 + 工具轨迹）
```

要点：

- 工具结果**必须**回灌给下一轮请求，否则模型会重复调用同一工具；
- 工具异常被转成错误型 `ToolResult`，不中断整轮对话；
- 模型返回非法 JSON 参数时，错误信息作为工具结果回灌，让模型自我修正。

---

## 4. 工具与安全

### 4.1 风险分级

| 级别 | 示例 | 审批 |
|---|---|---|
| `READ_ONLY` | 读文件、HTTP GET、搜索 | 不审批 |
| `WRITE` | 写文件、创建目录 | 默认开启确认，可在工具页关闭 |
| `DESTRUCTIVE` | 删除文件 | **强制**审批，不可关闭 |

`ToolApprover` 由 app 层实现（`ApprovalCoordinator`），界面弹确认框，用户拒绝时工具返回"用户拒绝执行"。

**合并工具里的动态审批**：`notion` 是一个工具、三种 action。搜索/读页面是只读，不弹窗；`action=create` 是写操作，由工具内部调用 `ctx.approver.request(...)` 单独确认——这样搜索不会被写操作的审批策略连累。

### 4.2 第三方密钥

`ToolSecrets` 是 core 定义的读取接口（`suspend fun get(name)`），app 层用加密的 `SecretStore` 实现。工具在**每次执行时**现取 token，不在启动时快照，所以在「工具 → 集成密钥」里改完立即生效。目前可配置：Notion Integration Token、Tavily API Key、Brave Search API Key。

内置联网工具：`web_search` 默认走 `cn.bing.com`（国内可直连，DuckDuckGo 在大陆基本不可用），也可切 tavily / brave；`web_fetch` 直接抓取给定 URL，会把 DNS 解析失败、超时、TLS 错误翻译成人话，方便模型判断是「被墙」还是「站点 404」。

### 4.3 沙盒

- 文件工具只能通过 `FileWorkspace` 访问用户通过 SAF 授权的目录（`SafFileWorkspace`），不支持任意路径；
- HTTP 工具受 `ToolPolicyStore` 的域名/开关约束；
- Android 专有工具（应用列表、剪贴板、日历、联系人、定位等）在 `app/tools/android`，不经 core 暴露。

### 4.4 系统工具与权限

| 工具 | 权限 | 说明 |
|---|---|---|
| `alarm` | 无 | 闹钟 / 倒计时 / 打开闹钟页，走 `AlarmClock` Intent |
| `volume` / `torch` | 无 | 读音量不审批，写音量与手电筒动态审批 |
| `call` | `CALL_PHONE` | 没有权限时降级为 `ACTION_DIAL`，只打开拨号界面 |
| `take_photo` | 无 | 走系统相机 `ACTION_IMAGE_CAPTURE`；`FileProvider` + `MediaCaptureCoordinator` 等 Activity 结果 |
| `record_audio` | `RECORD_AUDIO` | `MediaRecorder` 录 1–60 秒，存 App 专属外部目录；模型不能直接听 |
| `calendar` | `READ_CALENDAR` / `WRITE_CALENDAR` | `list_*` 只读；create/update/delete 由工具内部动态审批；无写权限时 create 降级为系统日历预填页 |
| `contacts` | `READ_CONTACTS` | 只按姓名/号码搜索，不导出整个通讯录 |
| `location` | `ACCESS_FINE_LOCATION` / `COARSE` | 一次性定位，GPS → 网络 → 最近已知位置逐级降级 |

权限统一在「模型 → 工具 → 系统权限」卡片里申请；工具只检查权限并返回「去这里授权」的提示。类别三的能力（通知读取、无障碍模拟点击、修改系统设置）不实现。日历、联系人、位置数据会随工具结果发给当前模型，卡片里有明确提示。

### 4.5 MCP

只做**客户端**：`McpClient` 连接远端 server，`tools/list` 拿到工具定义后包成 `McpTool` 注册进 `ToolRegistry`，与内置工具同构。因此审批、风险分级、工具页开关对 MCP 工具自动生效。

---

## 5. 会话与上下文

### 5.1 消息树

`MessageEntity.parentId` 把消息组织成树，`MessageTree` 负责：

- 取当前分支（根 → 叶子）；
- 编辑用户消息后从该节点**另开分支**，旧分支保留；
- 重新生成助手消息同样生成兄弟节点；
- `ConversationEntity.activeLeafId` 记住"当前看的是哪条分支"。

### 5.2 上下文化

`ContextEngine` 在请求前裁剪历史：

1. 保留系统提示与最近若干轮；
2. 超预算时**先压缩中段**为摘要，而不是丢最近的对话；
3. `pinned` 消息（用户固定的）永不丢弃；
4. token 估算由 `TokenCounter` 提供（按字符/词近似，不依赖 tokenizer）。

---

## 6. 数据层

### 6.1 Room

| 表 | 说明 |
|---|---|
| `conversations` | 标题、模型、系统提示、思考强度/展示、工具调用展示、`activeLeafId`、`updatedAt` |
| `messages` | `parentId` 树结构、角色、正文、`reasoning`、工具轨迹、附件 |
| `model_profiles` | 模型档案（不含 key），`reasoningLevels` 为逗号分隔等级，`supportsFileInput` 表示能否原样发文件 |
| `skills` | 技能**索引**：内容以文件为准，这里存 frontmatter 元数据、启用开关、排序、来源路径 |
| `scheduled_tasks` | 定时任务：cron、动作类型与参数、白名单、专属会话、上次/下次执行 |
| `mcp_servers` | MCP server 配置 |
| `tool_policies` | 每个工具的启用状态与确认策略 |

迁移历史：

| 版本 | 变更 |
|---|---|
| 1 | 初始表结构 |
| 2 | 新增 `skills` |
| 3 | 会话加 `reasoningEffort` / `showReasoning`，消息加 `reasoning` |
| 4 | 模型档案加 `reasoningLevels`（幂等迁移，兼容已升到 v3 的库） |
| 5 | 会话加 `showToolCalls`（幂等迁移，默认显示，老会话行为不变） |
| 6 | 会话加 `maxSteps`（单次回复最大工具轮数，默认 8，范围 4–32） |
| 7 | `skills` 重建为文件索引表；新增 `scheduled_tasks`。会话/消息原样保留 |
| 8 | 模型档案加 `supportsFileInput`（老档案一律按「不支持文档输入」起步，避免发出会被 400 拒掉的请求） |

### 6.2 设置

`SettingsStore`（DataStore）保存：`themeMode`、`themeColor`、`dynamicColor`、`autoTitle`、`hideTabLabels`、`agentName`、`agentPreferences`。
`MainActivity` 在最外层收集，主题变化即时生效。

`agentName` / `agentPreferences` 在每次发送前读取，拼到系统提示最前面（会话级提示与技能提示依次追加在后面），
因此改完设置后下一次发送即生效；名字留空时回落到 `MyAgent`。

---

## 7. 技能（文件化）

技能不再是数据库里的一段提示词，而是**磁盘上的文件**，格式兼容 [Agent Skills](https://agentskills.io) 与 ClawHub：

```
<授权目录>/skills/
  morning-brief/
    SKILL.md          # 必需：YAML frontmatter + Markdown 正文
    references/       # 可选：参考资料
    assets/           # 可选：素材
```

- **目录授权**：`ACTION_OPEN_DOCUMENT_TREE` 授权一次，`takePersistableUriPermission` 持久化；App 在目录下建 `skills/` 子目录。之后用户可以直接用手机文件管理器增删改。
- **扫描**：`SkillFileStore.scan()` 递归找含 `SKILL.md` 的目录（最多 3 层，支持分组）。App 启动和用户点「重新扫描」时执行，结果同步到 `skills` 表作为索引。
- **解析**：`core/skills/SkillParser` 用 kaml 解析 frontmatter。未知字段忽略，YAML 语法错误只记 warning 并保留正文，不整份丢弃。
- **三层披露**
  1. 元数据层：`SkillCatalog.render()` 生成 `<available_skills>` 块，只有名字 + 一句话说明，有字符预算，超了先砍说明再砍条目。
  2. 正文层：模型调 `skill_read` 工具按名字取正文。
  3. 资源层：`references/`、`assets/` 里的文件按需读取。
- **手动 chip 仍可用**：对话页点选的技能正文会直接拼进系统提示（在目录块之后），优先级最高。
- **模型写技能**：`skill_write` 工具校验名字（小写字母/数字/连字符，1–64 位）后写 `SKILL.md`，再触发一次扫描。
- **ClawHub 字段映射**：`metadata.openclaw.requires.env` → 提示需要哪个集成密钥；`os` 不含 `android` → 标记平台不匹配；`requires.bins` / `install` / `scripts` → 标记不适用（手机上不能执行命令行）。

`skills` 表只是索引：停用、排序、来源目录、最近扫描时间。文件被删掉后重扫即消失。

---

## 8. 定时任务

任务 = **cron 表达式** + **动作**。

- **解析**：`core/schedule/CronExpression` 自己实现 5 段 Unix cron（`*`、`a-b`、`a,b`、`*/n`、月份/星期英文缩写）。日与周同时限定时取并集（标准 cron 语义）。找不到下一次返回 null（如 2 月 30 日），不会死循环。
- **唤醒**：`AlarmManager.setExactAndAllowWhileIdle`。Android 12+ 需要 `SCHEDULE_EXACT_ALARM`（appop，用户去系统设置开）；拿不到就退化为 `setAndAllowWhileIdle`，晚几分钟但不会不跑。
- **执行链路**：闹钟 → `TaskAlarmReceiver`（BroadcastReceiver 只有几秒，不干重活）→ `TaskRunService`（前台服务，`dataSync` 类型）→ `TaskRunner` → 结果通知。
- **三种动作**
  - `NOTIFY`：直接发通知，正文支持 `{date}` / `{time}` / `{weekday}` 模板
  - `TOOL`：预填参数调一个工具
  - `AGENT`：用指定技能跑一次完整 AgentLoop，结果写进任务专属会话
- **无人值守的审批**：建任务时勾选的工具组成白名单，白名单内的写操作视为已批准；`DESTRUCTIVE` 无论怎么勾都拒绝；白名单外的工具直接拒绝。因为后台没人能点确认框。
- **专属会话**：`AGENT` 类型第一次执行时创建一个会话并写回 `conversationId`，之后每次都追加到同一个会话；通知点击带上 `EXTRA_CONVERSATION_ID` 直接跳进去。
- **重排**：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 后 `BootReceiver` 重新登记所有启用的任务（系统重启会清空 AlarmManager）。App 被强制停止后任务停摆，下次启动重排。
- **模型不能管理任务**：定时任务只在 UI 里配置，不开放工具给模型，避免模型给自己排后台任务。

---

## 9. UI 结构

### 9.1 导航

三个一级标签：**对话 / 模型 / 设置**。

- 手机/窄窗口（< 840dp）：底部 `NavigationBar` + `ModalNavigationDrawer` 历史抽屉（仅对话页可手势拉出）
- 平板/宽窗口（≥ 840dp）：`NavigationRail` + 常驻会话侧栏；「模型」页的四个子项（模型配置 / 工具 / 技能 / 定时任务）也改为左侧常驻列表，交互与历史对话一致
- 手机上的模型子标签用短名（配置 / 工具 / 技能 / 定时），四个全称放不下
- 全窗口 edge-to-edge：手机分支由 `Scaffold` 的 content insets 让开系统栏，平板分支显式用 `WindowInsets.systemBars ∪ displayCutout` 处理；状态栏/导航栏图标明暗跟随 App 内主题，而不是系统深色模式

对话页顶部：左上角汉堡按钮（历史）、右上角 `Tune`（对话设置）与新建。

### 9.2 消息渲染与输入区

- 气泡正文走 `MarkdownishText`：只认代码块、换行与网址，不为了这点需求引完整 Markdown 渲染库
- 网址用 `LinkAnnotation.Url` 标成可点链接，点击交给 `LocalUriHandler`（浏览器、地图、拨号盘都算）。模型写出来的网址不一定规矩，所以结尾的中英文标点不算网址的一部分，打不开也只提示、不抛异常
- 附件在气泡里是「文件名 + 类型 + 体积」的占位条（图片也一样），不引图片加载库
- 输入区两个按钮：加图片、加文件；附件 chip 点一下即移除；只有附件没打字时也能发，会话标题用文件名兜底

### 9.3 对话设置面板

底部弹层 `ConversationSettingsSheet`：换模型、换思考强度（只列当前模型支持的档位）、是否展示思考过程、是否展示工具调用、最大步数（4–32）、上下文检查器入口。
关闭工具调用展示后，工具结果消息、调用 chip 与实时轨迹都不再渲染，只保留模型的最终回复。
最大步数是会话级的单次回复工具轮数上限，默认 8；调高能跑更长的工具链，代价是更多 token 与更慢的返回。

### 9.4 主题

`Theme.kt` 用 HSL 从种子色派生整套配色，不引 material-color-utilities。除了 M3 基础角色外，还显式提供
`surfaceDim / surfaceBright / surfaceContainerLowest…Highest`——底部导航栏、抽屉、卡片都靠这些容器色，
缺了它们会退回 Material 默认紫，表现为"底色不跟主题色"。

### 9.5 断点

自定义断点：600dp（手机/平板）、840dp（平板/宽屏），不依赖 material3-adaptive。

---

## 10. 依赖注入

手写 DI，不引 Hilt。`AppContainer` 在 `Application` 里构建一次，持有数据库、仓库、HTTP、密钥存储、设置、审批协调器；ViewModel 通过 `ViewModelProvider.Factory` 从容器取依赖。

---

## 11. 关键取舍

| 取舍 | 原因 |
|---|---|
| `core` 纯 JVM | 协议逻辑、cron、技能解析都能脱离设备快速回归；目前 `:core:test` 182 项、`:app:testDebugUnitTest` 14 项 |
| 自研 SSE / MCP | 需要精确控制流式增量与审批时机，第三方库封装过深 |
| 消息树而非线性列表 | 编辑与重生成必须保留旧分支，否则用户无法回溯 |
| 思考档位写在模型档案 | 各家档位不一致，全局枚举无法表达 |
| 上下文/输出上限"服务端优先 + 预设兜底 + 可手改" | 只有 Gemini/OpenRouter 会返回上限，其余只能猜 |
| 手写 DI | 模块少，Hilt 的构建成本高于收益 |
| 技能用文件而非数据库 | 用户能直接用文件管理器改，也能拷进来别人写好的技能；数据库只做索引 |
| 自研 cron 解析 | 5 段表达式的语义很固定，引库反而增加体积和升级风险 |
| 定时任务用 AlarmManager 而非 WorkManager | 需要精确到分钟；WorkManager 有最小间隔与系统调度抖动 |
| 后台审批用白名单预授权 | 无人值守时弹不出确认框；破坏性操作仍然一律拒绝 |
| 文本附件转文本、PDF 原样发 | 一律按二进制塞既费 token 又容易读不准，一律抽文本又没地方放 PDF；按内容分流各取其长 |
| 附件按 base64 存进消息 | 与图片同一套做法，重发历史时不必再去找那个文件；代价是大附件会撑大库 |
