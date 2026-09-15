## ADDED Requirements

### Requirement: SPEC-ADV-STAT-001 统计日与有效耗时
行为建议的按日分组及日均值 SHALL 使用本地 04:00 统计日；应用、娱乐及晚间耗时 SHALL 按 activity-statistics 契约裁剪和排除 AFK。晚间娱乐 SHALL 统计本地 22:00 至次日 04:00 的有效娱乐活动，并归属开始日。未知应用 MUST NOT 被推断为娱乐或生产力应用；覆盖不足时 SHALL 报告依据不足，无法形成可靠比较时返回 empty。

#### Scenario: 凌晨娱乐
- **WHEN** 用户在凌晨 01:00 至 02:00 使用娱乐应用，其中半小时为 AFK
- **THEN** 前一统计日获得半小时有效晚间娱乐时长

#### Scenario: 不完整比较
- **WHEN** AFK 查询失败导致近期与基准时段无法可靠比较
- **THEN** 建议不将未扣除 AFK 的差异描述为已确认行为趋势
