## MODIFIED Requirements

### Requirement: SPEC-WIKI-SRC-001..006、010、011、013 标题事实来源与预算
事实 SHALL 来自当前主机由永久原始事件投影生成的 window、AFK 和 content bucket；Wiki MUST NOT 直接查询逐 heartbeat 原始事件层。窗口事件 SHALL 用于应用耗时、切换次数和窗口标题样本；AFK 用于非活跃时间；content 只提供 app、系统标题、可选上下文标题和种类。任一投影 bucket 缺失或查询失败时，其他可用事实 MAY 继续生成。单次 prompt 输入 SHALL 受 `wiki.prompt.maxContentChars` 限制，单个窗口标题样本默认最多 160 字符；上下文标题 SHALL 按 app、effectiveTitle、contextKind 去重。Wiki MUST NOT 读取 `text_content`。

#### Scenario: content bucket 含旧正文
- **WHEN** 内容事件投影同时具有标题字段和旧 `text_content`
- **THEN** Wiki facts 只使用标题投影，prompt 和数据库不包含旧正文

#### Scenario: 部分 bucket 缺失
- **WHEN** AFK 或 content 投影 bucket 不存在，但 window 投影 bucket 有可用事件
- **THEN** 系统使用可用指标继续构造 facts，缺失指标置零或为空

#### Scenario: Wiki 不读取永久原始层
- **WHEN** Wiki 生成小时、半天或天级事实
- **THEN** 系统读取 ActivityWatch 投影且不调用桌面原始事件查询 API

## ADDED Requirements

### Requirement: SPEC-WIKI-RAW-001 Wiki 派生版本与覆盖信息
每个新生成的 Wiki 条目 SHALL 记录事实构建版本、所消费 ActivityWatch 投影版本以及各来源 bucket 的时间覆盖或缺失状态。Wiki 重建 MUST 只修改 Wiki 和语义派生数据，不得修改永久原始事件；覆盖不完整时查询结果 SHALL 能向 Agent 和桌面说明不完整性。

#### Scenario: 投影版本升级后重建 Wiki
- **WHEN** ActivityWatch 投影算法或 Wiki 事实构建版本发生变化并触发重建
- **THEN** 新条目记录新版本和覆盖信息，旧 Wiki 派生数据可被替换，但永久原始事件保持不变

#### Scenario: 原始完整但投影尚有延迟
- **WHEN** 时间段内存在已接收但尚未投影的原始事件
- **THEN** Wiki 覆盖信息标记投影延迟，不把当前摘要描述为完整结果
