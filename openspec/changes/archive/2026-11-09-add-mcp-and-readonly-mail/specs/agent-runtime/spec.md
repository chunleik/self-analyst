# Agent 集成运行增量规格

## MODIFIED Requirements

### Requirement: SPEC-AGT-003 工具注册与顺序执行
Agent SHALL 注册 EventQueryTools，并在相应依赖可用时注册 ConfigTools、WikiTools、FileTools、已批准的联网搜索/外部 MCP 及已授权的内置邮箱工具。工具执行 SHALL 使用串行 toolkit，避免共享本地 store/connection 的并发访问；外部工具和邮箱调用 SHALL 经过后端权限网关，不直接绕过批准与账户范围检查。可选工具初始化失败 MUST NOT 阻止其余 Agent 能力启动；失败客户端 SHALL 被关闭或跳过。

#### Scenario: 可选 Wiki/File/Config 工具不可用
- **WHEN** 某个可选 store 或配置入口未创建
- **THEN** Agent 不注册对应工具，其它已满足依赖的工具仍可使用

#### Scenario: 联网搜索初始化失败
- **WHEN** websearch 已启用但 MCP 连接或注册失败
- **THEN** Agent 跳过联网搜索并继续使用本地工具

## ADDED Requirements

### Requirement: SPEC-AGT-INT-001 每轮工具快照与即时撤权
每个聊天轮次 SHALL 固定其工具目录版本，后续模型运行版本 SHALL 使用最新已发布目录；正在进行的轮次不得被原地增删工具定义。实际调用 SHALL 重新验证最新权限，轮次快照不延长已撤销的授权。旧连接 SHALL 在在途使用结束后有界释放，应用关闭不再接收新调用。

#### Scenario: 工具后续轮次切换
- **WHEN** 用户批准新工具并随后修改模型配置
- **THEN** 后续聊天使用同一最新工具目录，当前实例与新模型实例不会出现注册遗漏或重复

#### Scenario: 关闭中的工具请求
- **WHEN** 应用进入关闭或连接被撤销后旧轮次请求新调用
- **THEN** 后端拒绝新工作并清理在途资源，不伪造成功结果

### Requirement: SPEC-AGT-INT-002 邮箱来源标记与原生工具限制
模型获得邮箱数据前 SHALL 先可靠记录服务端会话来源标记，标记 SHALL 在恢复、摘要和后续轮次中保留。含邮箱来源的会话 SHALL 只自动执行明确允许的本地只读工具和已授权邮箱读取；语义 embedding 查询 SHALL 拒绝，原生设置/记忆等写入 SHALL 要求受管桌面一次性批准。标记、批准或权限状态无法验证时 MUST fail closed。

#### Scenario: 邮件要求更改配置
- **WHEN** 模型根据邮件文字调用配置或记忆写入工具并宣称用户已同意
- **THEN** 在没有绑定实际参数的用户确认时不执行，工具描述和模型参数不能替代批准

#### Scenario: 标记保存失败
- **WHEN** 首次邮件读取结果准备交给模型，但会话来源标记无法可靠写入
- **THEN** 不向模型提供结果，并返回安全失败，不先发送后补写标记

### Requirement: SPEC-AGT-INT-003 模型出口的统一目标核对
普通聊天、压缩及独立会话摘要使用邮箱来源内容时，SHALL 在每次实际模型发送前检查该运行版本的目标是否已获授权。更换接收目标后 MUST 在用户确认前阻止相关远端生成，允许本地确定性降级；同目标的凭据轮换不得悄悄改变接收身份。模型不可用或授权等待 SHALL 不阻塞本地会话查看和删除。

#### Scenario: 先压缩后生成的聊天
- **WHEN** 含邮箱来源的会话在切换目标后先触发上下文压缩
- **THEN** 压缩开始前检查目标授权，未确认时不把历史发送到新目标，也不先压缩再弹确认
