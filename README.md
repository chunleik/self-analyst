# SelfAnalyst

SelfAnalyst 是一个本地优先的个人活动分析工具。窗口/AFK 状态与应用内上下文标题由本地
ActivityWatch 存储并供 Wiki 聚合；用户显式配置目录中的文件系统元数据则由本地文件存储、
FileTools 和桌面 API 提供查询，并以 metadata-only heartbeat 留存在本地 ActivityWatch 历史中。

嵌入式 ActivityWatch 模式会先把每次通过隐私校验的原始事件永久、只追加地写入 UTC 月度
SQLite 分区，再生成可合并、可重建的 `aw.db` 投影。外部 ActivityWatch 模式不提供这项永久保留
保证。磁盘低于阻断阈值时系统拒绝新采集，不会自动删除最旧分区。

当前“内容采集”的严格定义是标题采集：允许在识别时临时读取完整 UIA 控件树，但最终只保存
系统窗口标题、微信对话人、文章/文档/页面标题及来源、置信度和时间等元数据，禁止保存 UIA 正文。

OCR、截图样本、麦克风/系统声音采集和语音转写已暂时从代码与产品入口中移除。详细范围、旧配置
兼容和恢复方式见 [OCR 与声音模块暂时移除说明](docs/archive/removed-features/removed-ocr-audio.md)。

## 环境要求

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

输出为 `artifacts/SelfAnalyst-portable.zip`。发布包不包含 PaddleOCR、Tesseract、Whisper 或语音模型。

## 核心模块

| 模块 | 职责 |
|------|------|
| `self-analyst-aw` | 嵌入式 ActivityWatch、事件策略和历史迁移 |
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
- OCR/音频旧配置键被识别并忽略；旧数据库和用户目录不会自动删除。

隐私细节见 [PRIVACY.md](PRIVACY.md)，现行规格索引见 [docs/README.md](docs/README.md)。

## 配置

首次启动会在数据目录创建 `config.toml`。桌面“配置”页可编辑当前受支持字段；环境变量仍可覆盖
对应配置。完整键表以 [用户配置规格](openspec/specs/user-configuration/spec.md) 和 `SupportedKeys` 为准。

| 配置键 | 环境变量 | 默认值 |
|--------|----------|--------|
| `llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` |
| `llm.model` | `LLM_MODEL` | `gpt-4o` |
| `llm.api-key` | `LLM_API_KEY` | 空 |
| `aw.mode` | `AW_MODE` | `embedded` |
| `aw.port` | `AW_PORT` | `5700` |
| `aw.collection.content` | `AW_COLLECTION_CONTENT` | `true` |
| `aw.raw.dir` | `AW_RAW_DIR` | `{aw.data-dir}/raw` |
| `aw.raw.query.maxRangeDays` | `AW_RAW_QUERY_MAX_RANGE_DAYS` | `31` |
| `aw.raw.query.maxPageSize` | `AW_RAW_QUERY_MAX_PAGE_SIZE` | `1000` |
| `aw.raw.lowDisk.warnBytes` | `AW_RAW_LOW_DISK_WARN_BYTES` | `10737418240` |
| `aw.raw.lowDisk.blockBytes` | `AW_RAW_LOW_DISK_BLOCK_BYTES` | `1073741824` |
| `aw.raw.integrity.verifyOnStartup` | `AW_RAW_INTEGRITY_VERIFY_ON_STARTUP` | `latest` |
| `aw.raw.projector.batchSize` | `AW_RAW_PROJECTOR_BATCH_SIZE` | `1000` |

嵌入式模式的永久原始层固定启用，不支持 TTL、最大分区数或自动删除配置。产生分区后，普通配置
保存不能修改 `aw.raw.dir`；目录迁移需要独立的显式转存流程。

[移除说明](docs/archive/removed-features/removed-ocr-audio.md) 中列出的旧 OCR 键和所有 `aw.audio.*` 不再是受支持配置。
加载旧文件时这些键不会导致启动失败，但不会产生任何功能。

## 恢复基线

移除 OCR 与声音模块前已建立带注释 Git 标签：

```powershell
git show archive/pre-remove-ocr-audio
```

重新引入功能时应从该标签开独立分支重新设计数据边界，不应直接把旧实现合回当前主线。

## 文档

- [架构](docs/architecture.md)
- [上下文标题采集规格](openspec/specs/title-capture/spec.md)
- [标题最小化持久化规格](openspec/specs/content-event-persistence/spec.md)
- [OCR 与声音模块暂时移除说明](docs/archive/removed-features/removed-ocr-audio.md)
- [隐私说明](PRIVACY.md)
