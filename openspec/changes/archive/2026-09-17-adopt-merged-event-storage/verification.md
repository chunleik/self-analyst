# 实施与验证记录

- 分支：`codex/adopt-merged-event-storage`；基线提交：`73d6e7f`。
- `mvn test -q` 完整回归通过，包含 151 项 Node 测试；随后针对最终补充的目录联接、备份清理及退役重建入口运行测试并通过。
- `openspec validate adopt-merged-event-storage --strict` 通过；28 项主规格严格校验通过。
- 容量结果与限定条件见 `docs/benchmarks/merged-event-storage.md`。固定样本新库 10,149,888 字节，旧双层基线 60,534,784 字节，比例 16.77%。
- 旧版 `RuntimeStorageGuard` 在基线源码中只接受格式 1；新格式提交测试覆盖格式 2 已发布、活动库尚未替换时的中断恢复。未启动用户已安装的旧程序。
- UI 通过实际组件及项目 CSS 的合成数据页面验证，截图位于 `docs/screenshots/merged-storage-preview.png`。用户实际运行数据未迁移、未清理。

## 规格与保留文档

八项能力已对齐。`raw-event-retention/spec.md` 的全部 13 个要求及 Purpose 已退役，替代能力为 `merged-event-storage`；旧编号不复用，历史 change 和 Git 保留完整追溯。

`docs/runtime-storage.md`、`docs/architecture.md`、`PRIVACY.md` 和双语 README 为当前说明；`docs/benchmarks/merged-event-storage.md` 为当前验证证据，旧 `docs/benchmarks/raw-event-retention.md` 标为历史报告，`docs/archive/` 保留历史背景，不作为当前契约。

如需查看退役规格，可在本检出执行 `git show 73d6e7f:openspec/specs/raw-event-retention/spec.md`；如需恢复该文档文件，可使用 `git checkout 73d6e7f -- openspec/specs/raw-event-retention/spec.md`，这只恢复文档，不恢复应用数据或旧行为。
