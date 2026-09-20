## MODIFIED Requirements

### Requirement: SPEC-I18N-PROMPT-004、005 日期与兜底文本

提示词中的 current time 和 UTC→本地时间说明 SHALL 按有效语言使用对应 Locale 文本。主 Agent 每轮调用时提供的 current time SHALL 取该轮调用时的系统本地时间与时区，MUST NOT 沿用 Agent 初始化或前一轮的时间。预算阻断、Wiki
不可用和其它确定性提示 SHALL 跟随有效语言；由用户原文构造的确定性摘要 SHALL 保持用户语言。

#### Scenario: English 时间文本

- **WHEN** effective language 为 en 且 prompt 注入当前时间
- **THEN** 日期和时间说明使用英文 Locale 与 English 文案

#### Scenario: 长时间运行后的当前时间

- **WHEN** 同一 Agent 在不同本地时间先后处理两轮对话
- **THEN** 后一轮提供给模型的 current time 为后一轮的系统本地时间与时区，不复用前一轮或启动时的值
