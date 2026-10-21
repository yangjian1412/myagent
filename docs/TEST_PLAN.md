# MyAgent 测试计划

版本：`0.1.0beta4`　设备：小米 22127RK46C（`2944a134`，1440×3200 @560dpi，Android 15）；2608BPX34C（`0582645009983540`，1168×1712 @440dpi，Android 17）

---

## 1. 自动化测试

### 1.1 运行方式

```powershell
cd F:\aicode\mobile-agent
.\gradlew.bat :core:test :app:testDebugUnitTest --console=plain
```

结果在 `core/build/test-results/test/*.xml` 与 `app/build/test-results/testDebugUnitTest/*.xml`。统计用脚本读，别用 PowerShell 解析 XML：

```powershell
python -c "
import glob,xml.etree.ElementTree as ET
t=f=0
for p in glob.glob('core/build/test-results/test/*.xml'):
    r=ET.parse(p).getroot(); t+=int(r.get('tests') or 0); f+=int(r.get('failures') or 0)+int(r.get('errors') or 0)
print('core tests:',t,'failed:',f)
"
```

### 1.2 覆盖范围

| 测试类 | 覆盖点 |
|---|---|
| `SseParserTest` | 单/多 `data:` 帧、跨 chunk 拼接、尾随换行、注释行、`[DONE]` 兜底 |
| `ToolCallAccumulatorTest` | 流式工具调用的分片参数拼接、多工具并发下发、非法 JSON |
| `OpenAICompatProviderTest` | 请求体构造、`reasoning_effort` 映射、工具定义、错误码转事件 |
| `AnthropicProviderProtocolTest` | `thinking.budget_tokens` 与 `max_tokens` 联动、`tool_use` 解析、HTTP/SSE 错误 |
| `GeminiProviderProtocolTest` | `thinkingConfig` 映射、`functionCall` 解析、HTTP 错误 |
| `ContextEngineTest` | 预算裁剪顺序（中段优先压缩）、`pinned` 保护、摘要不吞最近轮次 |
| `AgentLoopTest` | 单工具闭环、多步工具链、工具异常回灌、非法参数自愈、最大步数、审批拒绝/通过、破坏性工具强制审批、事件逐个消费 |
| `BuiltinToolsTest` | 内置工具参数校验与错误处理 |
| `McpClientTest` | JSON-RPC 编解码、`tools/list` 解析、错误响应 |
| `ModelCatalogTest` | 三家 `/models` 解析、上限字段提取、OpenRouter 能力字段、错误转 `Failure` |
| `ModelPresetsTest`（含在上一类） | 按模型名猜上下文/输出/思考档位、未知模型返回 null |
| `CalendarToolsTest`（app） | 日历时间参数的 ISO / 时区 / 本地时间 / 日期 / epoch 秒与毫秒解析 |

当前结果：`:core:test` 114 项、`:app:testDebugUnitTest` 7 项，共 121 项，0 失败。

### 1.3 断言原则

- 协议层只断言"发出去的 JSON"和"收回来的事件"，不 mock 内部方法；
- 测试用固定字符串模拟服务端响应，便于回归时定位差异；
- 每个 bug 修复都要先补一条能复现的失败用例。

---

## 2. 真机手工测试

### 2.1 安装

```powershell
adb -s 2944a134 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 2944a134 shell am force-stop com.mynok.myagent
adb -s 2944a134 shell am start -n com.mynok.myagent/.MainActivity
```

截图必须先落到设备再 pull（PowerShell 会破坏二进制）：

```powershell
adb -s 2944a134 shell screencap -p /sdcard/s.png
adb -s 2944a134 pull /sdcard/s.png C:\Users\mynok\AppData\Local\Temp\opencode\shot.png
```

设备会休眠，先 `adb shell svc power stayon true`；若 `mWakefulness=Dozing` 截图为黑屏，需要人工点亮屏幕。

### 2.2 用例清单

#### A. 导航与主题

