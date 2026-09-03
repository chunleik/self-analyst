# 原始事件永久保留规格

## Purpose

定义经过来源 schema 与隐私策略校验的采集事件如何以不可变事实永久保留，并为 ActivityWatch 时间线、Wiki 和其它派生结果提供可恢复、可验证且受控查询的统一来源。

## Requirements

### Requirement: SPEC-RAW-001 合规原始事件边界
系统 SHALL 把已经完成解析、来源 schema 校验和隐私校验，但尚未执行 heartbeat 合并、时间聚合、摘要或 embedding 的事件作为合规原始事件。原始事件 SHALL 保存事件 ID、可选来源事件 ID、bucket、来源、schema 版本、接收类型、事件时间、接收时间、持续时间、规范化 data 和完整性摘要；MUST NOT 保存 HTTP header、认证凭据、完整请求字节或校验失败的字段。

#### Scenario: 校验后的事件成为原始事实
- **WHEN** 一个窗口 heartbeat 通过来源 schema、大小和隐私校验
- **THEN** 系统在任何 heartbeat 合并前永久保存其规范化事件语义值和接收元数据

#### Scenario: 非法请求不进入永久层
- **WHEN** 请求包含被来源策略禁止的字段、无效类型或超出允许大小
- **THEN** 系统拒绝请求，且原始事件存储、投影、日志和失败队列均不包含该非法 payload

### Requirement: SPEC-RAW-002 原始事件不可变与永久保留
成功提交的合规原始事件 SHALL 只追加且永久保留。系统 MUST NOT 通过更新、普通删除、bucket 删除、监控根移除、TTL、FIFO、容量上限、派生成功或后台维护修改或删除原始事件；纠错 SHALL 通过追加具有明确关联的新事件表达。

#### Scenario: heartbeat 投影合并不改变原始事件
- **WHEN** 多个相邻等价 heartbeat 被合并为一个时间线事件
- **THEN** 每个已提交 heartbeat 的原始记录、事件时间、持续时间和 data 均保持不变

#### Scenario: 删除 bucket 只影响派生投影
- **WHEN** 用户通过兼容接口删除一个 bucket
- **THEN** 系统可以删除或隐藏该 bucket 的派生事件和元数据，但其合规原始事件仍可通过受保护原始查询读取

### Requirement: SPEC-RAW-003 事件身份与幂等
系统 SHALL 为每个原始事件分配稳定且全局唯一的事件 ID。自有采集器 MUST 为逻辑事件生成来源事件 ID 并在重试时复用；批量导入 MUST 使用稳定导入会话和批内序号。来源事件 ID 或导入身份重复时 SHALL 返回既有原始事件且不得追加副本；第三方请求没有稳定身份时 SHALL 作为新的原始提交保存，MUST NOT 仅凭 payload 摘要丢弃。

#### Scenario: 自有 watcher 重试
- **WHEN** watcher 因响应不确定而使用相同来源事件 ID 重试 heartbeat
- **THEN** 原始层只存在一条对应记录，投影可以安全重试

#### Scenario: 无来源 ID 的相同第三方事件
- **WHEN** 第三方客户端连续提交两个语义值完全相同但没有稳定来源 ID 的事件
- **THEN** 系统为两次成功提交分别保存原始记录，不把可能合法的重复事件静默删除

### Requirement: SPEC-RAW-004 原始优先写入与可恢复投影
系统 MUST 在更新任何 ActivityWatch 投影前提交原始事件。原始提交失败时 MUST NOT 产生 projection-only 事件；原始提交成功而投影失败时 SHALL 保留原始事件、把投影标记为待处理并允许后台重试。HTTP 响应 SHALL 区分完全成功、原始已接收但投影待处理以及原始写入失败。

#### Scenario: 投影写入失败
- **WHEN** 原始事件事务成功但 ActivityWatch 投影写入失败
- **THEN** 系统保留原始事件并返回原始已接收、投影待处理的成功接收状态，后台随后能够补齐投影

#### Scenario: 原始写入失败
- **WHEN** 原始事件无法完成持久化事务
- **THEN** 系统返回失败，不更新 ActivityWatch 投影，并把对应 collector 状态置为 degraded 或 blocked

### Requirement: SPEC-RAW-005 月度分区与封存完整性
系统 SHALL 按 `receivedAt` 的 UTC 月份写入唯一活动分区，跨月后 SHALL 创建新活动分区并封存旧分区。封存 MUST 在完整性检查、事件数量与边界核对、文件摘要和 manifest 成功后完成；封存中断或校验失败 MUST NOT 删除旧分区或可恢复文件，并 SHALL 在后续启动恢复或隔离。

#### Scenario: UTC 月份切换
- **WHEN** 第一条事件在新的 UTC 月份成功接收
- **THEN** 该事件写入新月份活动分区，上一月份分区在完整性验证后变为只读封存状态

#### Scenario: 封存校验失败
- **WHEN** 旧分区的完整性、计数或摘要验证失败
- **THEN** 系统保留全部相关文件、报告隔离状态且不把该分区标记为健康封存

