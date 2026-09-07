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
- **THEN** 系统读取事件投影且不调用桌面原始事件查询 API

### Requirement: SPEC-WIKI-RAW-001 Wiki 派生版本与覆盖信息
每个新生成的 Wiki 条目 SHALL 记录事实构建版本、所消费事件投影版本以及各来源 bucket 的时间覆盖或缺失状态。Wiki 重建 MUST 只修改 Wiki 和语义派生数据，不得修改永久原始事件；覆盖不完整时查询结果 SHALL 能向 Agent 和桌面说明不完整性。

#### Scenario: 投影版本升级后重建 Wiki
- **WHEN** 事件投影算法或 Wiki 事实构建版本发生变化并触发重建
- **THEN** 新条目记录新版本和覆盖信息，旧 Wiki 派生数据可被替换，但永久原始事件保持不变

#### Scenario: 原始完整但投影尚有延迟
- **WHEN** 时间段内存在已接收但尚未投影的原始事件
- **THEN** Wiki 覆盖信息标记投影延迟，不把当前摘要描述为完整结果

### Requirement: SPEC-WIKI-AGT-001..005 Agent 使用 Wiki
Agent 指令 SHALL 说明 Wiki 用于历史复盘。用户询问明确时间段的任务、趋势或对比时，Agent SHOULD
优先使用时间查询；只有主题而无时间范围时 SHOULD 尝试语义搜索。工具返回 pending/failed 时 MUST
说明结果不完整。Agent MUST NOT 在 Wiki 无结果时直接声称没有数据，除非必要的事件原始查询
也无可用事实。

#### Scenario: Wiki 区间未完成
- **WHEN** 查询结果包含 pending 或 failed 周期
- **THEN** Agent 明确说明仍在生成或生成失败，不把部分结果描述为完整历史

### Requirement: SPEC-WIKI-NON-001、004..006 功能边界
当前能力 MUST NOT 依赖独立 Wiki 浏览页、手动编辑摘要、跨设备同步、账号或云端存储才能工作。
历史摘要 SHALL 默认永久保留，除非未来显式引入保留策略。当前系统 MAY 把完成摘要投影到本地
事件 summary bucket 供时间线消费；该行为不改变 llm-wiki.db 的摘要权威。

#### Scenario: 用户需要历史复盘
- **WHEN** 没有独立 Wiki 页面
- **THEN** 用户仍可通过 Agent、时间线投影和 WikiTools 查询摘要

### Requirement: SPEC-WIKI-DSK-001 桌面时间轴消费已结束 Wiki
桌面时间轴对已结束时段 SHALL 以 `llm-wiki.db` 中对应层级的 `SUMMARIZED` 条目为摘要权威，MUST NOT 为这些时段再次现场生成 LLM 文案。父级跨度未完成时 SHALL 拼装可用子级 `SUMMARIZED` 条目；PENDING、FAILED、SKIPPED 或缺失 MUST NOT 阻塞当前窗。该消费路径 MUST NOT 改变 Wiki worker 的发现、生成或补算职责，也不得把事件 summary bucket 投影当作比 Wiki 更高的权威。

#### Scenario: 已结束日复用 Wiki
- **WHEN** 昨天存在 SUMMARIZED DAY 条目且客户端请求桌面 summary
- **THEN** 时间轴昨天条目使用该 Wiki 摘要，不对该日发起新的 summary LLM 调用

#### Scenario: 未完成跨度不阻塞页面
- **WHEN** 本周 WEEK 条目仍为 PENDING 但若干已结束 DAY 已 SUMMARIZED
- **THEN** 时间轴使用这些 DAY 摘要与当前窗拼装本周，并继续返回当前窗
