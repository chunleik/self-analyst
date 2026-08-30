# OCR 与声音模块暂时移除说明

> 状态：已实施
>
> 实施日期：2026-08-30
>
> 恢复基线：Git 标签 `archive/pre-remove-ocr-audio`

## 1. 决策

为明确“只采集标题、不采集内容”的产品边界，当前版本暂时移除 OCR 和声音模块的代码、配置、
桌面功能、测试入口和发布依赖。UIA 仍可在一次调用内读取完整控件树，但跨越采集边界的对象只能
是标题投影。

本次是可恢复的功能收缩，不是历史数据销毁。已有数据库、旧音频 bucket、用户本地 OCR 样本目录
和 `tools/` 中的第三方文件不会由升级程序自动删除。

## 2. 删除范围

### 2.1 OCR

- 删除截图、OCR 引擎、PaddleOCR/Tesseract 适配、样本保存、Thin 检测和 UIA/OCR 合并代码。
- `ContentWatcher` 改为 `TitleCapture`：临时读取 UIA，只输出标题及诊断计数。
- 删除 Tess4J Maven 依赖、OCR 默认配置、桌面配置项和运行时系统属性。
- 删除 OCR 下载、`-WithOcr` 打包参数和 PaddleOCR 发布目录。
- 无障碍边车失败时只回退到系统窗口标题，不再截图。

### 2.2 声音

- 删除 `self-analyst-audio` 及其集成测试模块。
- 删除录音、VAD、本地 Whisper、云端 ASR、音频 watcher、事件控制器和桌面 API。
- 删除桌面端声音开关、状态、配置表单、样式、文案和测试。
- 删除 Whisper 下载和 minimal/full 包变体，便携发布统一为
  `artifacts/SelfAnalyst-portable.zip`。

### 2.3 配置

下列旧键仅作为兼容墓碑被识别并忽略，不参与运行，也不出现在受支持配置列表或桌面表单中：

- `aw.ocr.engine`
- `ocr.sample.enabled`、`ocr.sample.dir`
- `ocr.excluded.apps`、`ocr.title-strip-height`
- `ocr.stable-capture-interval-ms`、`ocr.force-refresh-ms`
- 所有 `aw.audio.*` 键

这样旧 `config.toml` 可以继续启动且不会产生未知键警告；用户再次保存结构化配置时，这些键不会
被写回。原始文本编辑模式不会主动删除用户文件中的旧行。

## 3. 数据兼容

- 新内容事件不再写入 `ocr_chars`，`title_source` 只产生 `window`、`uia_document` 或
  `uia_context`。
- 服务端继续接受历史内容事件中的 `ocr_title` 与 `ocr_chars`，仅用于读取和迁移旧数据；这不是
  OCR 功能仍然可用的信号。
- 旧音频 bucket 保留并可按既有通用 ActivityWatch 查询方式读取，但不会再产生新音频事件。
- 升级过程不删除 `ocr.sample.dir`、录音临时目录或 `tools/PaddleOCR-json`、`tools/whisper`。
  如需清理，应先备份并由用户明确执行。

## 4. 发布和运维变化

- `scripts/download-tools.ps1` 已删除。
- `scripts/build-dist.ps1` 不再接受 `-WithOcr`，也不复制任何 OCR/Whisper 工具。
- `scripts/build-portable.ps1` 只保留 `-SkipBuild` 和 `-NoZip`，只生成一个便携包。
- JDK 21、Maven、Rust/cargo 仍是构建前置条件；运行包仍包含 jlink JRE 和 accessibility sidecar。

## 5. 验证清单

1. Maven reactor 中不存在 `self-analyst-audio`。
2. 生产源码中不存在 OCR、截图、录音、Whisper 或 ASR 调用链。
3. 配置加载包含旧键时启动成功，配置 API 不返回 OCR/声音字段。
4. UIA 标题识别测试覆盖微信对话人、文章标题、通用 Document 和无候选场景。
5. 全量 `mvn test`、Rust sidecar 测试和 PowerShell 脚本语法检查通过。
6. 便携包中不出现 PaddleOCR、Tesseract 或 Whisper 文件。

## 6. 恢复方式

如后续重新引入，应从 `archive/pre-remove-ocr-audio` 创建独立分支，按“标题输出适配器”重新设计，
不要直接把旧模块合回主线。重新引入前必须满足：

- OCR/ASR 原文只在内存中短暂存在，持久化类型无法表达正文；
- 默认关闭，并有独立权限提示、敏感应用排除和可验证的数据保留策略；
- API、UI、配置、发布依赖与核心标题采集解耦；
- 新旧数据迁移和清理均有显式、可回滚的方案。
