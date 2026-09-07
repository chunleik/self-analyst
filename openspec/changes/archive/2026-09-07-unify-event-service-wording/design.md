## Context

动机见 proposal.md - Why。本设计只处理一个技术约束：OpenSpec 的 delta 机制对「改名」支持有限，而本次
改动的核心恰恰是改名。实施中已经验证出三条边界：

- capability 无法移动或重命名。`openspec instructions specs` 明确要求 modified capability 使用
  `openspec/specs/` 下的既有路径，并注明「Do not move or rename the capability」。
- 场景（Scenario）无法重命名。`RENAMED Requirements` 只接受 requirement 层的 FROM/TO；若在
  `MODIFIED` 块里改写场景名，`openspec validate --strict` 会判定为「omits scenario(s) the current spec
  still has」并拒绝，因为归档时 MODIFIED 会整块替换、不允许静默丢弃场景。
- 主规格的 `## Purpose` 不受 delta 影响。同一份 instructions 说明既有 capability 的 delta 中不应出现
  `Purpose`，其内容会被忽略，必须直接编辑主规格文件。

## Goals / Non-Goals

目标是在不改变任何可观察行为、不改动稳定标识符的前提下，让契约文本对「自研事件服务」与「第三方
ActivityWatch」的指代不再混淆。

非目标：不重构 capability 的职责划分；不调整 requirement 的粒度或拆分；不借机修订与品牌名无关的措辞；
不触碰归档目录。

## Decisions

### 用 REMOVED + ADDED 表达 capability 重命名

`activitywatch-tools` 的 delta 整体使用 `REMOVED Requirements`（七条各附 Reason 与 Migration），
`event-query-tools` 的 delta 使用 `Purpose` + `ADDED Requirements` 承接同样的七条。归档时旧目录被移除、
新目录建立，SPEC-AW-* 的 ID 与正文原样保留。

考虑过的替代方案：在实施阶段直接 `git mv` 目录、delta 只写 MODIFIED。放弃的原因是 MODIFIED 会被归档
逻辑应用回 `openspec/specs/activitywatch-tools/`，与手工移动的目录冲突，且改名这件事不会留在变更记录里。

### 用 REMOVED + ADDED 表达场景重命名

三处场景名含品牌名——`agent-runtime` 的「Agent 查询 ActivityWatch」、`raw-event-retention` 的
「ActivityWatch 投影损坏」、`long-term-memory` 的「ActivityWatch 原始事件」。由于场景无法重命名，
这三条 requirement 各自整体走 REMOVED + ADDED，并顺带把 requirement 名改为不含品牌名的表述。
其余仅正文含品牌名的 requirement 一律走 MODIFIED，并完整保留原有场景名与场景数量。

考虑过的替代方案：保留场景名不动，只改正文。放弃的原因是场景名会出现在规格索引与评审视图中，留着
品牌名等于没有统一。

### 以「指代对象」而非「字符串出现」作为取舍标准

判断依据是这个词指的是谁，而不是它出现在哪里。指代自研投影层的一律改；指代用户自建实例、第三方 Web UI
或 `/api/0` 协议的一律留。由此产生三类明确的保留项，记录在 proposal.md - What Changes 中。

其中最容易被误改的是元规则条款：`agent-runtime`、`user-configuration`、`web-search` 中形如
「MUST NOT 使用 ActivityWatch 品牌名」的约束必须继续引用该词，否则约束本身无法表达。对应的守卫测试
（`AgentPromptsTest`、`TomlSupportTest`、`DesktopConfigControllerTest`）同样保留。

## Risks / Trade-offs

- SPEC-AW-* 的 ID 前缀留在名为 `event-query-tools` 的 capability 中，字面上不再对应 → 项目约定 SPEC ID
  是稳定追溯标识、不得改号（见 `openspec instructions` 的 context），因此这是有意接受的代价；
  design 与 proposal 均记录了 ID 与路径的对应关系。
- REMOVED + ADDED 在归档时先删后建，若中途失败可能留下缺失的 requirement → 改动仅涉及 Markdown，
  且 `openspec validate --strict` 在归档前已通过；异常时可用 `git checkout` 直接回滚。
- 主规格 Purpose 需手工编辑，容易遗漏 → 已在 tasks.md 中列为独立任务，并以「全库检索仅剩预期保留项」
  作为完成判据。

## Migration Plan

无数据迁移与用户可见变更。改动仅触及 Markdown，不涉及数据库、配置键与 HTTP 接口，用户无需任何操作。
回滚方式为撤销对应提交。
