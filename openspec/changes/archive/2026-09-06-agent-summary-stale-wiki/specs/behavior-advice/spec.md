## MODIFIED Requirements

### Requirement: SPEC-ADV-GEN-001..006 生成与本地降级
需要生成新建议时，每次 SHALL 最多生成一条最重要建议。若摘要快照仍有效且当前窗事实未触发失效，系统 SHALL 复用快照中的建议，MUST NOT 仅因页面重开、Agent tab 切回或定时刷新而重新生成。LLM 可用时 MAY 负责自然语言措辞，但 MUST NOT 覆盖本地计算指标；LLM 不可用或失败时 SHALL 使用本地规则生成朴素建议，无法形成可靠规则时返回 empty。本地规则 SHOULD 覆盖分心时长下降、窗口切换显著上升和连续晚间娱乐偏高等场景。

#### Scenario: LLM 改写事实
- **WHEN** 模型返回与本地统计冲突的数值或趋势
- **THEN** 系统保留本地事实，并仅采用安全可解释的措辞

#### Scenario: LLM 不可用
- **WHEN** 模型未配置、预算阻断或调用失败
- **THEN** 后端返回本地建议或 empty，summary 主响应继续成功

#### Scenario: 快照建议仍有效
- **WHEN** 客户端再次请求 summary 且快照建议未因当前窗事实变化失效
- **THEN** 响应复用快照中的 behaviorAdvice，不发起新的建议生成