| 编号 | 步骤 | 期望 |
|---|---|---|
| A1 | 冷启动 | 进入对话页，底部三标签「对话 / 模型 / 设置」 |
| A2 | 设置 → 隐藏标签栏文字（默认开） | 底部只剩图标，重新进入仍生效 |
| A3 | 设置 → 主题模式切白天/夜间/跟随系统 | 立即换肤，无需重启 |
| A4 | 设置 → 主题颜色依次切换 5 种 | 顶栏、按钮、**底部标签栏**、**抽屉底色**都跟着变，不出现紫色 |
| A5 | 设置 → 跟随壁纸取色（Android 12+） | 配色取自壁纸；关闭后回到种子色 |
| A6 | 反复切换主题 ×10 | 无闪烁、无内存增长 |
| A7 | 设置 → Agent → 改名字为「小助手」后发消息 | 抓包看到系统提示开头为「你的名字是「小助手」。」；留空时回落为 MyAgent |
| A8 | 设置 → Agent → 偏好填「只回答一句话」后发消息 | 系统提示带上偏好内容；回复遵守该偏好；再改偏好无需重启即生效 |
| A9 | 连续快速输入 Agent 名字 / 偏好后立刻返回 | 文本不丢（350ms 落盘），重进设置页内容还在 |

#### B. 会话

| 编号 | 步骤 | 期望 |
|---|---|---|
| B1 | 对话页左滑出抽屉 | 列表按最后对话时间倒序，每条显示「刚刚 / 12 分钟前 / 15:30 / 昨天 / 09-28」 |
| B2 | 长按会话条目 | 弹删除确认；确认后条目消失 |
| B3 | 新建会话 → 发一条消息 | 自动出现在列表顶部并更新标题与时间 |
| B4 | 编辑已发送的用户消息 | 从该节点新开分支，旧回复仍可通过切换分支找回 |
| B5 | 重新生成助手消息 | 生成兄弟节点，可来回切换 |
| B6 | 杀掉进程重进 | 会话、分支、当前叶子节点都恢复 |

#### C. 模型配置

| 编号 | 步骤 | 期望 |
|---|---|---|
| C1 | 模型 → 模型配置 → 添加 | 填名称/协议/BaseURL/模型 ID/key 可保存 |
| C2 | 编辑内置种子模型 | 可改可存 |
| C3 | 删除任意模型（含内置） | 确认后消失，重启不复活 |
| C4 | 点「拉取模型列表」（需填好 BaseURL 与 key） | 出现下拉，列出服务端模型 |
| C5 | 从下拉选 Gemini 模型 | 自动填入上下文窗口与最大输出（Gemini 会返回上限） |
| C6 | 从下拉选 OpenAI 模型 | 上限按预设表填入，提示"已填入上限信息，可手改" |
| C7 | 选模型后改上下文窗口再保存 | 保存的是手改后的值 |
| C8 | 填错 key 点拉取 | 显示「拉取失败：HTTP 401…」，不崩溃 |
| C9 | 连接自检 | 正确识别三种协议并显示可用模型 |
| C10 | 编辑模型 → 勾选思考强度（极低/低/中/高） | 保存后在对话设置面板只出现勾选过的档位 |

#### D. 对话与思考过程

| 编号 | 步骤 | 期望 |
|---|---|---|
| D1 | 对话页点右上 `Tune` | 弹面板：换模型 / 思考强度 / 显示思考过程 / 显示工具调用 / 最大步数 / 上下文检查器 |
| D2 | 换一个不支持思考的模型 | 思考强度区提示"当前模型没有声明支持扩展思考" |
| D3 | 在支持思考的模型上选「高」再切到只支持 low/high 的模型 | 强度自动回落，不会发出对方不认识的参数 |
| D4 | 开启显示思考过程 → 触发带推理的模型 | 回复上方出现可折叠「思考过程」，流式时显示"思考中…" |
| D5 | 关闭显示思考过程 | 区块隐藏，但数据库里的推理内容不丢（重新打开可见） |
| D6 | 关闭显示工具调用 → 触发一次工具调用 | 工具结果卡片、调用 chip、实时轨迹全部隐藏，只看到最终回复；重新打开后历史记录仍在 |
| D7 | 选「关闭」思考强度 | 抓包确认请求体里没有 `reasoning_effort` / `thinking` / `thinkingConfig` |
| D8 | 最大步数拖到 4，发一个需要 6 轮工具的任务 | 第 4 轮后以「已达到最大执行步数（4）」失败 |
| D9 | 最大步数拖到 16 后重开该会话 | 仍是 16；新建会话恢复默认 8 |
| D10 | 上下文检查器 | 显示 token 占用、裁剪与摘要情况 |

