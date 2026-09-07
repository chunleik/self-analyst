## MODIFIED Requirements

### Requirement: SPEC-ARCH-001、SPEC-ARCH-004 依赖与 ActivityWatch 查询边界
应用服务依赖 SHOULD 通过构造器注入；进程级原生资源 MAY 使用具有关闭与失败降级路径的受控共享实例。
Agent 的事件投影查询 SHALL 通过注册的 EventQueryTools；窗口、AFK、标题与文件 heartbeat 等
采集链 MAY 直接调用本地兼容 HTTP API。

#### Scenario: Agent 查询 ActivityWatch
- **WHEN** Agent 需要列出 bucket、查询事件或执行 AQL
- **THEN** 调用通过 EventQueryTools 发往配置的兼容 API base URL，不直接访问数据库

### Requirement: SPEC-AGT-003 工具注册与顺序执行
Agent SHALL 注册 EventQueryTools，并在相应依赖可用时注册 ConfigTools、WikiTools、FileTools 和
联网搜索 MCP。工具执行 SHALL 使用串行 toolkit，避免共享本地 store/connection 的并发访问。
可选工具初始化失败 MUST NOT 阻止其余 Agent 能力启动；失败客户端 SHALL 被关闭或跳过。

#### Scenario: 可选 Wiki/File/Config 工具不可用
- **WHEN** 某个可选 store 或配置入口未创建
- **THEN** Agent 不注册对应工具，其它已满足依赖的工具仍可使用

#### Scenario: 联网搜索初始化失败
- **WHEN** websearch 已启用但 MCP 连接或注册失败
- **THEN** Agent 跳过联网搜索并继续使用本地工具
