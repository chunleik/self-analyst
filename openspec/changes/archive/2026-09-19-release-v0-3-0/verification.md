# 验证记录

- 发布基线：main@4529bd1（包含 PR #53 adopt-merged-event-storage 与 PR #54 fix-app-jar-resolution），远端最新标签为 v0.2.8，无 v0.3.0。
- 版本更新：根 pom、5 个模块 pom、desktop package.json、tauri.conf.json、两个 Cargo.toml 及对应 Cargo.lock 中项目版本均为 0.3.0；git diff 核对第三方依赖版本未变（desktop Cargo.lock 中 dtor、urlpattern 的 0.3.0 为既有第三方版本）。
- `mvn --batch-mode validate` 通过。
- `openspec validate release-v0-3-0 --strict` 通过；`git diff --check` 通过。
- 主规格同步：本 change 设置 skip_specs: true，仅更新发布元数据和文档，无行为契约变化，无需同步主规格。
- design.md 未创建：发布元数据变更无技术决策、跨模块边界或迁移风险需要解释，符合 config 规则的条件产物判断（与 v0.2.8 发布 change 一致）。
- docs/ 保留文档：docs/releases/ 历史发布说明与 docs/benchmarks/ 验证记录仅作历史资料，不在本 change 中修改。
- 外部交付（任务 2.1–2.4：推送发布分支、PR 必需检查、合并、打标签并验证 Release 产物）在归档后继续执行并逐项记录。
