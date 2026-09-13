## MODIFIED Requirements

### Requirement: SPEC-ARCH-001、SPEC-ARCH-004 依赖与事件查询边界
应用服务依赖 SHOULD 通过构造器注入；进程级原生资源 MAY 使用具有关闭与失败降级路径的受控共享实例。
Agent 为模型分析查询事件投影时 SHALL 通过注册的 EventQueryTools；窗口、AFK、标题与文件 heartbeat 等
采集链 MAY 直接调用本地兼容 HTTP API。文档导出 SHALL 由后端受控只读适配器消费数据快照并直接写入文件，
其工具结果 SHALL 只返回文档元数据、计数和覆盖摘要，不把逐条原始记录返回模型。

#### Scenario: Agent 查询事件投影
- **WHEN** Agent 为模型分析需要列出 bucket、查询事件或执行 AQL
- **THEN** 调用通过 EventQueryTools 发往配置的兼容 API base URL，不直接访问数据库

#### Scenario: 后端批量生成数据文档
- **WHEN** 受管会话请求将符合导出条件的记录生成文档
- **THEN** 后端通过受控只读适配器生成文件，模型只获得成果元数据，不获得逐条原始记录

## ADDED Requirements

### Requirement: SPEC-AGT-DOC-001 文档工具与可信执行身份
Agent SHALL 在文档服务和受管桌面会话可用时注册文档列表查询、文档生成、数据导出和受管生成源读取工具，工具 SHALL 使用既有串行执行、聊天 gate、模型版本和预算规则。会话与用户轮次身份 SHALL 由运行上下文提供，MUST NOT 使用模型参数作为归属权威。工具 SHALL 返回有界的状态、文件标识和元数据，不返回二进制、任意文件路径或逐条原始事件。依赖不可用 SHALL 明确报告且不阻止其它 Agent 工具使用。

#### Scenario: 正常生成
- **WHEN** 用户在受管会话要求生成文档且所需依赖可用
- **THEN** Agent 调用文档工具，成果归属发起会话与轮次，最终文件通过服务端关联提供

#### Scenario: 缺少会话或文档依赖
- **WHEN** 无会话聊天或文档服务不可用
- **THEN** 不创建无归属文件，不伪造下载结果，其它可用聊天能力保持可用

#### Scenario: 取消生成
- **WHEN** 用户取消正在生成文档的匹配聊天轮次
- **THEN** 未完成生成停止并释放资源，不影响其它轮次文件，已发布成果按文档生命周期保留
