## REMOVED Requirements

### Requirement: SPEC-NEO-001 默认关闭与安全配置
**Reason**: 产品下线可选 Neo4j 图投影同步功能，不再维护 Neo4j 连接配置、凭据环境变量解析或驱动集成。
**Migration**: 无需迁移；用户旧 `config.toml` 中残留的合法 `neo4j.*` 配置键将按普通未知键处理，不生效且不阻塞启动或保存，用户可按需手工删除。

### Requirement: SPEC-NEO-002 手工确认与有界范围
**Reason**: 产品下线可选 Neo4j 图投影同步功能，配置窗口不再提供“Neo4j 同步”页签，也不再提供手工图谱发送与确认流程。
**Migration**: 继续使用桌面“知识”页面及 Agent 本地本体查询工具浏览和管理个人知识图谱。

### Requirement: SPEC-NEO-003 原子隔离快照
**Reason**: 产品下线可选 Neo4j 图投影同步功能，不再向外部 Neo4j 实例写入或维护图投影快照。
**Migration**: 本地 SQLite、Wiki、memory.json 与本体存储不受影响；若此前曾手工同步至外部 Neo4j 实例，远端历史数据由用户自行在目标数据库中管理或清理。
