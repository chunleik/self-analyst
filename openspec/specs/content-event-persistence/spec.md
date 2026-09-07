# 内容事件最小化持久化规格

## Purpose

定义上下文标题从瞬时无障碍读取到 ActivityWatch 持久化、历史净化及下游消费的最小化契约，确保当前写入只保存标题事实，并准确描述历史 OCR 兼容值、迁移副本和有限降级边界。

## Requirements

### Requirement: SPEC-CTP-001 临时读取与持久化投影边界
系统 MAY 在一次标题识别调用中临时读取无障碍树和文本。自动采集 Snapshot MAY 保留用于校验结果
新鲜度的非正文窗口身份元数据，但 Snapshot 和内容 heartbeat MUST NOT 包含无障碍树、正文、截图或
等价原始内容；跨越捕获调用的内容事实 SHALL 限于应用名、系统窗口标题、可选上下文标题投影及不可
还原正文的诊断计数。该边界不声称更早执行的旧布局迁移从未生成临时工作副本。

#### Scenario: 正文秘密不进入捕获结果和 heartbeat
- **WHEN** 无障碍输入包含唯一正文秘密标记并完成标题识别
- **THEN** 标题识别结果和 heartbeat data 仅包含标题投影及诊断计数，且不包含该秘密标记

### Requirement: SPEC-CTP-002 标题事实派生边界
Wiki 从 ActivityWatch 内容事件投影构造事实时 SHALL 只读取窗口标题、上下文标题、应用和上下文种类，MUST NOT 直接查询逐 heartbeat 原始事件层，也 MUST NOT 读取 `text_content`。更高层级 Wiki 聚合 MAY 消费子级 metrics、summary 和 primaryTask，并 MAY 持久化由标题事实及这些既有派生结果生成的摘要、主要任务和任务片段。

#### Scenario: 旧正文行被消费者忽略
- **WHEN** 内容 bucket 投影中存在一条带系统标题和旧 `text_content` 的历史行
- **THEN** Wiki 使用系统标题作为回退标题事实，且 facts 和 prompt 不包含旧正文

#### Scenario: Wiki 不查询逐 heartbeat 原始层
- **WHEN** Wiki 为一个时间段构造内容事实
- **THEN** 系统从 ActivityWatch 内容投影读取标题事实，不通过桌面原始事件 API读取逐条 heartbeat

### Requirement: SPEC-CTP-010 内容 bucket 写入策略适用范围
系统 SHALL 对 ID 以 `watcher-content_` 或 `aw-watcher-content_` 开头，或已存 bucket 的 `client` 大小写不敏感等于
`watcher-content` 或 `aw-watcher-content` 的所有应用写入执行内容事件 v2 策略；其他 bucket 的既有通用字段 SHALL 不受
此策略影响。本产品内容采集器新写入 SHALL 使用 `watcher-content_{hostname}` 与 client `watcher-content`。

#### Scenario: bucket ID 前缀触发策略
- **WHEN** 禁止字段写入 ID 以 `watcher-content_` 或 `aw-watcher-content_` 开头的 bucket
- **THEN** 写入被内容事件策略拒绝

#### Scenario: bucket client 触发策略
- **WHEN** 禁止字段写入自定义 ID、但 client 为 `watcher-content` 或 `aw-watcher-content` 的已存 bucket
- **THEN** 写入被内容事件策略拒绝

#### Scenario: 非内容 bucket 不受影响
- **WHEN** 同一通用字段写入既没有内容前缀也没有内容 client 的 bucket
- **THEN** 写入不因内容事件 v2 策略而失败

### Requirement: SPEC-CTP-042 默认桶列表隐藏内容采集桶
默认 bucket 列表 SHALL 隐藏 ID 以 `watcher-content_` 或 `aw-watcher-content_` 开头的桶；完整列表或显式查询仍可返回这些桶。

#### Scenario: 默认列表不展示内容桶
- **WHEN** 客户端请求默认 bucket 列表且存在本产品内容采集桶
- **THEN** 响应不包含该内容桶 ID，窗口等其他采集桶仍可见

### Requirement: SPEC-CTP-011 内容事件 v2 字段集合与值约束
内容事件 data SHALL 只允许 `schema_version`、`app`、`title`、`context_title`、`context_kind`、
`title_source`、`title_confidence`、`uia_chars` 和 `ocr_chars`。系统 SHALL 按以下 Scenario 中的
基础、上下文和诊断字段契约校验值；任何其他字段 MUST 被拒绝。

