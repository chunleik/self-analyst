# 隐私说明 / Privacy Notice

SelfAnalyst 是一个**在你本机运行**的自我分析工具。它会采集你的数字活动以提供洞察，
因此默认就有较高的系统权限。本文如实说明：**采集什么、存在哪里、什么会离开你的电脑、
发给谁、以及如何关闭。**

> 一句话总结：活动数据、OCR/转写结果、记忆都**默认只存本地**；只有当你向 Agent 提问、
> 或开启摘要/索引/搜索功能时，相关文本才会被发送到你**自己配置的** LLM 服务。
> 音频、文件监控**默认关闭**。

---

## 1. 数据存在哪里（默认全部本地）

| 数据 | 位置 | 说明 |
|------|------|------|
| 活动记录（窗口标题 / AFK 空闲） | `~/.self-analyst/aw-data/aw.db` | AW bucket 和事件存于同一个 SQLite 文件 |
| OCR / 屏幕内容文本 | 同上（aw-data） | 见下文 OCR 范围限制 |
| 音频转写文本 | 同上（aw-data） | 仅在开启音频时产生 |
| 成长记忆（目标 / 模式 / 改进记录） | `~/.self-analyst/memory/` | |
| 聊天正文（UI transcript） | `{memory.dir}/chat-sessions/` | `index.json` + 每会话分片，按服务端会话 ID 明文存储 |
| Agent 模型上下文 | `{memory.dir}/agent-state/self-analyst-chat/desktop/<sessionId>/` | 与聊天正文分开保存；按会话隔离，包含近期消息与滚动摘要，是模型执行历史的权威 |
| Wiki 摘要 + 语义索引 | `~/.self-analyst/` 下的 SQLite / Lucene 索引 | |
| API 密钥 | 环境变量或 `application.properties` | **不**随数据上传，仅用于调用你配置的服务 |

这些文件都在你本机，SelfAnalyst 自身不会把它们上传到任何中心化服务器——本项目没有
任何官方后端。

聊天正文分片与 AgentState 是两份用途不同的本地数据。对升级前已经存在于服务端分片、但
尚无 AgentState 的会话，下一次发送时会把当前问题之前的有效 user/assistant 历史**单次懒迁移**
到对应 AgentState；UI-only system、pending 和 error 消息不会导入。旧 WebView 会话数据不会迁移。

## 2. 本地服务

- 内置 HTTP 服务**仅监听 `127.0.0.1`**（回环地址），不对局域网/公网开放；
  CORS 仅允许 `localhost` / `127.0.0.1` 来源。
- 默认端口 `5700`，可用 `aw.port` 调整。
- 同机上的其它进程理论上可访问该端口——若你在多用户/不可信的机器上运行，请注意这一点
  （见 [SECURITY.md](SECURITY.md)）。

## 3. 什么会离开你的电脑（发给第三方）

以下内容**只有在对应功能开启时**，才会被发送到你在配置中指定的服务地址
（默认是 OpenAI，但 `*.base-url` 可改为任何兼容服务，包括本地自托管模型）：

| 出口 | 发送的内容 | 目的地（可配） | 默认 |
|------|-----------|---------------|------|
| Agent 对话 | 当前提问 + 同会话 AgentState 模型历史 + 本轮选择/检索的活动、任务或内容片段 | `llm.base-url` | 随提问触发 |
| AgentState 压缩 | 达到阈值的旧会话前缀，用于生成有界滚动摘要 | `llm.base-url` | 默认开启；仅达到 message/token 阈值时触发 |
| 会话摘要 | 该会话中的聊天正文（不含配置敏感值） | `llm.base-url` | 消息实质变化后异步触发；失败时本地确定性降级 |
| Wiki 摘要 | 时段内的活动标题 / OCR 文本片段 | `llm.base-url` | **开启**（`wiki.enabled=true`）|
| 语义索引 Embedding | 待索引文本 | `embedding.base-url` | **开启**（`embedding.enabled=true`）|
| 文件监控摘要 | **被监控目录的文件内容** | `llm.base-url` | **关闭**（`file.watch.enabled=false`）|
| 联网搜索 | 你的搜索查询 | `websearch.mcp-url`（默认 `search.parallel.ai`）| **关闭**（`websearch.enabled=false`），按需开启并配 key |

> ⚠️ **文件监控**是隐私敞口最大的功能：开启后，被监控目录里的文件正文会被发给 LLM
> 生成摘要。请只监控你确实希望被分析的目录。默认关闭。

**不会离开本机的：**
- 原始截图（OCR 后不外发，仅识别出的文本按上表规则处理）
- 原始音频（**转写完全由本地 whisper.cpp 完成**，音频本身不联网；仅转写出的文本可能
  在你向 Agent 提问时作为上下文被检索）

## 4. 采集范围的隐私设计

- **OCR 默认只读窗口顶部 80 像素**（`ocr.title-strip-height=80`）——只抓应用标题栏 /
  标签栏以识别"开着什么"，不读正文。设为更大值或 `0`（全窗口）会显著增加隐私暴露。
- **可排除应用**：`ocr.excluded.apps` 可按进程名跳过 OCR（如密码管理器、银行应用）。
- 活动追踪只记录**窗口标题**，不记录键盘输入内容。

## 5. 如何关闭各项采集

在 `application.properties` 或对应环境变量中设置：

```properties
aw.audio.enabled=false          # 关闭音频采集（默认即关）
file.watch.enabled=false        # 关闭文件内容监控（默认即关）
wiki.enabled=false              # 关闭 LLM 时段摘要
embedding.enabled=false         # 关闭语义索引 embedding
websearch.enabled=false         # 关闭联网搜索
ocr.title-strip-height=0        # 配合引擎设置可最小化/关闭 OCR 暴露
```

OCR 内容识别随启动开启；如完全不想截屏识别，可参考 `docs/specs/content.md` 关闭
`ContentWatcher`。

## 6. 删除你的数据

直接删除本机数据目录即可（默认 `~/.self-analyst/`，开发环境下为项目内 `./data/`）。
没有远程副本需要清理。

在 UI 删除聊天会话时，后端会在同一 Agent 生命周期 gate 中清除 ReActAgent cache、对应的
AgentState、会话分片和索引项；即使 LLM 未配置、Agent 没有初始化，也会直接清理磁盘上的
AgentState。若该 gate 正在处理聊天，请求返回 HTTP 409，以上数据均保持不变，避免只删除
一部分；稍后重试成功后才会全部删除。已经从会话中提取、并作为独立条目保存的长期记忆
不会被隐式删除，可在记忆面板中单独删除。

## 7. 你对第三方服务的责任

SelfAnalyst 把文本转发给**你自己配置**的 LLM / Embedding / 搜索服务。这些服务如何留存、
使用你的数据，受**它们各自的隐私政策**约束（例如 OpenAI 的数据使用条款）。若对隐私要求高，
可将 `*.base-url` 指向本地自托管模型，使数据完全不出本机。

---

*本说明描述软件的实际行为，随功能演进会更新。如发现与代码行为不符，请提 Issue。*
