## MODIFIED Requirements

### Requirement: SPEC-ONTO-011 隐私边界
本体 SHALL 只消费允许的标题派生信息、已授权记忆和用户输入；不得读取普通文件正文、恢复 OCR/UIA 原文或持久化完整 prompt。系统 MUST NOT 提供将完整图谱外发至外部图数据库的同步通道。本体建立与重建 SHALL 可离线运行且不额外请求模型或 embedding。

#### Scenario: 无模型配置
- **WHEN** 本地已有允许的 Wiki 来源但没有模型配置
- **THEN** 用户仍可管理实体、建立关系、查询和重建本体

#### Scenario: 未授权外发
- **WHEN** 用户查询、编辑或重建个人本体
- **THEN** 全部操作仅在本地完成，不向外部图数据库发起网络连接或外发图谱快照