### Requirement: SPEC-RAW-006 原始事件桌面查询
系统 SHALL 仅通过受保护的桌面 API 提供原始事件查询。查询 MUST 要求 bucket、开始时间和结束时间，使用稳定游标分页并限制时间范围和单页数量；响应 SHALL 返回事件字段、下一页游标和覆盖信息。现有 ActivityWatch events、AQL、WikiTools 和 Agent 工具 MUST 继续查询派生投影，且 MUST NOT 暴露逐 heartbeat 原始查询能力。

#### Scenario: 有界原始查询
- **WHEN** 已认证桌面客户端使用合法 bucket、时间范围、limit 和 cursor 查询原始事件
- **THEN** 系统按接收时间与事件 ID 的稳定顺序返回一页结果和可选下一页游标，无遗漏或重复

#### Scenario: 禁止无界查询
- **WHEN** 原始查询缺少 bucket 或时间边界，或请求范围和页大小超过配置上限
- **THEN** 系统返回参数错误且不扫描全部永久历史

#### Scenario: AQL 仍查询投影
- **WHEN** 用户通过 AQL 或 ActivityWatch Agent 工具查询同一 bucket
- **THEN** 返回 heartbeat 合并后的 ActivityWatch 投影，而不是逐条原始事件

### Requirement: SPEC-RAW-007 原始查询强制桌面凭据
原始事件桌面 API MUST 要求本次受管桌面启动配置的有效 header token 或会话 cookie。后端未配置启动 token 时该接口 MUST fail-closed；原始查询 MUST NOT 接受 query 参数 token，且认证失败响应 MUST NOT 泄露事件是否存在。

#### Scenario: 后端没有启动 token
- **WHEN** 后端以未配置 desktop token 的方式运行并收到原始事件查询
- **THEN** 接口返回能力不可用，且不返回原始事件、计数或覆盖信息

#### Scenario: 凭据缺失或错误
- **WHEN** 请求没有有效 header token 或会话 cookie
- **THEN** 接口返回未认证响应，响应不透露目标 bucket 是否存在

### Requirement: SPEC-RAW-008 嵌入式模式边界
永久原始事件保证 SHALL 仅在应用控制采集写入链路和数据目录的嵌入式 ActivityWatch 模式启用。外部 ActivityWatch 模式 MUST 明确报告原始事件能力不可用，MUST NOT 声称外部服务中的事件已被永久原始层保存。

#### Scenario: 外部 ActivityWatch 模式
- **WHEN** 应用以外部 ActivityWatch 模式启动
- **THEN** 原始存储和查询状态报告 unavailable，原始查询不代理或伪造外部服务数据

### Requirement: SPEC-RAW-009 磁盘压力不得触发删除
系统 SHALL 监控原始目录可用空间和增长状态。达到警告阈值时 SHALL 报告告警；达到阻断阈值或无法安全提交下一事务时 SHALL 暂停非必要派生工作并阻止新的原始采集。任何磁盘压力处理 MUST NOT 自动删除或覆盖最旧原始事件。

#### Scenario: 磁盘达到警告阈值
- **WHEN** 原始数据目录剩余空间低于警告阈值但仍高于阻断阈值
- **THEN** 桌面状态报告告警，原始采集继续且历史事件不被删除

#### Scenario: 磁盘不足以提交
- **WHEN** 剩余空间低于阻断阈值或原始事务因空间不足失败
- **THEN** 新采集进入 blocked，派生链路不产生未落原始层的事件，既有原始事件保持不变

### Requirement: SPEC-RAW-010 派生投影可重建
ActivityWatch 事件、Wiki 数据和语义索引 SHALL 被视为可重建派生数据。ActivityWatch 投影 SHALL 记录投影版本和连续原始覆盖位置；重建 SHALL 写入独立目标、验证覆盖与一致性，并在成功后原子切换，MUST NOT 就地修改原始分区。

#### Scenario: ActivityWatch 投影损坏
- **WHEN** 用户请求从健康原始分区重建 ActivityWatch 投影
- **THEN** 系统按稳定原始顺序生成并验证新投影，成功切换后查询恢复且原始分区未改变

#### Scenario: 重建中断
- **WHEN** 投影重建在完成验证和切换前中断
- **THEN** 当前有效投影和全部原始事件保持可用，未完成目标可在后续安全重试或作为派生文件清理

### Requirement: SPEC-RAW-011 开发阶段启用边界
系统 SHALL 从永久原始事件能力正式启用后的第一条合规事件开始建立事实源，MUST NOT 自动扫描、导入、修改或删除启用前的开发期 `aw.db` 或旧 bucket 数据库，也 MUST NOT 为这些开发数据增加兼容读取或精度标记。

#### Scenario: 首次启用原始事件层
- **WHEN** 开发环境第一次以新原始事件能力启动且目录中存在旧开发数据库
- **THEN** 新原始层从当前新事件开始记录，不导入、不转换且不自动删除旧开发数据库

### Requirement: SPEC-RAW-012 状态与安全可观测性
桌面状态 SHALL 提供原始能力状态、活动分区、分区数量、事件时间边界、总大小、增长速度、可用空间、投影延迟和最近完整性结果。日志与指标 MUST NOT 包含事件 data、窗口标题、上下文标题、文件路径、认证信息或完整请求。

#### Scenario: 查看原始存储状态
- **WHEN** 已认证桌面客户端请求系统状态
- **THEN** 响应包含可诊断的容量、健康和投影进度，不包含任何原始事件 payload
