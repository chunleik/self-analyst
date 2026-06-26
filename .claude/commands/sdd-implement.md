---
description: 按任务清单实现并维护追溯矩阵（spec-kit 的 /implement，遵守本项目 commit 约定）
argument-hint: <slug，可选附带任务号如 "llm-budget Task 2">
allowed-tools: Read, Write, Edit, Grep, Glob, Bash
---

你要按 plan 的 `### Task N` / `Step` 清单落地实现，并维护 spec→源码的可追溯性。

目标：$ARGUMENTS

## 步骤

1. **载入上下文**：读 `docs/superpowers/plans/*-<slug>.md`（含 Task/Step）与 `docs/specs/<slug>.md`。
   若参数指定了具体任务号（如 `Task 2`），只做该任务；否则按顺序推进未勾选的 Step。

2. **逐 Step 实现**：
   - 写代码要贴合周边代码风格（命名、注释密度、惯用法）。
   - 实现完成后在 plan 里把对应 `- [ ] Step` 打勾 `- [x]`。
   - 若实现中发现 spec 契约需要调整，**先停下回到 `/sdd-spec` 修订**，不要让代码偏离 spec。

3. **维护追溯矩阵**：实现触及的源文件若是新增承载某 `SPEC-<域>-<子域>-NNN`，更新 spec 末尾
   **三列**追溯矩阵 `| 规格 ID | 目标文件/组件 | 验证方式 |`，保持 ID ↔ 文件 ↔ 验证方式一致。

4. **验证**：运行该任务对应的测试（参考 spec §5 / plan 的验证步骤）。Windows 上用 PowerShell 或
   Bash 工具按项目构建方式跑（Maven）。如实报告通过/失败，失败就贴输出并修复，不要谎报完成。

5. **提交（仅当用户要求提交时）**：commit message 引用涉及的 SPEC ID，聚焦「为什么」，
   中文或英文均可，并带 `Co-Authored-By` trailer。当前在非 main 分支再提交。

6. **收尾**：汇报完成了哪些 Task/Step 与 SPEC ID、测试结果、追溯矩阵是否已更新、剩余未完成 Step。

诚实优先：测试失败就说失败并贴输出；跳过的步骤要说明。
