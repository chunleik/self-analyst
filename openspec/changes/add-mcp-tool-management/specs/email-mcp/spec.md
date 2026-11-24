## Purpose

定义 SelfAnalyst 内置的多账号邮箱 MCP 服务，通过纯 Java IMAP/SMTP 协议提供零外部运行时依赖的邮件查询、阅读、草稿创建与受控发信能力，同时严格守住凭据安全与本地数据隐私边界。

## ADDED Requirements

### Requirement: SPEC-MAIL-ACC-001..004 多账号内置邮箱与预设
系统 SHALL 提供零外部运行时依赖的内置邮箱 MCP Provider（`BUILTIN_EMAIL`），支持用户添加并并行管理多个独立邮箱账号；每个邮箱账号对应独立的 MCP 服务实例、独立的 IMAP/SMTP 配置、独立的凭据以及独立的对内/对外开关与工具权限。添加向导 SHALL 提供常见邮箱服务商预设（至少涵盖 QQ 邮箱、163/126 邮箱、Gmail、Outlook/Exchange、企业微信邮箱、阿里企业邮箱及自定义 IMAP/SMTP），自动填充主机、端口与加密方式。连接默认 MUST 使用 TLS/SSL（IMAPS/SMTPS/STARTTLS），不得在未显式配置的情况下降级为明文传输。

#### Scenario: 并行配置工作邮箱与个人邮箱
- **WHEN** 用户分别添加两个不同账号的内置邮箱 MCP 实例并启用对内访问
- **THEN** 两个账号独立建立连接与隔离凭据，Agent 的工具列表通过服务标识区分两个邮箱账号

#### Scenario: 使用服务商预设配置邮箱
- **WHEN** 用户在添加邮箱向导中选择预设服务商并输入邮箱地址与授权码
- **THEN** 表单自动填充对应的 IMAP/SMTP 主机、端口与安全传输模式，保存前可独立测试连通性

### Requirement: SPEC-MAIL-TOOL-001..005 邮箱工具集与默认安全策略
每个邮箱 MCP 实例 SHALL 提供以下标准工具：`list_folders`（列出文件夹与未读统计）、`search_emails`（按文件夹、发件人、主题、关键词、时间范围、未读状态检索邮件元数据与摘要）、`read_email`（按文件夹与 UID 读取单封邮件详情）、`create_draft`（在草稿箱保存邮件草稿）以及 `send_email`（经 SMTP 发送邮件）。新建邮箱实例时，只读工具与 `create_draft` SHALL 默认启用，`send_email` MUST 默认处于禁用列表，仅在用户于工具权限界面显式开启后方可使用。

#### Scenario: 默认新建邮箱账号的工具权限
- **WHEN** 用户新建一个内置邮箱 MCP 实例且未修改默认工具权限
- **THEN** `list_folders`、`search_emails`、`read_email` 与 `create_draft` 可用，`send_email` 处于禁用状态且拒绝调用

#### Scenario: 用户显式开启发信工具
- **WHEN** 用户在对应邮箱账号的工具权限中取消禁用 `send_email`
- **THEN** 该账号的 `send_email` 工具热生效并允许按指定收件人、主题与正文通过 SMTP 发送邮件

#### Scenario: 检索与阅读大体积或 HTML 邮件
- **WHEN** Agent 或外部 MCP 客户端调用 `search_emails` 或 `read_email`
- **THEN** 检索结果受最大返回条数上限约束，邮件正文优先返回纯文本或清洗后的 HTML 文本并受最大字符上限截断，默认不自动下载附件正文

### Requirement: SPEC-MAIL-PRV-001..003 邮箱数据隐私与连接容错
邮箱内容 SHALL 仅在工具调用期间按需从邮件服务器读取并返回给当前调用方，MUST NOT 自动持久化到 `events.db`、Wiki 摘要或本地文件索引中。所有 IMAP/SMTP 操作 MUST 具有有限连接与读取超时；认证失败、服务器不可达或邮箱未开启 IMAP 服务时，系统 SHALL 返回清晰且不含密码或授权码的诊断错误，且不得影响应用其他功能。

#### Scenario: 邮件读取不污染本地事件与 Wiki 存储
- **WHEN** 用户通过 Agent 查询并阅读多封邮件
- **THEN** 邮件正文与发件人信息仅存在于本次工具返回与对话上下文中，不写入 `events.db` 或 Wiki 持久化数据

#### Scenario: 授权码错误或 IMAP 未开启
- **WHEN** 用户测试或调用一个密码错误或未开启 IMAP 的邮箱账号
- **THEN** 系统在超时内返回明确的认证或连接诊断信息，且日志与错误响应中不包含明文密码或授权码