#### E. 工具与安全

| 编号 | 步骤 | 期望 |
|---|---|---|
| E1 | 让模型读一个已授权目录里的文件 | 直接执行，不弹确认 |
| E2 | 让模型写文件（确认开关打开） | 先弹确认，拒绝后模型收到"用户拒绝" |
| E3 | 让模型删除文件 | **必然**弹确认，关闭开关也弹 |
| E4 | 工具页关掉某工具 | 模型再要求调用时返回未启用 |
| E5 | 访问未授权路径 | 返回沙盒错误，不越权 |
| E6 | 工具页「集成密钥」填 Notion token | 保存后徽标变「已配置」，`notion` 工具立即可用 |
| E7 | 让模型 `notion` action=search | 直接返回结果，不弹确认 |
| E8 | 让模型 `notion` action=create | 单独弹「新建页面」确认，拒绝后不发请求 |
| E9 | 让模型 `web_search` 不指定 provider | 走 Bing（cn.bing.com），国内网络可用 |
| E10 | `web_fetch` 一个被墙的地址 | 返回「域名解析失败/连接超时」的可读提示，而不是原始异常栈 |

#### F. 流式输出

| 编号 | 步骤 | 期望 |
|---|---|---|
| S1 | 发一句话，观察回复 | 文字**逐字**出现，不是等半天一次性蹦出 |
| S2 | 流式过程中点「停止」 | 立即停止，连接被取消（不是等读超时） |
| S3 | 配错 API Key 后发送 | 界面显示 HTTP 401 + 服务端错误描述，不闪退 |
| S4 | 工具调用多轮 | 每轮助手消息落库后流式气泡清空，不会出现文字重复 |

#### G. MCP / 技能

| 编号 | 步骤 | 期望 |
|---|---|---|
| F1 | 添加 MCP server 并连接 | 发现工具，出现在工具页与技能白名单 |
| F2 | 调用 MCP 工具 | 与内置工具一样走审批与风险分级 |
| F3 | 新建技能（提示词 + 白名单） | 对话页 chip 行出现，点一下启用 |
| F4 | 启用技能后提问 | 系统提示注入技能内容，工具调用被白名单约束 |
| F5 | 删除技能 | 确认后消失，已启用状态自动清除 |

#### H. 多模态

| 编号 | 步骤 | 期望 |
|---|---|---|
| H1 | 选支持识图的模型 → 发图 + 提问 | 图片以 base64/URL 正确编码，模型能描述内容 |
| H2 | 对不支持识图的模型发图 | 明确提示该模型不支持，或自动降级为仅文本 |
| H3 | 大图（>4MB） | 压缩后再上传，不 OOM |

#### I. 平板

```powershell
adb -s 2944a134 shell wm size 1600x2560
adb -s 2944a134 shell wm density 320
# 测完还原
adb -s 2944a134 shell wm size reset
adb -s 2944a134 shell wm density reset
```

| 编号 | 期望 |
|---|---|
| I1 | ≥840dp 走 `NavigationRail` + 常驻会话侧栏，无底部栏；600–840dp 仍用手机布局 |
| I2 | 隐藏标签文字在 Rail 上同样生效 |
| I3 | 旋转屏幕不丢草稿与分支状态 |
| I4 | 「模型」页三个子项改为左侧常驻列表（模型配置 / 工具 / 技能），选中高亮，点击切换右侧内容 |
| I5 | 状态栏与刘海不遮挡内容（手机顶部标题、平板内容都让开）；强制浅色/深色主题时状态栏图标始终清晰可见 |

