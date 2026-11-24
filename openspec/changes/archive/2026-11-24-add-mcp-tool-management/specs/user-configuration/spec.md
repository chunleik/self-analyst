## MODIFIED Requirements

### Requirement: SPEC-CFG-INTEGRATIONS-002 秘密与兼容配置隔离
连接文件及导出 SHALL 只含公共配置和受控凭据引用，不含授权码、Bearer 秘密或任意系统保险库访问路径。导入 SHALL 清除原机器凭据引用和授权结果并默认关闭。旧 `websearch.*` 与 `WEBSEARCH_*` 配置 SHALL 退役，不作为有效配置、凭据或新连接来源；旧 `legacy-websearch` 身份不得重新注册，历史批准不得授权新连接。普通 TOML 保存不得覆盖连接文件或秘密。

#### Scenario: 配置备份在新电脑恢复
- **WHEN** 用户导入导出的邮箱/MCP 配置
- **THEN** 元数据作为关闭的草稿恢复，需要重新配置凭据、核对目标和批准工具，不继承原机器授权

#### Scenario: 保存高级 TOML
- **WHEN** 用户通过高级配置或模型设置保存无关 TOML 修改
- **THEN** 不重写连接 JSON，不删除邮箱账户或系统保险库项

#### Scenario: 旧搜索配置残留
- **WHEN** 旧 TOML 中保留 `websearch.*` 三键或进程仍继承旧搜索环境变量
- **THEN** 不读取为有效连接、不写入连接文件、不尝试传输旧凭据，也不为旧键报告重启要求
