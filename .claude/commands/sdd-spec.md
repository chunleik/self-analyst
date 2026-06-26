---
description: 新建或精修一份 SelfAnalyst spec（spec-kit 的 /specify，对齐本项目 SDD 约定）
argument-hint: <模块或特性名，或对已有 spec 的修改诉求>
allowed-tools: Read, Write, Edit, Grep, Glob
---

你要为 SelfAnalyst 起草或精修一份**正式 spec**。本项目用规格驱动开发，约定见
`docs/specs/README.md`，索引见根 `CLAUDE.md` 的「SDD Document Index」。**严格遵守，
不要照搬外部 spec-kit 的目录或 ID 习惯。**

诉求：$ARGUMENTS

## 步骤

1. **先读约定**：读 `docs/specs/README.md`（§3 编号、§4 骨架、§5 追溯矩阵、§6 spec/plan 边界）。
   若是新 feature spec，以 `docs/specs/desktop-chat-tab.md` 为格式模板；若是 module spec，
   以 `docs/specs/audio.md` / `docs/specs/file.md` 为范式。

2. **判定类型与落点**：
   - Module Spec → `docs/specs/<module>.md`（对应某个 `self-analyst-*` 子模块）
   - Feature Spec → `docs/specs/<feature>.md`（跨模块特性）
   先用 Glob 看 `docs/specs/` 现有文件，避免与已存在的 spec 重复或撞名。

3. **分配 Key Prefix**：形如 `SPEC-<域>-*`（如 `SPEC-CFG-*`）。检查 `CLAUDE.md` 索引表，
   确保 prefix 全局唯一、不与既有冲突。

4. **写 spec 正文**，套用 README §4 骨架：模块标识 → 架构契约 → 组件规格 → 配置 → 测试规格。
   - 每条需求编号 `SPEC-<域>-[<子域>-]NNN`（如 `SPEC-CFG-010`、`SPEC-BUDGET-API-001`、
     `SPEC-ADV-GEN-005`）；细分子项用小写后缀 `NNNa`/`NNNb`。子域按功能聚合（CFG/API/ACC/GOAL/NON…）。
   - 只写「要满足什么」（行为、接口签名、数据契约、约束、配置语义、设计决策及取舍），
     **不写「怎么实现」**（私有逻辑、版本号、方法名清单、pom 接线）——那些留给 `/sdd-plan`。
   - 末尾写**追溯矩阵**（三列）：`| 规格 ID | 目标文件/组件 | 验证方式 |`，
     ID 用 `SPEC-X-*` 或 `SPEC-X-001..003` 表示一段；验证方式如「单元测试」「代码审查」「验收测试」。
     参见 `docs/specs/llm-budget.md` 末尾矩阵为范例。

5. **登记索引**：在根 `CLAUDE.md` 的 SDD 索引表新增一行（文档链接、用途、Key Prefix）。

6. **收尾**：简述新增/改动了哪些 SPEC ID，并提示用户下一步可运行 `/sdd-plan <slug>`。

约束：ID 只增不改、不复用；已发布 ID 含义固定，废弃就标注「已废弃」而非删号重用。
不要在此阶段写实现代码。