#### Scenario: 合法标题事件被接受
- **WHEN** 内容事件包含必需字段和合法可选上下文字段
- **THEN** 系统允许持久化，读取结果只包含 v2 允许字段

#### Scenario: 基础字段契约
- **WHEN** 内容事件提交基础字段
- **THEN** `schema_version` 必须为数值 `2`
- **THEN** `app` 与 `title` 必须为单行字符串，分别最多 260 和 1024 code point
- **THEN** `title_source` 必须为 `window/uia_document/uia_context/ocr_title` 之一

#### Scenario: 上下文字段契约
- **WHEN** 内容事件包含 `context_title`
- **THEN** 该值必须为非空白单行字符串、最多 200 code point，且不是纯 URL、精确匹配的内置通用标签或具有两个及以上句末符的明显句群
- **THEN** `context_kind` 必须为 `chat/article/document/page/unknown` 之一
- **THEN** 可选的 `title_confidence` 必须为 `high/medium/low` 之一

#### Scenario: 诊断计数字段契约
- **WHEN** 内容事件包含 `uia_chars` 或 `ocr_chars`
- **THEN** 诊断计数必须为数值，且其 64 位整数转换结果非负

#### Scenario: 正文键或未知键被拒绝
- **WHEN** 内容事件包含正文键或任意不在允许集合中的字段
- **THEN** 系统在写入前拒绝该事件

#### Scenario: 非标题上下文被拒绝
- **WHEN** `context_title` 是纯 URL、忽略大小写后精确匹配内置通用标签、包含换行、至少两个句末符或超过 200 code point
- **THEN** 系统拒绝该内容事件

#### Scenario: 上下文字段依赖不成立
- **WHEN** 事件没有 `context_title` 却包含 `context_kind` 或 `title_confidence`
- **THEN** 系统拒绝该内容事件

### Requirement: SPEC-CTP-012 OCR 历史兼容值与当前采集器
持久化策略 SHALL 为兼容现有数据接受 `title_source=ocr_title` 和 `ocr_chars`；当前生产标题采集链
MUST NOT 生成这两个值。迁移 SHALL 保留合法 `ocr_chars`，并且只在存在合法上下文候选时保留兼容
来源值 `title_source=ocr_title`；否则 SHALL 把来源规范化为 `window`。迁移保存 `ocr_chars` 时 SHALL
将合法数值规范化为其 64 位整数转换结果。

#### Scenario: 当前采集不生成 OCR 字段
- **WHEN** 当前标题采集器生成内容 heartbeat
- **THEN** heartbeat 不包含 `ocr_chars`，且 `title_source` 为 `window/uia_document/uia_context`

#### Scenario: 兼容 payload 可被服务端接受
- **WHEN** 合法 v2 内容 payload 显式包含 `title_source=ocr_title` 或字段 `ocr_chars`
- **THEN** 服务端按兼容字段规则接受，而不是把它们视为未知字段

#### Scenario: 没有候选的历史 OCR 来源被规范化
- **WHEN** 旧事件只有 `ocr_title` 来源但没有合法上下文标题候选
- **THEN** 迁移保留系统窗口标题并把来源规范化为 `window`

### Requirement: SPEC-CTP-013 可持久化标题候选
系统 SHALL 优先使用应用专用上下文候选，其次使用当前已验证应用结构中的富文档标题；无法取得可靠
候选时 SHALL 省略 `context_title` 并保留系统窗口标题。空白、通用标签、纯 URL、多行、明显句群和
超长文本 MUST NOT 成为上下文标题。

#### Scenario: 微信聊天和文章产生结构化标题
- **WHEN** 微信无障碍结构分别可靠标识当前聊天和富文档文章
- **THEN** 系统分别生成 `chat/uia_context/high` 和 `article/uia_document/high` 的上下文标题

#### Scenario: 通用文档名称不被直接持久化
- **WHEN** 未验证应用仅提供 `Document.Name`
- **THEN** 系统省略上下文标题并使用窗口来源

### Requirement: SPEC-CTP-020 统一校验与策略失败原子性
系统 SHALL 在 heartbeat、单条或批量 events、导入、内容 bucket 认领及内部存储写入上应用同一策略。
批量 events 和导入 MUST 在首条写入或首个 bucket 创建前预检全部内容策略条件；该原子性保证只约束
内容策略违规，不扩展到策略校验后的其他解析或存储错误。

