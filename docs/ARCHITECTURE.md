# MyAgent 架构说明

版本：`0.1.0beta2`　包名：`com.mynok.myagent`

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
| `core.model` | 模型档案、协议适配器、SSE 解析、思考强度、模型目录探测 |
| `core.agent` | `AgentLoop`：请求 → 工具调用 → 结果回灌 → 再请求的闭环 |
| `core.context` | 消息树、上下文裁剪与摘要 |
| `core.tools` | 工具抽象、注册表、风险分级、审批接口、内置工具 |
| `core.mcp` | 自研 MCP 客户端（JSON-RPC over stdio/HTTP、工具发现） |
| `core.net` | `Http` 抽象 + OkHttp 实现，便于测试替换 |

`core` 不引用任何 Android API，网络与文件系统都通过接口注入（`Http`、`FileWorkspace`、`ToolApprover`），所以测试里可以直接用 `FakeHttp` 驱动完整协议逻辑。

---

## 2. 模型层

### 2.1 模型档案

`ModelProfile` 描述"一个可用的模型"：

- 连接：`providerType`（`OPENAI_COMPAT` / `ANTHROPIC` / `GEMINI`）、`baseUrl`、`modelId`、`secretId`
- 能力：`supportsTools`、`supportsVision`、`supportsStreaming`
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
- Android 专有工具（通知、应用列表、剪贴板等）在 `app/tools/android`，不经 core 暴露。

### 4.4 MCP

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
| `model_profiles` | 模型档案（不含 key），`reasoningLevels` 为逗号分隔等级 |
| `skills` | 技能：名称、描述、提示词、可选工具白名单 |
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

### 6.2 设置

`SettingsStore`（DataStore）保存：`themeMode`、`themeColor`、`dynamicColor`、`autoTitle`、`hideTabLabels`、`agentName`、`agentPreferences`。
`MainActivity` 在最外层收集，主题变化即时生效。

`agentName` / `agentPreferences` 在每次发送前读取，拼到系统提示最前面（会话级提示与技能提示依次追加在后面），
因此改完设置后下一次发送即生效；名字留空时回落到 `MyAgent`。

---

## 7. UI 结构

### 7.1 导航

三个一级标签：**对话 / 模型 / 设置**。

- 手机（< 600dp）：底部 `NavigationBar` + `ModalNavigationDrawer` 历史抽屉（仅对话页可手势拉出）
- 平板（≥ 600dp）：`NavigationRail` + 常驻会话侧栏

对话页顶部：左上角汉堡按钮（历史）、右上角 `Tune`（对话设置）与新建。

### 7.2 对话设置面板

底部弹层 `ConversationSettingsSheet`：换模型、换思考强度（只列当前模型支持的档位）、是否展示思考过程、是否展示工具调用、上下文检查器入口。
关闭工具调用展示后，工具结果消息、调用 chip 与实时轨迹都不再渲染，只保留模型的最终回复。

### 7.3 主题

`Theme.kt` 用 HSL 从种子色派生整套配色，不引 material-color-utilities。除了 M3 基础角色外，还显式提供
`surfaceDim / surfaceBright / surfaceContainerLowest…Highest`——底部导航栏、抽屉、卡片都靠这些容器色，
缺了它们会退回 Material 默认紫，表现为"底色不跟主题色"。

### 7.4 断点

自定义断点：600dp（手机/平板）、840dp（平板/宽屏），不依赖 material3-adaptive。

---

## 8. 依赖注入

手写 DI，不引 Hilt。`AppContainer` 在 `Application` 里构建一次，持有数据库、仓库、HTTP、密钥存储、设置、审批协调器；ViewModel 通过 `ViewModelProvider.Factory` 从容器取依赖。

---

## 9. 关键取舍

| 取舍 | 原因 |
|---|---|
| `core` 纯 JVM | 协议逻辑可以脱离设备快速回归；目前 100+ 单测 |
| 自研 SSE / MCP | 需要精确控制流式增量与审批时机，第三方库封装过深 |
| 消息树而非线性列表 | 编辑与重生成必须保留旧分支，否则用户无法回溯 |
| 思考档位写在模型档案 | 各家档位不一致，全局枚举无法表达 |
| 上下文/输出上限"服务端优先 + 预设兜底 + 可手改" | 只有 Gemini/OpenRouter 会返回上限，其余只能猜 |
| 手写 DI | 模块少，Hilt 的构建成本高于收益 |
