## Why

当前 ActivityWatch 兼容事件表同时承担采集事实、heartbeat 合并结果和下游分析来源，合并时会原地延长事件持续时间，无法保留每次成功接收的合规原始事件，也无法为投影重建提供稳定事实源。项目仍处于开发阶段，适合现在建立“原始事件永久保留、派生数据可重建”的清晰边界，避免后续数据规模和算法演进进一步固化现有耦合。

## What Changes

- 新增永久、只追加的合规原始事件层，按 UTC 月份使用 SQLite 分区，保存隐私与来源 schema 校验后的事件语义值，不保存 HTTP 原始请求字节。
- 自有 window、AFK、content 和 file watcher 为逻辑事件生成稳定来源 ID；普通 events 和 import 支持批量幂等写入。
- 写入链路改为先提交原始事件，再由可重试投影器生成 ActivityWatch 兼容事件；heartbeat 合并只修改投影，不修改原始事件。
- 新增分区 catalog、封存 manifest、完整性校验、投影 checkpoint、崩溃恢复和从原始事件重建派生存储的能力。
- 新增受桌面认证保护的分页原始事件查询 API；现有 ActivityWatch events、AQL、Wiki 和 Agent 查询继续读取合并投影。
- 原始事件接口在启动 token 缺失时 fail-closed；WebView 使用同源 header token，系统浏览器使用作用域限制为 `/desktop` 的 HttpOnly 会话 cookie。
- **BREAKING**：bucket 删除、文件监控根移除和派生数据清理不再删除永久原始事件；原始事件不使用 TTL、FIFO 或容量上限自动删除，磁盘不足只允许告警、停止非必要派生工作或阻止新采集。
- 内容事件在永久写入前继续执行标题字段白名单，文件事件继续严格限制为 metadata-only；UIA 正文、控件树、普通文件正文、截图、OCR 和音频不进入原始层。
- Wiki、ActivityWatch 投影和语义索引记录重建所需版本与覆盖信息，并保持为可删除、可重建的派生数据。
- 增加原始存储路径、查询上限、磁盘阈值、完整性校验和投影批量参数的配置契约与桌面状态展示。
- 不迁移或导入启用前的开发期 `aw.db` 数据，也不新增旧 schema 兼容或历史精度标记。
- 永久原始事件保证仅适用于应用控制写入链路与数据目录的嵌入式 ActivityWatch 模式；外部 ActivityWatch 模式明确报告该能力不可用。

## Capabilities

### New Capabilities

- `raw-event-retention`: 定义合规原始事件、只追加永久保留、月度 SQLite 分区、原始优先写入、投影重放、只读桌面查询、磁盘压力和完整性保证。

### Modified Capabilities

- `content-event-persistence`: 内容策略必须在原始提交前执行，内容 heartbeat 的永久原始记录与 ActivityWatch 标题投影分离。
- `file-metadata-collection`: 文件 heartbeat 的合规元数据事件进入永久原始层，文件当前状态与历史原始变化保持不同查询边界。
- `desktop-shell`: 原始事件桌面接口必须要求本次启动凭据，token 缺失时拒绝访问，并收紧浏览器会话 cookie 的 Path。
- `user-configuration`: 增加原始事件分区、查询、磁盘阈值、完整性检查和投影批量参数的配置与校验。
- `llm-wiki`: Wiki 明确消费 ActivityWatch 投影而非逐 heartbeat 原始层，并记录可重建所需的事实构建版本和覆盖范围。

## Impact

- 主要影响 `self-analyst-aw` 的 HTTP 写入入口、SQLite 存储、heartbeat 合并、bucket 删除语义、查询和启动恢复。
- `self-analyst-content`、`self-analyst-file` 和 window/AFK watcher 需要生成并在重试时复用稳定来源事件 ID。
- `self-analyst-app` 需要调整启动顺序，注册原始事件桌面 API，并暴露容量、分区、完整性和投影延迟状态。
- `self-analyst-desktop` 继续复用现有 token 注入与浏览器会话交换，但 cookie 范围收紧且原始接口不能在无 token 模式下降级开放。
- `self-analyst-wiki` 增加事实构建版本和原始覆盖引用，但 LLM 与 embedding 输入边界不扩大。
- 新增多个长期 SQLite 分区和 manifest 文件，会持续增加本地磁盘占用；首版不新增压缩归档格式、AQL 原始查询、云同步、自动原始数据删除或旧开发数据迁移。
- 现有 ActivityWatch events/AQL/Agent 调用保持合并时间线语义；新增原始查询只通过受保护的桌面 API 提供。
