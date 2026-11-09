# 连接配置增量规格

## ADDED Requirements

### Requirement: SPEC-CFG-INTEGRATIONS-001 独立版本化连接配置
新增外部 MCP 和邮箱元数据 SHALL 保存于运行根的 `data/config/integrations.json`，与现有 `config.toml` 各有唯一职责，不通过普通点分键读写复杂对象。文件 SHALL 含明确格式版本和保存 revision；未知版本、损坏、非法类型、重复 ID 或过期 revision MUST 拒绝保存且保留原文件。缺失文件 SHALL 等价没有新连接，不自动创建或联网。

#### Scenario: 并发编辑同一连接
- **WHEN** 两个界面基于同一 revision 修改连接，后一个在新版本发布后保存
- **THEN** 后一个收到冲突并保留草稿，不覆盖较新的账户或权限设置

#### Scenario: 未知文件版本
- **WHEN** 已有连接文件采用当前版本无法理解的 schemaVersion
- **THEN** 保留文件并禁用新连接配置写入，不重置成空文件或部分导入

### Requirement: SPEC-CFG-INTEGRATIONS-002 秘密与兼容配置隔离
新连接文件及导出 SHALL 只含公共配置和受控凭据引用，不含授权码、Bearer 秘密或任意系统保险库访问路径。导入 SHALL 清除原机器凭据引用和授权结果并默认关闭。已有 `websearch.*` SHALL 保持唯一兼容配置来源，新文件不得重定义其保留连接身份，普通 TOML 保存也不得覆盖新连接文件或秘密。

#### Scenario: 配置备份在新电脑恢复
- **WHEN** 用户导入导出的邮箱/MCP 配置
- **THEN** 元数据作为关闭的草稿恢复，需要重新配置凭据、核对目标和批准工具，不继承原机器授权

#### Scenario: 保存高级 TOML
- **WHEN** 用户通过高级配置或模型设置保存无关 TOML 修改
- **THEN** 不重写连接 JSON，不删除邮箱账户或系统保险库项

### Requirement: SPEC-CFG-INTEGRATIONS-003 草稿和真实生效状态
“连接与工具” SHALL 是独立设置视图，默认打开模型设置的既有行为不变。各视图的脏态、关闭确认和迟到响应 SHALL 独立处理。连接保存 SHALL 返回实际 revision、启用/认证/批准状态及应用阶段；工具目录变更在下一轮生效而撤权在下一次发送前生效，不能把保存成功或诊断成功解释为全部运行实例已切换。

#### Scenario: 从未保存的邮箱表单切换
- **WHEN** 用户在邮箱表单有未保存内容时切换到模型或高级配置
- **THEN** 先确认放弃，取消则保留原草稿；确认后重新读取目标视图，不自动保存秘密或连接

#### Scenario: 旧回答尚未完成
- **WHEN** 用户保存新的已批准工具目录而旧回答仍在执行
- **THEN** 界面说明新轮次采用新目录，旧轮次保留定义但仍受即时撤权检查
