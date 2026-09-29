## Why

PR 必需检查「Windows 全量验证」历史样本耗时约 37 分钟。该次成功运行里，`mvn test` 占 28 分钟，其中 `MergedStorageCapacityTest` 单测 1332 秒；Rust 测试与 Clippy 另占约 5 分钟，且每次从零编译依赖。检查按分支取消进行中的运行，新提交会把已消耗的时间作废重来。

## What Changes

- 容量夹具仍提交十万次相同状态心跳，并保持「一个永久活动区间、回执过期后回收、紧凑库不超过旧双层样本 20%」的验收。夹具不再为每次心跳执行 `synchronous=FULL` 的独立 fsync；生产写入的提交粒度和耐久性不变。
- Windows 全量验证缓存 Cargo 依赖与构建产物，避免每次 PR 重新编译桌面壳和 sidecar 的第三方 crate。
- 这是测试夹具与 CI 工具调整，不改变合并事件存储、采集或发布包的行为，也不是文档基线迁移。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。生产行为契约不变，本 change 在 `.openspec.yaml` 中设置 `skip_specs: true`。

## Impact

- `self-analyst-events`：容量测试及仅供该夹具使用的耐久性开关；`MergedEventStore` 的生产默认仍为每次心跳独立事务且 `synchronous=FULL`。
- `.github/workflows/ci.yml`：仅为 `Windows 全量验证` 增加 Rust 构建缓存。数据守卫矩阵、发布工作流和必需检查名称不变。
- 不修改主规格、用户文档或运行时依赖。

实施前补充核对：2026-09-29 成功运行 36504823298 共 20 分钟，Java/Node 步骤 710 秒，容量测试 446.9 秒，Rust 步骤 352 秒；耗时随 runner 波动，但瓶颈方向一致。
