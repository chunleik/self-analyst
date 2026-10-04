## MODIFIED Requirements

### Requirement: SPEC-ONTO-011 隐私边界
本体 SHALL 只消费允许的标题派生信息、已授权记忆和用户输入；不得读取普通文件正文、恢复 OCR/UIA 原文或持久化完整 prompt。完整图谱 MUST NOT 自动外发；只有用户显式启用可选 Neo4j 同步，并在手工操作中确认目标和数据范围后，才允许发送当前有效、有界且不包含敏感记忆的图投影。本体建立与重建 SHALL 可离线运行且不额外请求模型或 embedding。

#### Scenario: 无模型配置
- **WHEN** 本地已有允许的 Wiki 来源但没有模型配置
- **THEN** 用户仍可管理实体、建立关系、查询和重建本体

#### Scenario: 未授权外发
- **WHEN** 未启用 Neo4j 同步或用户未确认本次发送
- **THEN** 本体查询、重建与配置操作均不外发图谱