#### J. 系统工具

| 编号 | 步骤 | 期望 |
|---|---|---|
| J1 | 让模型设一个 1 分钟后的闹钟 | 时钟应用出现该闹钟；不支持静默设置的 ROM 会打开预填页 |
| J2 | 让模型设 60 秒倒计时，再让它打开闹钟页 | 倒计时启动；闹钟页打开 |
| J3 | 说「把媒体音量调到 30%」 | 弹审批，确认后音量变化；`volume get` 不弹审批 |
| J4 | 说「打开手电筒」再关闭 | 闪光灯亮/灭；无闪光灯设备返回明确错误 |
| J5 | 未授予日历权限时让模型「新建明天 10 点的日程」 | 弹审批后打开系统日历预填页，用户保存后事件存在 |
| J6 | 授予日历权限后让模型列出日历、查未来 7 天日程 | 返回日历列表与日程；不弹审批 |
| J7 | 新建 / 修改 / 删除日程 | 写操作弹审批；删除必须确认；重复日程提示删除整个系列 |
| J8 | 授予联系人权限后搜「张」 | 返回匹配姓名与号码；未授权时提示去系统权限卡片 |
| J9 | 授予定位权限后问「我在哪」 | 返回经纬度、精度与来源；关闭定位服务时给出明确错误 |
| J10 | 有电话权限时让模型拨号 / 无权限时拨号 | 有权限直接拨出；无权限打开拨号界面并填入号码 |
| J11 | 让模型拍照 | 系统相机打开，拍完照片作为图片结果返回给视觉模型 |
| J12 | 授予麦克风权限后录 3 秒 | 生成 `.m4a` 文件，返回路径与大小；模型明确说明自己不能直接听 |
| J13 | 打开「模型 → 工具 → 系统权限」 | 显示日历/联系人/定位/电话/麦克风状态；授权后状态刷新；系统设置入口可用 |

---

## 3. 端到端（mock 服务）

本地起一个 OpenAI 兼容 mock（`C:\Users\mynok\AppData\Local\Temp\opencode\mock_model_server.py`），收到含 `[tool]` 的输入时返回 `tool_call`：

```powershell
python C:\Users\mynok\AppData\Local\Temp\opencode\mock_model_server.py
adb -s 2944a134 reverse tcp:18080 tcp:18080
```

模型 BaseURL 填 `http://127.0.0.1:18080/v1`，然后发含 `[tool]` 的消息，验证：
请求 → `tool_call` → 审批 → 工具执行 → 结果回灌 → 第二次请求 → 最终文本，全链路无重复调用。

---

## 4. 已知限制

| 项目 | 说明 |
|---|---|
| `adb shell input tap/text` | MIUI 未授予 `INJECT_EVENTS`，无法脚本点击，UI 交互只能人工或临时改默认页后截图 |
| `:app:connectedDebugAndroidTest` | 安装测试 APK 报 `INSTALL_FAILED_USER_RESTRICTED`，需在开发者选项里允许"通过 USB 安装"并关闭 MIUI 优化后才能跑 |
| 设备休眠 | 截图前确认 `mWakefulness=Awake`，否则是黑图 |
| 上下文/输出上限 | OpenAI、Anthropic 原生接口不返回，只能靠预设表或手填 |
| 思考档位 | 无官方"查询支持档位"接口，靠模型档案声明 + 预设表 |

---

## 5. 回归门槛

发布前必须满足：

1. `:core:test` 全绿（当前 108 项）；
2. `:app:assembleDebug` 无 error；
3. A1–A6、B1–B3、C1–C3、D1–D4、E2–E3、S1–S4 手工通过；
4. 冷启动无 `FATAL EXCEPTION`，`adb logcat` 中无 Room schema 校验失败。
