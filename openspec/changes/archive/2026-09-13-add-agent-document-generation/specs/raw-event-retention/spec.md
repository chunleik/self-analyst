## MODIFIED Requirements

### Requirement: SPEC-RAW-006 原始事件桌面查询
系统 SHALL 仅通过受保护的桌面 API 提供原始事件查询。查询 MUST 要求 bucket、开始时间和结束时间，使用稳定游标分页并限制时间范围和单页数量；响应 SHALL 返回事件字段、下一页游标和覆盖信息。现有事件查询、AQL、WikiTools 和 Agent 查询工具 MUST 继续查询派生投影，且 MUST NOT 向模型暴露逐 heartbeat 原始查询结果。受管桌面聊天的文档导出工具 SHALL 允许指定有界原始查询条件，由后端直接读取并生成受保护文件，只向 Agent 返回文件元数据、计数和覆盖摘要；该路径 SHALL 要求有效受管桌面身份，保持嵌入式模式、原始存储健康和查询预算限制。

#### Scenario: 有界原始查询
- **WHEN** 已认证桌面客户端使用合法 bucket、时间范围、limit 和 cursor 查询原始事件
- **THEN** 系统按接收时间与事件 ID 的稳定顺序返回一页结果和可选下一页游标，无遗漏或重复

#### Scenario: 禁止无界查询
- **WHEN** 原始查询缺少 bucket 或时间边界，或请求范围和页大小超过配置上限
- **THEN** 系统返回参数错误且不扫描全部永久历史

#### Scenario: AQL 仍查询投影
- **WHEN** 用户通过 AQL 或事件查询 Agent 工具查询同一 bucket
- **THEN** 返回 heartbeat 合并后的事件投影，而不是逐条原始事件

#### Scenario: Agent 发起原始文件导出
- **WHEN** 已认证受管桌面会话请求导出合法范围内的原始采集记录
- **THEN** 后端逐页生成文件并关联到该会话，工具结果不包含原始记录正文，文件读取继续要求桌面凭据

#### Scenario: 原始导出不可用
- **WHEN** 未配置 desktop token、缺少有效桌面身份或处于外部事件服务模式
- **THEN** 原始导出明确不可用，不扫描本地 raw 目录，不以派生数据冒充原始数据
