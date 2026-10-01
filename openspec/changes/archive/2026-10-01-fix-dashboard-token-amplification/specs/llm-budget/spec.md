## ADDED Requirements

### Requirement: SPEC-BUDGET-SOURCE-001 用量来源与预算总额
用量 SHALL 区分供应商确认的真实 usage、成功完成但缺少 usage 的估算、未知失败或取消的保守预留，以及缺少来源字段的历史记账。API SHALL 在根对象和每个类别提供 actualTokens、estimatedTokens、reservedTokens、unclassifiedTokens；四者之和 SHALL 等于原有 inputTokens/outputTokens 的累计总额。原有 totalTokens、预算判定与 input/output/calls SHALL 保持兼容，并继续使用保守记账总额。未知失败预留及历史记账 MUST NOT 标记为供应商已确认消耗。

#### Scenario: 成功与未知失败
- **WHEN** 一次调用返回真实 usage，一次成功调用没有 usage，另一次调用超时且没有 usage
- **THEN** 三次调用分别归入真实、估算和预留，预算包含三者且各自至多记录一次

#### Scenario: 旧记录重启
- **WHEN** 加载只有 input/output/calls 的旧用量文件
- **THEN** 总额和预算保持不变，旧 token 归入 unclassifiedTokens，后续调用按其来源累计

#### Scenario: 用量展示
- **WHEN** 用户查看桌面用量
- **THEN** 显示真实、估算、失败预留和历史未分类明细，并说明记账总额不等于供应商账单
