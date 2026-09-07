## MODIFIED Requirements

### Requirement: SPEC-WS-GOAL-001..005 可选外部信息能力
启用并成功注册时，Agent SHALL 能使用 MCP 搜索工具获取本地数据无法覆盖的实时外部信息。联网搜索
SHALL 默认关闭，并支持配置开关、单一端点和可选 API key。初始化失败 MUST NOT 阻止 Agent 启动。
涉及用户个人活动、会话、Wiki 或文件元数据的问题，Agent SHOULD 优先使用相应本地工具，仅在需要
外部事实时使用网络搜索。

#### Scenario: 搜索已启用且可用
- **WHEN** 用户询问需要最新外部信息的问题
- **THEN** Agent MAY 调用已注册 MCP 搜索工具，并基于结果回答

#### Scenario: 本地活动问题
- **WHEN** 用户询问自己的时间线、应用使用或历史任务
- **THEN** Agent 优先查询事件数据、Wiki 或其它本地工具，不把联网搜索当作个人事实来源

### Requirement: SPEC-WS-AGT-001..003 Agent 工具选择与降级
Agent system prompt SHALL 说明外部实时信息可使用联网搜索，并说明个人活动数据优先本地事件查询与
Wiki 等工具；该说明 MUST NOT 使用 ActivityWatch 品牌名。
搜索未启用或注册失败时，Agent SHALL 继续处理本地数据问题，不声称搜索工具可用。

#### Scenario: 搜索工具缺失
- **WHEN** Agent Toolkit 没有联网搜索工具
- **THEN** 本地事件查询、Wiki、文件、配置和聊天能力不受影响
