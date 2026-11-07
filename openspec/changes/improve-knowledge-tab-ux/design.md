## Context

知识页由 `ontology.js` 以原生 DOM 构建，无框架；样式在 `ontology.css`，只能使用共享主题中已定义的设计令牌（`ontology-style.test.mjs` 校验）。后端 `/desktop/ontology` 接口已提供实体分页、详情及关系分页（每页 20 条，关系项含 `assertion` 与 `other` 实体摘要）、覆盖信息和重建入口。本期不改后端。动机见 proposal.md。

## Goals / Non-Goals

**Goals:**
- 在不改接口的前提下，重组信息层级、术语和交互，满足修改后的 SPEC-ONTO-009 与新增的 SPEC-ONTO-013。
- 保持现有测试覆盖的安全渲染、并发查询保护、表单重试等行为。

**Non-Goals:**
- 独立的“待整理”收件箱视图、批量归类、实体统计计数（活动数、最近活跃时间）——留待第二期，需要新接口。
- 引入 UI 框架或第三方组件库。

## Decisions

1. **关系分组在当前页内进行。** 关系接口按页返回 20 条，客户端按 `other.type` 分组展示，分组计数为当前页数量，分页器保持在分组之下并显示总数。备选方案是按类型分别请求，但需要新增类型过滤参数，超出本期范围。
2. **断言状态映射。** `status === "candidate"` 优先显示“待确认”；否则按 `claimType` 映射：`confirmed` → 已确认（✓）、`inferred` → 自动推测（~）、`observed`/`legacy` → 自动记录（•）。图标与文字同时出现，不只依赖颜色。
3. **关系名称句子化。** 用 `ontology.relationLabel.<predicate>.<otherType>` 查表，缺失时回退到 `ontology.predicate.<predicate>`；文案键在中英文中成对添加，并由现有双语测试校验。
4. **菜单使用轻量的可访问按钮菜单。** 按钮带 `aria-haspopup="menu"` 和 `aria-expanded`，菜单项为 `role="menuitem"`；支持 Esc 关闭、点击外部关闭、方向键移动。不用 `<details>`，因为它难以处理外部点击关闭与焦点归还。
5. **即时搜索。** 输入框以约 300 ms 防抖触发 `loadKnowledge`，沿用 `knowledge.generation` 丢弃过期结果；回车立即触发。目标选择器复用同一防抖逻辑，结果列表实现为 `role="listbox"` 组合框，用 `aria-activedescendant` 支持键盘选择，替代 `select size=5` 加独立按钮。
6. **时间预设在客户端计算。** 预设换算为 `start`/`end` ISO 时间，沿用现有查询参数；仅在“自定义”时显示两个 `datetime-local` 输入框。
7. **确认对话框复用 `knowledgeDialog`。** 删除与重建的确认都走应用内 `<dialog>`，替代 `window.confirm`，键盘与焦点行为保持一致。
8. **刷新保留位置。** 关系确认或否定后仍重新加载列表与详情以保证一致性，但在重绘前记录列表与详情的 `scrollTop` 并在重绘后恢复。不做局部 DOM 修补，以避免状态漂移。

## Risks / Trade-offs

- [分组计数只反映当前页，可能被误读为总数] → 分组标题显示本页数量，分页器显示“共 N 条”。
- [防抖搜索增加请求次数] → 300 ms 防抖加过期结果丢弃；查询本身有界（limit 30）。
- [术语调整影响老用户的认知] → 同步更新 `docs/personal-ontology.md`；同一概念只使用一种叫法。
- [菜单与组合框是自研组件，存在可访问性回归风险] → 在 Node 测试中覆盖 ARIA 属性、Esc 关闭与键盘选择。

## Migration Plan

纯前端改动，无数据迁移；回滚即回退前端文件。
