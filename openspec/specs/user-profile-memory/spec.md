# 用户档案与本地记忆存储规格

## Purpose

定义 legacy 目标、行为模式、改进记录及扩展长期记忆共用的本地 profile 模型、上下文摘要和 memory.json 原子读写行为。

## Requirements

### Requirement: SPEC-MDL-001 Goal 模型
Goal SHALL 包含服务端生成的短 ID、description、metric、baseline、target、setAt 和 active。创建工厂
SHALL 自动生成 ID、使用当前本地日期并默认 active=true。

#### Scenario: 创建目标
- **WHEN** 用户创建包含描述、指标、基线和目标值的 Goal
- **THEN** 系统生成新 ID、当前 setAt 和 active=true

### Requirement: SPEC-MDL-002 KnownPattern 模型
KnownPattern SHALL 保存 description、evidence、confirmedAt 和 1..10 confidence。调用方 SHALL 在保存前
保证 confidence 位于范围内；当前模型不承诺构造器级强制拒绝。

#### Scenario: 保存已确认模式
- **WHEN** 调用方提交合法模式和 confidence
- **THEN** profile 持久化描述、证据、确认日期和置信度

### Requirement: SPEC-MDL-003 ImprovementLog 模型
ImprovementLog SHALL 保存 goalId、action、outcome 和 observedAt。goalId MAY 引用不存在的 Goal；读取
和保存 MUST NOT 因缺少外键目标失败。

#### Scenario: 独立改进记录
- **WHEN** ImprovementLog 的 goalId 当前不存在
- **THEN** profile 仍可保存和读取该记录

### Requirement: SPEC-MDL-004 上下文摘要
空 profile 的 buildContextSummary SHALL 返回明确“暂无”文案。存在数据时 SHALL 分组输出 active goals、
known patterns 和最近改进记录；改进记录只展示按 observedAt 降序的最近 5 条。扩展 MemoryItem SHALL
按长期记忆规格加入 active 摘要，非 active 项不得出现。

#### Scenario: 空档案
- **WHEN** profile 没有目标、模式、日志或 active 记忆
- **THEN** 摘要明确表示暂无已存储内容

#### Scenario: 多条改进记录
- **WHEN** profile 有超过 5 条 ImprovementLog
- **THEN** 摘要只展示 observedAt 最新的 5 条

### Requirement: SPEC-MDL-005 JSON 兼容格式
LocalDate SHALL 序列化为 ISO-8601 日期字符串，Instant SHALL 使用 ISO 时间；输出 SHALL pretty print，
读取 SHALL 忽略未知字段。缺失新增集合字段 SHALL 使用空集合，旧 goals/patterns/logs MUST 保持兼容。

#### Scenario: 读取未来字段
- **WHEN** memory.json 包含当前模型不认识的额外字段
- **THEN** 已知 profile 数据正常加载，未知字段被忽略

### Requirement: SPEC-MEM-001 MemoryStore 加载
MemoryStore SHALL 在父目录中使用固定 `memory.json`。父目录不存在时 SHALL 创建；文件不存在时 SHALL
返回空 profile；存在但 JSON 损坏时 SHALL 抛出读取错误，不静默覆盖损坏文件。

#### Scenario: 首次加载
- **WHEN** memory 目录和 memory.json 不存在
- **THEN** 系统创建父目录并返回空 profile，不要求文件立即写盘

#### Scenario: 损坏 JSON
- **WHEN** memory.json 不是可解析 profile
- **THEN** 加载失败并保留原文件供诊断

### Requirement: SPEC-MEM-002、003 保存与 profile 引用
save SHALL 把当前完整 profile 写入同目录临时文件并替换 memory.json，不做增量追加。profile() SHALL
返回同一内部可变引用；调用方修改后必须调用 save 才能持久化。并发修改 SHALL 由上层服务串行化。

#### Scenario: 修改后保存
- **WHEN** 调用方通过 profile 引用新增 Goal 并调用 save
- **THEN** 重新加载可看到完整 Goal 与其它既有数据