#### Scenario: 批量策略违规不部分写入
- **WHEN** 同一批包含合法内容事件和带正文键的非法内容事件
- **THEN** 整批因策略违规失败，批内事件均不写入

#### Scenario: 导入策略违规不创建 bucket
- **WHEN** 导入包含新内容 bucket 和至少一个非法内容事件
- **THEN** 导入在创建 bucket 或写事件前失败

#### Scenario: 内容 bucket 认领前预检
- **WHEN** 已有孤儿事件将被认领为内容 bucket，且其中含有违规 data
- **THEN** 系统拒绝认领，不让旧违规事件绕过策略

### Requirement: SPEC-CTP-021 安全的策略失败响应
内容策略违规的 HTTP 请求 SHALL 返回 `422`，响应 MUST NOT 包含字段值或完整请求体。已知禁止字段
MAY 以字段名报告；包含不可信未知字段名时 SHALL 报告 `unknown`。缺少 schema v2 的旧正文事件
MUST NOT 通过兼容模式继续写入。

#### Scenario: 正文值不被错误响应回显
- **WHEN** heartbeat、events 或 import 提交包含秘密正文值的禁止字段
- **THEN** 响应状态为 `422`，并且响应不包含秘密值

#### Scenario: 不可信未知字段名被隐藏
- **WHEN** payload 包含不在已知禁止集合中的额外字段
- **THEN** 错误只报告 `unknown`，不回显该字段名或字段值

### Requirement: SPEC-CTP-030 增量逻辑净化与状态记录
嵌入式启动迁移 SHALL 扫描 bucket ID 具有内容前缀 `watcher-content_` 或 `aw-watcher-content_`，或 bucket client 精确等于规范值
`watcher-content` 或 `aw-watcher-content` 的内容行，并将其规范化为 v2 投影，删除正文和未知字段。首次运行 SHALL 扫描
该选择范围内全部内容行；已有完成记录时 SHALL 从已完成高水位之后继续扫描，并把成功扫描水位推进
到当前全局最大事件 ID。发生需物理净化的逻辑修改时 SHALL 先记录 pending 状态。写策略对 client
大小写不敏感的更宽识别范围，不构成历史迁移对混合大小写 client bucket 的净化保证。

#### Scenario: 首次净化提取可靠旧标题
- **WHEN** 旧微信内容事件含正文和可可靠识别的聊天标题
- **THEN** 迁移保留标题投影并删除正文及未知字段

#### Scenario: 后续运行只处理新高水位
- **WHEN** 初次迁移完成后又出现 ID 更高的旧内容行
- **THEN** 后续迁移处理该新行，再次运行不重复扫描已完成范围

#### Scenario: 非内容长尾推进高水位
- **WHEN** 内容行之后存在 ID 更高的非内容事件
- **THEN** 成功扫描水位推进到全局最大事件 ID，避免后续重复扫描同一长尾

#### Scenario: 混合大小写的自定义 content client
- **WHEN** 自定义 bucket 的 client 仅以大小写变体匹配 `watcher-content` 或 `aw-watcher-content`，且 bucket ID 没有内容前缀
- **THEN** 新写入仍受内容策略保护，但当前历史迁移不保证扫描或净化该 bucket 的既有行

### Requirement: SPEC-CTP-031 物理净化、旧副本删除与完成状态
标题化迁移 SHALL 在逻辑更新连接启用 SQLite `secure_delete`。当本轮净化了事件、已有 pending 状态，
或尚无完成 marker 时，逻辑提交后 SHALL 执行 WAL 截断 checkpoint、`VACUUM` 和再次 checkpoint；
本轮净化事件时 SHALL 先记录 pending。成功后 SHALL 仅在严格验证的数据目录范围内删除内容前缀或
规范 client 精确识别的旧 bucket 数据库、sidecar 和识别出的旧布局工作文件或备份。首次完成 marker
和无 pending 的高水位状态 MUST 在所需物理净化及旧副本清理成功后记录。标题化迁移自身 MUST NOT
新建普通备份；该约束不表示更早执行的旧布局迁移从不产生临时工作文件或备份。

#### Scenario: 成功净化后不保留识别出的旧副本
- **WHEN** 逻辑净化、压缩和路径验证全部成功
- **THEN** 识别出的内容旧数据库及迁移副本被删除，pending 状态被清除并记录完成状态

