# SelfAnalyst

[官网](https://chunleik.github.io/self-analyst/zh-CN/) · [官网预览与发布说明](docs/website.md)

[English](README.md) | **简体中文**

SelfAnalyst 是一个本地优先的个人活动分析工具。它记录前台应用、窗口标题和活动时间，
帮助你回顾一天的工作；配置大模型后，还可以总结活动、归纳工作内容，并通过对话继续追问。

![SelfAnalyst 桌面端会话页运行截图](docs/mockups/desktop-chat-screenshot.png)

> 上图是桌面端会话页的实际运行截图，展示会话列表、对话区，以及包含当前状态、最近活动、
> 可生成待办、长期记忆与上下文选项的右侧面板。

## 快速开始

当前提供 **Windows 10/11** 桌面版，尚未提供 macOS 安装包。

1. 前往 [GitHub Releases](https://github.com/chunleik/self-analyst/releases)，下载安装包或免安装 ZIP。
2. 完成安装，或解压 ZIP 后运行 `SelfAnalyst.exe`。发布包自带 Java 运行环境，无需安装下方开发工具。
3. 打开右上角配置入口，在“模型设置”中填写服务地址、API Key 和模型，点击“保存并应用”。详见[模型设置指南](docs/llm-settings.md)。
4. 在“看板”回顾活动，在“会话”中提问，例如“今天主要做了哪些工作？”。

未配置模型时也能启动本地服务；聊天和模型摘要需要可用的模型连接。活动数据保存在本机，
调用模型时会将所需输入发送到你配置的服务端，详见[隐私说明](PRIVACY.md)。
关闭窗口后应用继续在系统托盘运行；完全退出请使用托盘菜单。帮助菜单提供使用指南、反馈和手动检查更新入口。

## 主要功能

- **活动回顾**：通过看板时间轴查看当前、今天和历史活动，并带着条目上下文继续追问。
- **图片提问**：在会话中选择或粘贴 PNG/JPEG 图片，交给支持图片输入的模型分析；每轮最多 4 张、每张不超过 5 MiB 和 2000 万像素。图片随会话保存，发送时交给所配置的模型，不启用后台截屏。
- **生成文件**：让助手生成表格、文档、演示文稿或 HTML/SVG，通过会话文件卡片保存或下载。
- **长期记忆**：自动筛选有长期价值的信息，也可以在会话中要求更正或忘记。

## 开发环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+（桌面端开发）
- Rust stable 与 Cargo（Tauri 壳和 accessibility sidecar）
- Windows 10/11（当前完整桌面体验）

## 构建与测试

```powershell
mvn test
mvn package -DskipTests
cargo test --manifest-path self-analyst-axsidecar/Cargo.toml
```

桌面端开发：

```powershell
Set-Location self-analyst-desktop
pnpm install
pnpm tauri dev
```

构建本机发布目录：

```powershell
.\scripts\build-dist.ps1
```

构建包含 jlink JRE 的单一便携包：

```powershell
.\scripts\build-portable.ps1
```

构建包含同一后端与 jlink JRE 的 NSIS 安装包：

```powershell
.\scripts\build-installer.ps1
```

输出位于 `artifacts/`，并为 ZIP 和安装包生成 `.sha256` 文件。
安装版与免安装 ZIP 默认将运行数据放在 `%LOCALAPPDATA%\com.selfanalyst.desktop`，升级或换解压位置继续使用同一目录。
免安装包只有在 EXE 同级存在 `portable.marker` 普通文件时，才使用程序旁的 `data/`。
无格式标记的已有兼容数据经只读检查后原地补标记，不自动搬运其他目录数据。详见[运行数据指南](docs/runtime-storage.md)。

## 界面语言

当前提供中文和 English，默认跟随系统语言，不支持的系统语言回退英文。打开桌面设置可选择语言，也可在配置文件中设置：

```toml
[app]
language = "en" # auto、zh 或 en
```

语言选择和其他编辑一起保存。保存后需从托盘退出并重新启动应用；仅关闭窗口会隐藏到托盘，不会重启。独立运行 Java 后端时，需重启后端并刷新页面。界面、原生菜单和新生成内容的提示词会采用同一有效语言，历史会话、已有摘要和用户原文保持原样。

配置草稿采用复杂 TOML 语法、无法安全定位语言字段时，语言选择框会禁用，可直接使用原始文本编辑器。后端未就绪时，页面使用英文加载提示，原生启动失败提示按系统语言显示。

## 核心模块

| 模块 | 职责 |
|------|------|
| `self-analyst-events` | 嵌入式事件服务、事件策略和历史迁移 |
| `self-analyst-content` | 前台窗口、UIA 临时查询和上下文标题提取 |
| `self-analyst-file` | 用户显式配置目录中的文件名、路径、大小和时间等元数据 |
| `self-analyst-wiki` | 标题事实的时间聚合、摘要和索引 |
| `self-analyst-app` | 启动编排、Agent、桌面 API/UI |
| `self-analyst-axsidecar` | Windows UIAutomation Rust 边车 |
| `self-analyst-desktop` | Tauri 桌面壳 |

## 数据边界

- 内容事件只允许 v2 标题白名单字段，不允许 `text_content`、`uia_text`、`raw_tree`、正文或截图。
- UIA 查询失败时退回系统窗口标题，不使用截图/OCR 回退。
- 敏感应用会跳过 UIA 查询。
- 文件模块只采集文件名、路径、大小和创建/修改时间等文件系统元数据；除安全解析 `.gitignore` 外，
  不读取普通文件正文，不计算内容哈希，也不生成摘要、主题或向量。

嵌入式事件服务永久保留通过隐私校验的原始事件，不会自动清理最旧数据；磁盘低于阻断阈值时停止新采集。
外部 ActivityWatch 模式不提供此永久保留保证。

隐私细节见 [PRIVACY.md](PRIVACY.md)，现行规格索引见 [docs/README.md](docs/README.md)。

## 配置

桌面配置入口默认打开“模型设置”，支持一个 OpenAI-compatible 连接，可选择连接预设、填写 API Key、
加载或手工输入模型，并调整温度和输出上限。保存后，新聊天和摘要任务采用新配置，进行中的回答
继续使用原配置；历史会话与用量不会重置。摘要与压缩保持低温策略。详见[模型设置指南](docs/llm-settings.md)。

“高级配置”保留 TOML 原文编辑入口。模型表单仅修改指定字段，保留其他配置与注释；无法安全修改的
复杂语法需要使用高级配置。旧通用结构化 API 与 Agent 配置工具仍可能重新生成 TOML。
切换页面时，如有未保存修改会提示确认。

显式 TOML 配置优先于环境变量，未配置的键才回退环境变量和默认值。删除覆盖可恢复继承，显式空密钥
会阻止环境变量兜底。密码框留空保留已有密钥；“清空密钥”和“恢复密钥继承”是分别确认的操作。
界面显示配置来源与生效状态。完整键表见[用户配置规格](openspec/specs/user-configuration/spec.md) 和 `SupportedKeys`。
`memory.dir` 的显式 JVM 参数优先级最高；`events.port` 不接受环境变量覆盖。

无需重启即可更新的键为 `llm.api-key`、`llm.base-url`、`llm.model`、`llm.temperature` 和 `llm.max-tokens`。
端口、预算、`maxIters`、压缩阈值与 Embedding 客户端仍采用启动期配置；Embedding 继承的密钥变化时会
单独提示重启。未配置模型时仍可启动本地服务，补齐模型设置后新任务无需重启即可使用。

生成连接测试发送固定的最小请求，可能产生少量费用；测试与模型发现均不保存配置。
高级配置中的旧 LLM 检查仅验证模型目录连通性。保存成功不保证模型可调用，测试成功也不会切换运行配置。
外部修改 TOML 不会自动应用，需要在应用内保存或重启后端；同一字段并发更新时，以最后提交的值为准。

| 配置键 | 环境变量 | 默认值 |
|--------|----------|--------|
| `llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` |
| `llm.model` | `LLM_MODEL` | `gpt-4o` |
| `llm.api-key` | `OPENAI_API_KEY` | 空 |
| `events.mode` | `EVENTS_MODE` | `embedded` |
| `events.port` | —（仅 `config.toml`） | `5700` |
| `events.collection.title.enabled` | `EVENTS_COLLECTION_TITLE_ENABLED` | `true` |
| `events.raw.dir` | `EVENTS_RAW_DIR` | `{events.data-dir}/raw` |
| `events.raw.query.maxRangeDays` | `EVENTS_RAW_QUERY_MAX_RANGE_DAYS` | `31` |
| `events.raw.query.maxPageSize` | `EVENTS_RAW_QUERY_MAX_PAGE_SIZE` | `1000` |
| `events.raw.lowDisk.warnBytes` | `EVENTS_RAW_LOW_DISK_WARN_BYTES` | `10737418240` |
| `events.raw.lowDisk.blockBytes` | `EVENTS_RAW_LOW_DISK_BLOCK_BYTES` | `1073741824` |
| `events.raw.integrity.startupScope` | `EVENTS_RAW_INTEGRITY_STARTUP_SCOPE` | `latest` |
| `events.raw.projector.batchSize` | `EVENTS_RAW_PROJECTOR_BATCH_SIZE` | `1000` |

嵌入式模式的永久原始层固定启用，不支持 TTL、最大分区数或自动删除配置。产生分区后，普通配置
保存不能修改 `events.raw.dir`；目录迁移需要独立的显式转存流程。

## 文档

- [模型设置指南](docs/llm-settings.md)
- [会话图片输入规格](openspec/specs/chat-image-input/spec.md)
- [桌面会话与输出文件规格](openspec/specs/desktop-chat/spec.md)
- [帮助与更新规格](openspec/specs/desktop-help/spec.md)
- [架构](docs/architecture.md)
- [文档与规格索引](docs/README.md)
- [测试与集成验证](docs/testing.md)
- [上下文标题采集规格](openspec/specs/title-capture/spec.md)
- [标题最小化持久化规格](openspec/specs/content-event-persistence/spec.md)
- [OCR 与声音模块暂时移除说明](docs/archive/removed-features/removed-ocr-audio.md)
- [隐私说明](PRIVACY.md)

## 长期记忆与会话澄清

长期记忆默认在后台自动总结和筛选，会话页不再展示审批面板。具有长期价值且证据充分的内容自动保存；凭据、敏感推断、低可信内容和一次性操作流水被过滤。旧的待确认记忆会在启动后后台重新评估：合格项生效，其余删除，模型或保存失败时保留原记录并稍后重试。显式关闭自动总结的会话保持关闭，旧的全部确认策略兼容为自动筛选。

如果发现记忆不准确，可以直接说“你记错了，我目前维护的是另一个项目”，或“忘记关于这个项目的记忆”。目标不明确时助手会先追问；目标明确后更正或停用对应记忆，只有保存成功才确认完成。显式纠错也适用于关闭自动总结的会话。

## 活动统计口径

看板与 Wiki 以**本地时间 04:00 至次日 04:00** 为统计日。凌晨 04:00 前的活动归前一个统计日；
周一 04:00 开始新的一周，每月首日 04:00 开始新的统计月。上午为 04:00–12:00。
当前窗口仍为最近约两小时，看板“最近两周”仍为滚动十四天。

应用耗时先裁剪到查询区间，再扣除与 AFK（非活跃）重叠的时间。未识别应用活动单独展示，
不参与主要应用结论；AFK 覆盖不足时明确标为估计。旧摘要快照会更新，旧 Wiki 摘要保留供追溯，
不进入当前口径结果。启用 Wiki 历史补算时，后台按既有模型预算和重试设置逐步重算，原始事件保持不变。

迁移与恢复细节见[活动统计说明](docs/activity-statistics.md)。

## 贡献

欢迎提交缺陷报告、功能建议和 PR。本项目采用规格驱动开发，行为契约以 `openspec/specs/`
为权威来源，并对采集与持久化范围有明确红线，动手前请先阅读
[贡献指南](CONTRIBUTING.md)，尤其是其中的数据边界红线与规格驱动流程两节。

- [贡献指南](CONTRIBUTING.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [安全策略](SECURITY.md) — 安全漏洞请勿开公开 Issue，请走私密报告渠道

## 许可证

本项目采用 [Apache License 2.0](LICENSE)，另见 [NOTICE](NOTICE)。依赖与随发布产物分发的
第三方组件及其各自许可证见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
