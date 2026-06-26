---
description: 为某份 spec 编写实现计划（spec-kit 的 /plan，产物落在 docs/superpowers/plans/）
argument-hint: <spec 文件名或 slug，如 llm-budget>
allowed-tools: Read, Write, Edit, Grep, Glob
---

你要为一份已存在的 spec 编写 **plan（实现/构建计划）**。plan 承载 spec **不该**包含的
实现细节，边界见 `docs/specs/README.md` §6。格式以现有 plan 为范本：
`docs/superpowers/plans/2026-06-08-behavior-advice.md`。

目标 spec：$ARGUMENTS

## 步骤

1. **定位 spec**：在 `docs/specs/` 找到对应文件并通读，记下它覆盖的全部 SPEC ID
   （形如 `SPEC-<域>-<子域>-NNN`）。找不到则提示用户先运行 `/sdd-spec`。

2. **确定 plan 落点**：`docs/superpowers/plans/<当天日期>-<slug>.md`，日期格式 `YYYY-MM-DD`，
   slug 与 spec 对齐。先 Glob 看是否已有同特性 plan；有则增量更新而非新建。

3. **写 plan 头部**（对齐现有范本）：
   ```
   # <特性名> — Implementation Plan
   **Goal:** 一句话目标
   **Architecture:** 涉及的服务/类/数据流概述
   **Tech Stack:** Java 17+ / Javalin / vanilla ES5 JS / CSS 等
   **Spec:** `docs/specs/<slug>.md` — all SPEC-<域>-* requirements
   ```

4. **写 File Map 表**：`| File | Action(Create/Modify) | Purpose |`，逐文件列出将改/新建的源文件
   （给 `self-analyst-*/...` 完整路径，含测试文件与 `scripts/` 校验脚本）。

5. **划分 Task**：用 `### Task N: <标题>` 分块，每块列出 **Files** 和实现要点，
   并在块内/末尾标注它实现的 SPEC ID（如 `// SPEC-ADV-GEN-005`、或末尾汇总 `SPEC-ADV-API-001, ...`）。
   只写「怎么实现」：私有逻辑、算法步骤、依赖版本号、`Config` 方法名、pom 接线、模块注册顺序、
   端到端/手动验证步骤、设计决策论证。
   **每条 spec 需求都要被某个 Task 覆盖**；若发现 spec 有缺口或需修订，回到 `/sdd-spec` 改契约，
   不要在 plan 里偷偷扩大契约。

6. **Step 留给细化**：Task 内的逐步 `- [ ]` checkbox 可粗，交由 `/sdd-tasks` 细化为可执行步骤。

7. **收尾**：列出 plan 覆盖的 SPEC ID、是否发现 spec 缺口，提示下一步 `/sdd-tasks <slug>`。

不要在此阶段写实现代码；plan 是给 `/sdd-implement` 的施工图。
