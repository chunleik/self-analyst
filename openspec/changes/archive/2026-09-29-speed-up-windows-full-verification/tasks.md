## 1. 容量夹具

- [x] 1.1 在 `MergedEventStore` 增加仅测试同包可用的容量夹具入口：将该连接设为 `synchronous=OFF` 并保留 WAL，并支持把一批 `heartbeat()` 放进同一个外层事务。生产构造路径保持 `synchronous=FULL` 和每次心跳独立提交。用代码审阅确认生产调用点没有使用该入口。
- [x] 1.2 修改 `MergedStorageCapacityTest`：十万次相同状态心跳按 1000 条一批提交，全部提交后从另一连接确认十万条回执并完成 checkpoint，再推进时钟并清理回执；测量前再次 checkpoint 并确认没有未计入的 WAL 数据；随后两日样本使用同一入口。断言仍为一个永久区间、回执清零、三日后三个区间，且紧凑库不超过旧双层样本的 20%。运行 `mvn -pl self-analyst-events -am '-Dtest=MergedStorageCapacityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认通过，并记录耗时与 `MERGED_CAPACITY` 的 `ratio`。

## 2. Windows 全量验证缓存

- [x] 2.1 在 `.github/workflows/ci.yml` 的 `Windows 全量验证` 中，于安装 Rust stable 之后加入 `Swatinem/rust-cache@v2`，分别缓存 `self-analyst-desktop/src-tauri` 与 `self-analyst-axsidecar` 的 `target`。确认数据守卫作业和 `windows-release.yml` 未被改动。

## 3. 校验

- [x] 3.1 运行 `openspec validate speed-up-windows-full-verification --strict`，确认 change 通过且未要求主规格增量。


## 验证记录

- 2026-09-29：使用 JDK 21 运行容量测试与 MergedEventStoreTest，共 9 项通过。容量测试 10.99 秒，总构建 16.737 秒（本机数据，非 CI 同机对照）。
- MERGED_CAPACITY：samples=100000，compactBytes=10149888，legacyDualLayerBytes=60534784，ratio=0.1677；保持原容量结果及 0.20 门槛。
- 搜索确认 capacityFixtureBatch 仅由容量测试调用，生产构造路径仍为 FULL；数据守卫和发布工作流未改动。
- openspec validate speed-up-windows-full-verification --strict 和 git diff --check 通过。
- Rust 缓存工作区配置已核对；远端缓存命中和完整 Windows CI 耗时待推送后验证。
- 无行为契约变更、无增量规格，无需同步主规格或修改用户文档。