#### Scenario: 物理净化失败可重试
- **WHEN** 逻辑更新提交后物理净化失败
- **THEN** 已完成高水位不推进；已有 pending 时继续保留，未产生 pending 时则因缺少新的完成记录而在下次运行重试物理净化
- **THEN** 先前成功运行留下的 marker MAY 继续存在

### Requirement: SPEC-CTP-032 嵌入式启动顺序与有限降级
嵌入式模式 SHALL 在启动 AW HTTP 服务和标题 watcher 前执行内容迁移。迁移失败时 SHALL 禁止标题
watcher 和新的 Wiki 原始事实汇总 worker，仍 MAY 启动 AW、window/AFK watcher、Agent、通用查询或
导出，以及只消费既有派生 Wiki 数据的服务。桌面状态的 `contentPersistence.status` SHALL 为
`migration_failed`，上下文标题 collector SHALL 为 `degraded`。

#### Scenario: 迁移失败保留非内容能力
- **WHEN** 内容迁移在嵌入式启动时失败
- **THEN** 标题采集和新 Wiki 汇总不启动，window/AFK 与 AW 服务仍可运行，并在状态 API 中报告迁移失败

### Requirement: SPEC-CTP-040 Wiki 与 Agent 的内容事实边界
Wiki 从原始 ActivityWatch 内容事件构造事实时 SHALL 只读取标题事实，并在缺少 `context_title` 时
使用系统 `title`；父级 Wiki 聚合 MAY 使用子级 metrics、summary 和 primaryTask。Wiki prompt MUST NOT
包含 `text_content` 或屏幕正文片段。Agent 的通用 ActivityWatch 查询不执行第二次内容字段投影，
因此其内容安全保证 SHALL 来自对应 bucket 成功迁移和统一写入策略，而不是工具层字段过滤。

#### Scenario: 标题事实进入 Wiki prompt
- **WHEN** Wiki 处理同时含上下文标题和仅含系统标题的内容事件
- **THEN** prompt 使用相应标题样本，不包含旧正文

#### Scenario: Agent 查询健康内容 bucket
- **WHEN** Agent 通过通用 ActivityWatch 工具查询属于迁移选择范围、已成功迁移并受写策略保护的内容 bucket
- **THEN** 返回的持久化 data 只包含内容事件允许字段

### Requirement: SPEC-CTP-041 桌面状态与时间线响应边界
桌面状态响应的 `contentPersistence` 子对象 SHALL 返回 `schemaVersion`、`ready` 和 `status`，并只在
迁移失败时返回经单行和长度限制处理的 `error`；完整状态响应 MAY 同时包含 backend、language、AW、
collectors 和 LLM 等其他状态。迁移失败时 `contentPersistence.status` SHALL 为 `migration_failed`，
上下文标题 collector SHALL 为 `degraded`。桌面 summary/timeline SHALL 从 window/AFK 事实，或由这些
允许事实派生的 Wiki 摘要构造响应，MUST NOT 读取或拼接无障碍正文。通用 AW events、AQL 和 export API
返回持久化事件 data，不提供额外字段投影。

#### Scenario: 桌面迁移失败状态不返回事件
- **WHEN** 内容迁移失败且客户端请求桌面状态
- **THEN** 响应报告 `degraded` 和 `migration_failed`，但不包含内容事件或无障碍正文

#### Scenario: 桌面摘要只使用窗口与 AFK 事实
- **WHEN** 客户端请求当前摘要和时间线
- **THEN** 响应由窗口标题、应用、耗时、AFK 数据或由其派生的 Wiki 摘要构造，不拼接隐藏控件文本

### Requirement: SPEC-CTP-050 内容策略先于永久原始提交
所有内容 heartbeat、events 和 import MUST 在写入永久原始事件层前执行内容事件 v2 策略。通过策略的每个原始内容事件 SHALL 永久保存其 v2 标题字段且不受后续 heartbeat 投影合并影响；策略失败的 payload MUST NOT 进入原始事件层。

#### Scenario: 内容原始 heartbeat 永久保留
- **WHEN** 多个合法内容 heartbeat 通过策略后被 ActivityWatch 投影合并
- **THEN** 原始层仍分别保存每个 heartbeat，且每条 data 只包含内容 v2 允许字段

#### Scenario: 正文键在原始写入前拒绝
- **WHEN** 内容事件包含 `text_content`、`uia_text`、`raw_tree` 或其它禁止字段
- **THEN** 系统返回 `422`，永久原始层和 ActivityWatch 投影均不包含该事件或禁止值
