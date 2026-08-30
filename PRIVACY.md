# 隐私说明

SelfAnalyst 是在本机运行的个人活动分析工具。它可能接触窗口标题、屏幕文字、音频、文件正文、
聊天记录和长期记忆。本文说明这些数据存在哪里、何时会发送给第三方，以及如何关闭相关功能。

> 默认情况下，活动数据库和记忆保存在本机；音频、文件监控、Wiki 摘要、Embedding 和联网搜索
> 默认关闭。向 Agent 提问或主动开启依赖远程模型的功能时，相应内容会发送到你配置的服务。

## 1. 本地数据

实际目录由 `config.toml`、环境变量和内置默认值共同决定。源码直接运行时，classpath 默认通常为
项目下的 `./data/`；安装脚本可使用用户目录。不要仅根据示例路径判断真实位置。

| 数据 | 位置 | 说明 |
|------|------|------|
| 活动、窗口、AFK、OCR 和转写事件 | `{aw.data-dir}/aw.db` | AW bucket 与事件使用单一 SQLite 数据库 |
| 用户配置 | `{memory.dir}/config.toml` | UTF-8 明文，可能包含 API key；旧 `config.properties` 首次迁移后保留为 `.bak` |
| 成长档案和长期记忆 | `{memory.dir}/memory.json` | 目标、模式、改进记录与长期记忆条目 |
| 聊天正文 | `{memory.dir}/chat-sessions/chat.db` | SQLite WAL 单库，是 UI transcript 的权威来源 |
| 聊天旧格式备份 | `{memory.dir}/chat-sessions/legacy/` | 首次迁移旧分片后保留，不再作为当前数据源 |
| Agent 模型上下文 | `{memory.dir}/agent-state/self-analyst-chat/desktop/<sessionId>/` | 按会话隔离的近期消息和滚动摘要 |
| Wiki 摘要 | `{memory.dir}/llm-wiki.db` | 仅启用 Wiki 后产生 |
| 本地语义索引 | 配置的 Wiki/File Lucene 目录 | 向量索引在本机，但生成向量可能调用远程 Embedding |
| 文件监控状态 | `{memory.dir}/file-watch.db` | 仅启用文件监控后产生 |

项目没有官方中心化后端。上述数据不会由 SelfAnalyst 自动上传到项目维护者的服务器。

## 2. 本地服务与访问控制

- 内置 HTTP 服务只监听 `127.0.0.1`，并校验 `Host` 和浏览器 `Origin` 必须指向回环地址。
- 桌面壳每次启动后端时会生成一个本次生命周期使用的随机 token；除 `/desktop/session` 外的 `/desktop/*` 路由要求
  `X-SelfAnalyst-Token` 或同值的 HttpOnly、SameSite=Strict cookie。
- 直接用 `java -jar` 启动、且没有提供 `SELF_ANALYST_DESKTOP_TOKEN` 时，桌面 API 不启用该 token
  验证，但仍只绑定回环地址。同机恶意进程仍可能访问本地服务。
- 默认端口是 `5700`，可通过用户级 `config.toml` 的 `aw.port` 修改；桌面壳通过启动握手获取实际端口。

## 3. 可能发送给第三方的数据

所有目的地都来自用户配置；`*.base-url` 可以指向云服务，也可以指向本地自托管服务。

| 功能 | 可能发送的数据 | 目的地 | 默认状态 |
|------|----------------|--------|----------|
| Agent 对话 | 当前问题、该会话模型历史、所选活动/任务/内容片段 | `llm.base-url` | 用户发起对话时 |
| AgentState 压缩 | 达到阈值的旧会话前缀 | `llm.base-url` | 压缩默认开启，达到阈值时触发 |
| 会话摘要与长期记忆提炼 | 会话正文及候选记忆上下文 | `llm.base-url` | 会话变化后异步触发；失败可降级 |
| Wiki 摘要 | 时间段内的窗口标题和可选内容片段 | `llm.base-url` | `wiki.enabled=false` |
| 文件摘要 | 被监控目录内的文件正文 | `llm.base-url` | `file.watch.enabled=false` |
| Embedding | 待建立语义索引的文本 | `embedding.base-url` | `embedding.enabled=false` |
| 联网搜索 | 搜索查询 | `websearch.mcp-url` | `websearch.enabled=false` |
| 云端语音转写 | 捕获到的 WAV 音频片段 | `llm.base-url/audio/transcriptions` | 音频总开关默认关闭；`cloud-asr` 或可用的 `auto` 会外发音频 |

`aw.audio.engine=local-whisper` 只在本机调用 whisper.cpp；`cloud-asr` 会发送音频；`auto` 优先使用
可用的云端 ASR，失败或不可用时再回退本地 whisper。因此，若要求音频绝不离开本机，必须同时
使用 `local-whisper` 并确认本地 whisper 可用，不能只依赖 `auto`。

## 4. 采集范围

- 窗口活动记录包含进程名和窗口标题，不记录键盘输入。
- UIA 通过本地 Rust accessibility sidecar 读取可访问性树；密码字段以安全标记处理并脱敏。
- 屏幕 OCR 默认关闭（`aw.ocr.engine=off`），此时内容采集不会为 OCR 截图。
- 显式启用 OCR 后，默认只识别窗口顶部 `ocr.title-strip-height=80` 像素；设为 `0` 会扩大到完整窗口。
- OCR 调试样本默认关闭；启用 `ocr.sample.enabled` 后会在本地保留截图，可能包含敏感正文。
- `ocr.excluded.apps` 可按进程名排除不应采集的应用。
- 音频可来自麦克风、Windows 系统回放或两者；总开关默认关闭。
- 文件监控会读取配置目录内的受支持文件正文，并可能将正文交给远程 LLM 生成摘要；只应配置明确允许分析的目录。

## 5. 关闭功能

在桌面配置编辑器的 `config.toml` 中设置：

```toml
[aw.audio]
enabled = false
engine = "local-whisper" # 即使以后启用音频，也禁止使用云端 ASR

[file.watch]
enabled = false

[wiki]
enabled = false

[embedding]
enabled = false

[websearch]
enabled = false

[aw.ocr]
engine = "off"

[ocr.sample]
enabled = false

[aw.collection]
content = false
```

## 6. 删除数据

退出应用后删除实际的 `{aw.data-dir}` 和 `{memory.dir}` 即可清除本地数据。配置可能把两者放在
不同目录，删除前请在 `config.toml` 或日志中确认路径。

在 UI 删除会话时，`chat.db` 的 `pending_deletions` 会先记录持久删除意图，再清除对应 AgentState
和聊天正文；启动时会继续完成中断的删除。由会话提炼后独立保存的长期记忆不会随会话自动删除，
需要在记忆界面单独删除。`chat-sessions/legacy/`、旧配置 `.bak` 和损坏数据库备份属于恢复材料，
应用不会自动清理，如不再需要可在退出后手动删除。

## 7. 第三方服务责任

远程 LLM、Embedding、搜索和云端 ASR 如何留存或使用数据，取决于对应服务的隐私政策。
SelfAnalyst 无法替你删除这些第三方已经接收的数据。对隐私要求较高时，应使用可信的本地服务、
关闭相应功能，并定期检查配置与本地数据目录。
