# MyAgent

Android 手机端的 AI Agent Harness：本地模型与在线模型自由切换，支持工具调用、MCP、多会话分支与多模态识图。

- 版本：`0.1.0beta2`
- 包名：`com.mynok.myagent`
- 最低系统：Android 8.0（API 26），目标 API 36，编译 API 37

> 个人项目，仍在 beta 阶段，接口和行为可能继续调整。

## 功能

**模型**
- 支持 OpenAI 兼容、Anthropic、Gemini 三类协议
- 本地 Ollama / LM Studio / llama.cpp / strata 等，只要兼容 OpenAI 协议即可接入
- 流式输出；思考过程可折叠，也可一键隐藏
- 思考强度按模型档案声明（低 / 中 / 高），不支持的档位不会出现在界面上

**Agent 与工具**
- 内置工具：`web_search`（Bing / DuckDuckGo / Tavily / Brave）、`web_fetch`、`http_request`、`notion`
- Android 工具：截图、剪贴板、文件读写与搜索（SAF 授权目录）、应用列表 / 启动 / 信息、打开链接、发送 Intent、设备信息
- MCP：通过 HTTP / SSE 接入 MCP server，工具自动注册
- 每个工具可单独启用、设置风险等级与是否每次确认；新建页面这类写操作会在执行前弹确认
- 技能（Skill）：预设系统提示 + 工具白名单，可在对话页快速切换

**会话**
- 多会话、历史抽屉、长按删除
- 消息树分支：编辑已发送消息 / 重新生成都会新开分支，旧回复仍可找回
- 上下文检查器：查看 token 占用、裁剪与摘要情况
- 会话级模型、思考强度、系统提示、思考与工具调用展示开关
- 手机底部导航 / 平板双栏布局

**设置**
- Agent 名字与长期偏好（会拼进每次请求的系统提示）
- 主题模式、主题色、跟随壁纸取色、隐藏标签栏文字、自动命名会话

**安全**
- 模型 API key 与第三方工具密钥使用 Android Keystore 的 AES/GCM 加密后存本地，明文不落库
- 允许明文 HTTP，仅用于连接局域网里的本地模型服务；界面会对 `http://` 端点给出提示

## 技术栈

| 模块 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.4.20、Jetpack Compose（BOM 2026.09.00）、Material 3 |
| 构建 | AGP 9.1.1、Gradle 9.3.1、KSP 2.3.12 |
| 存储 | Room 2.8.5、DataStore Preferences 1.2.1 |
| 网络 | OkHttp 4.12、kotlinx.serialization |
| 异步 | Kotlin Coroutines 1.11 |
| 测试 | JUnit 4、Turbine、MockWebServer、Robolectric |

`:core` 是纯 JVM 模块（Agent 循环、上下文引擎、模型协议、内置工具、MCP），`:app` 是 Android 层（Compose UI、Room、Keystore、系统工具）。依赖用手写容器注入，没有引入 Hilt。

## 项目结构

```
mobile-agent/
├── app/                      Android 应用
│   └── src/main/kotlin/com/mynok/myagent/
│       ├── data/             Room、DataStore、加密密钥存储
│       ├── di/               手写依赖容器
│       ├── tools/android/    截图、文件、应用等系统工具
│       └── ui/               对话 / 模型 / 工具 / 技能 / 设置
├── core/                     纯 JVM：AgentLoop、ContextEngine、模型适配、内置工具、MCP
│   └── src/test/kotlin/      单元测试
├── docs/
│   ├── ARCHITECTURE.md       架构与关键取舍
│   └── TEST_PLAN.md          手工验收清单与端到端测试方法
├── gradle/libs.versions.toml 版本目录
└── gradlew / gradlew.bat
```

## 构建

要求：

- Android Studio（支持 AGP 9.1 的版本）或命令行环境
- JDK 17
- Android SDK Platform 37、Build Tools

```bash
# Windows
.\gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

说明：

- `local.properties` 由 Android Studio 自动生成（指向本机 SDK），已在 `.gitignore` 中排除，不要提交
- Gradle 分发包默认走腾讯云镜像（`gradle/wrapper/gradle-wrapper.properties`）。境外网络或镜像不可用时，把 `distributionUrl` 换回 `https://services.gradle.org/distributions/...`
- Release 构建需要自备签名；仓库里没有任何签名文件

## 测试

```bash
./gradlew :core:test        # 纯 JVM 单元测试，113 项
```

端到端联调（工具调用 → 审批 → 回灌 → 最终回复）：

1. 启动本机 mock 模型服务，监听 `18080`
2. `adb reverse tcp:18080 tcp:18080`
3. 在模型页把 BaseURL 设为 `http://127.0.0.1:18080/v1`
4. 发送包含 `[tool]` 的消息，具体步骤见 `docs/TEST_PLAN.md`

## 使用

1. **模型** → 模型配置 → 新增：先填 BaseURL 和 API Key，刷新模型列表后选模型
2. **模型** → 工具：启用需要的工具、配置 MCP server、在「集成密钥」里填 Notion / Tavily / Brave 的 key、授权文件目录
3. **设置** → Agent：设置名字与偏好；偏好会附加到每次请求的系统提示
4. 回到对话页开始聊天，右上角 `Tune` 可以换模型、调思考强度、隐藏思考过程与工具调用

## 隐私

- API key 只以 Keystore 加密后的密文形式保存在本机，不上传任何服务器
- 对话与设置只存在本机 Room / DataStore 中
- 请求直接发往你配置的模型服务和工具服务，项目本身没有后端

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [测试计划](docs/TEST_PLAN.md)

## License

[MIT](LICENSE) © 2026 yangjian1412
