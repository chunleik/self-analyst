---
description: 把 plan 的 Task 细化为可执行步骤清单（spec-kit 的 /tasks，写回同一 plan 文件）
argument-hint: <spec/plan 的 slug，如 llm-budget>
allowed-tools: Read, Write, Edit, Grep, Glob
---

你要把 plan 里的每个 `### Task N` 细化成**有序、可独立验证的 `- [ ]` Step 清单**，
直接写回该 plan 文件（与现有 `docs/superpowers/plans/2026-06-08-behavior-advice.md` 一致：
每个 Task 由若干 `- [ ] **Step k: ...**` 组成）。

目标 plan：$ARGUMENTS

## 步骤

1. **读 plan 和对应 spec**：在 `docs/superpowers/plans/` 找到 `*-<slug>.md` 与 `docs/specs/<slug>.md`，
   建立「Step ↔ SPEC ID」覆盖关系。

2. **逐 Task 细化 Step**，每个 Step：
   - 用 `- [ ] **Step k: <动作>**` 开头
   - 指明改哪个文件（`self-analyst-*/...` 具体路径）、做什么
   - 标注它实现/验证的 SPEC ID（行内注释或 Step 末尾）
   - 标注顺序依赖（哪些 Step 必须先于本步）
   - 末尾给该 Task 的**验证方式**：对应 spec §5 测试规格的某条，或具体手动验证步骤

3. **补验证 Task**：若 spec §5 有测试规格而 plan 未覆盖，新增一个测试 Task
   （如 `### Task N: 测试 — 覆盖 SPEC-X-* 测试规格表`）。

4. **自检覆盖**：spec 里每个 SPEC ID 都应被至少一个 Step 覆盖（实现或验证）；
   列出未覆盖的 ID，补 Step 或回报缺口。

5. **收尾**：汇报 Task/Step 总数与 SPEC 覆盖情况，提示下一步 `/sdd-implement <slug>`
   （可附任务号只做单个 Task）。

不要在此阶段写实现代码。
