## Context

见 `proposal.md`。历史 Windows 全量验证样本中，`MergedStorageCapacityTest` 用 1332 秒提交十万次心跳。`MergedEventStore` 对生产连接执行 `PRAGMA synchronous=FULL`，且每次 `heartbeat()` 在自动提交开启时单独 `BEGIN IMMEDIATE` / `COMMIT`。当前 Database 初始化会启用 WAL；Windows runner 上，十万次独立提交的同步落盘是主要开销。

该测试在回执清理之后比较 `events.db` 与旧双层样本的体积，基线结果是 `compactBytes=10149888`、`legacyDualLayerBytes=60534784`、`ratio=0.1677`，门槛为 0.20。十万次相同状态心跳最终只保留一个永久活动区间，这是 `SPEC-MES-001` 已有场景；本变更不放宽样本规模或体积门槛。

Rust 步骤没有构建缓存。桌面壳 `cargo test` 的编译约 3 分 25 秒，随后 `cargo clippy` 再编译约 1 分钟；测试本身只有数秒。

## Goals / Non-Goals

**Goals:**

- 容量夹具在 Windows CI 上从约 22 分钟降到数分钟以内，同时仍提交十万次心跳、在清理前提交全部回执，并保留 20% 体积门槛。
- 生产路径继续每次心跳独立提交，并保持 `synchronous=FULL`。
- Windows 全量验证复用 Cargo 依赖编译结果。

**Non-Goals:**

- 不减少样本数量，不把体积比较改成只测逻辑行数。
- 不把心跳批量提交或降低同步级别变成生产 API 行为。
- 不改数据守卫矩阵、发布工作流、必需检查名称，也不缓存 Maven 以外的新运行时。
- 不并行拆分 Windows 全量验证作业。

## Decisions

### 1. 只放松容量夹具的耐久性，并按 1000 条心跳提交一次

在测试同包中增加仅供容量夹具调用的入口：把该连接设为 `synchronous=OFF`，保留现有 WAL 模式，并允许夹具把一批 `heartbeat()` 放进同一个外层事务。`heartbeat()` 发现连接已处于事务中时沿用现有逻辑，不再自行提交。每 1000 次心跳提交一次，十万次回执全部提交之后才推进时钟并清理。随后的两日各 1000 次心跳使用同一夹具入口。

实施时验证发现：已有 WAL 连接打开时，切换 MEMORY 会触发 SQLITE_BUSY。因此保留 WAL，仅关闭夹具连接的同步并按 1000 条批量提交。十万条提交后，通过另一连接确认全部回执可见，并执行且验证 TRUNCATE checkpoint，再推进时钟清理；最终测量前再次 checkpoint，关闭连接后确认 WAL 不含剩余数据，确保主库体积和清理前高水位真实。

生产构造函数不接受耐久性参数，避免调用方误关 fsync。

备选：

- 把样本降到一万：会偏离已有十万次场景。
- 只加 Rust 缓存：容量测试仍可能占数分钟至二十多分钟。
- 保留 WAL 但不验证 checkpoint：体积会留在 `-wal`，测试只量 `events.db`，比较失真。

### 2. 用 Swatinem/rust-cache 缓存两个 Rust 工作区

在安装 stable 工具链之后、`cargo fmt` 之前，为 `self-analyst-desktop/src-tauri` 和 `self-analyst-axsidecar` 分别缓存 `target`。动作版本使用 `Swatinem/rust-cache@v2`，与仓库现有以主要版本引用 Action 的方式一致。缓存未命中时行为与现在相同。

备选：自写 `actions/cache`。需要自行维护锁文件、目标三元组和清理策略，收益相同，维护更多。

## Risks / Trade-offs

- [夹具批量提交改变 SQLite 空闲页分布，体积比例越过 0.20] → 保持清理前的十万条已提交回执；本地跑该测试并核对打印的 `ratio`。若越过门槛，把批量改为更小而不是放宽 0.20。
- [`synchronous=OFF` 在进程崩溃时损坏夹具库] → 只用于临时目录中的容量夹具；测量发生在正常关闭之后。生产连接保持 FULL。
- [Rust 缓存命中旧产物] → 使用按工作区和锁文件区分的缓存；源码变化仍会重编本 crate。
- [缓存恢复本身花时间] → 只加在 Windows 全量验证。依赖未变时，省下的全量编译应大于恢复成本。

## Migration Plan

无数据迁移。合并后下一次 PR 即使用新夹具；Rust 缓存第一次运行为填充，随后的 PR 命中缓存。回滚时恢复逐次心跳提交和 CI 中的缓存步骤即可，不影响已有用户数据。

## Open Questions

无。
