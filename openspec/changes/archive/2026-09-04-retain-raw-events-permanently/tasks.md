## 1. 配置与原始事件模型

- [x] 1.1 在配置模型、默认 properties、SupportedKeys 和 TOML 模板中加入 `aw.raw.dir`、查询上限、磁盘阈值、完整性策略和投影批量参数，并通过 `ConfigTest` 与用户配置解析测试验证默认值、类型和 `latest/all` 枚举。
- [x] 1.2 实现阻断阈值必须小于告警阈值、数值范围和嵌入式模式固定启用校验，并运行配置保存测试验证非法 TOML 不替换磁盘文件。
- [x] 1.3 实现已有原始分区后拒绝通过普通配置修改 `aw.raw.dir`，并以临时目录测试验证旧配置和分区均保持不变。
- [x] 1.4 定义原始事件、来源、接收类型、投影状态和分区状态模型，实现时间有序 `eventId` 与规范化 JSON/SHA-256，使用单元测试验证稳定序列化、数字处理和同值 hash。

## 2. 月度原始存储与 catalog

- [x] 2.1 在 `self-analyst-aw` 实现受严格规范化路径约束的 raw 目录和 catalog schema，运行 `RawPartitionCatalogTest` 验证首次创建、重复打开、路径越界和链接目录 fail-closed。
- [x] 2.2 实现按 `receivedAt` UTC 月份选择活动 SQLite 分区及 `raw_events` schema/index，运行 `RawEventStoreTest` 验证跨时区边界仍写入正确月份。
- [x] 2.3 实现单条只追加事务、服务端事件 ID、来源事件 ID 唯一约束和第三方无 ID 不去重，并测试自有重试幂等与两个无 ID 同值事件都被保存。
- [x] 2.4 实现批量 append 与 `importSessionId + bucketId + importOrdinal` 幂等约束，并测试整批事务失败不留下部分原始行、重复导入不新增副本。
- [x] 2.5 封装原始存储接口使业务路径不暴露 `UPDATE/DELETE`，增加结构与代码测试验证 heartbeat 合并、bucket 删除和维护操作不能修改 `raw_events`。

## 3. 分区轮换、完整性与容量保护

- [x] 3.1 实现跨月时先创建新活动分区、再封存旧分区的轮换锁与状态转换，并通过可控时钟并发测试验证月份切换期间事件无遗漏、无错分区。
- [x] 3.2 实现 checkpoint、连接关闭、SQLite 完整性检查、计数/边界核对、文件 SHA-256 和原子 manifest 写入，并测试健康分区最终只读且 manifest 可重复验证。
- [x] 3.3 实现封存中断恢复、失败分区隔离和从 manifest 重建 catalog，并注入各步骤故障验证不删除数据库、WAL、manifest 或可恢复临时文件。
- [x] 3.4 实现原始目录可用空间采样、warning/block 状态和空间不足错误分类，并测试 warning 继续采集、block 拒绝新原始事件且不会删除最旧分区。
- [x] 3.5 增加原始存储一个月等价高频 heartbeat 的容量与分页基准测试，记录事件吞吐、分区大小和查询延迟，验证测试过程无自动删除。

## 4. ActivityWatch 投影器与恢复

- [x] 4.1 为 `aw.db` 增加投影来源映射、projector 版本和 checkpoint schema，运行数据库测试验证 schema 幂等创建与 checkpoint 事务语义。
- [x] 4.2 实现按 `receivedAt,eventId` 顺序投影单条和有界批次，使用原始事件 ID 防止重复投影，并测试投影失败后重试只产生一个逻辑结果。
- [x] 4.3 把 heartbeat 合并从直接写入路径收敛到投影器，正确应用实际 `pulsetime` 并更新来源覆盖范围，运行 heartbeat 测试验证多个原始行对应一个投影段且原始行不变。
- [x] 4.4 实现启动时从 checkpoint 恢复待投影事件，注入“原始提交后、投影前崩溃”验证重启补齐、checkpoint 不越过失败事件。
- [x] 4.5 实现旁路 `aw.db.rebuilding-*` 重建、覆盖校验、连接关闭和原子切换，测试重建中断保留当前投影、成功重建得到等价查询结果且不修改原始分区。
- [x] 4.6 修改 bucket 删除语义使其只删除或隐藏 ActivityWatch 投影，并通过控制器测试验证原始事件数量和原始查询结果不变。

## 5. 写入入口与采集器幂等

- [x] 5.1 将 heartbeat 控制器改为“解析/策略校验 → 原始提交 → 投影”，并测试完全成功保持兼容响应、投影失败返回带 `rawEventId` 的 202 pending、原始失败不产生投影。
- [x] 5.2 将单条/批量 events 控制器接入原始优先事务，验证内容策略违规仍在首条原始写入前拒绝，批量存储错误不留下部分原始记录。
- [x] 5.3 将 DataImporter 接入稳定导入会话和 ordinal，在创建投影前完成策略预检与原始批量提交，并运行导入重试和失败原子性测试。
- [x] 5.4 扩展 window/AFK watcher 为每个逻辑 heartbeat 生成稳定来源 ID，并增加保留该 ID 的有界重试机制；通过模拟网络失败验证重试不会产生重复原始事件。
- [x] 5.5 扩展 ContentWatcher 为每个合法标题 heartbeat 生成稳定来源 ID并复用重试，运行秘密标记测试验证事件 ID 支持不会让 UIA 树或正文进入请求、日志和原始分区。
- [x] 5.6 扩展 FileWatcher 为每个已发送元数据 heartbeat 生成稳定来源 ID并复用重试，运行文件测试验证正文不读取、节流发生在事件形成前且已提交原始行不被删除。

## 6. 原始事件查询与桌面认证

- [x] 6.1 实现跨 catalog 分区的只读原始查询服务，要求 bucket/start/end，按 `receivedAt,eventId` 排序并生成绑定查询条件的稳定 cursor；测试跨月分页无遗漏、无重复且拒绝 cursor 条件错配。
- [x] 6.2 实现 `GET /desktop/raw-events` 的参数、页大小、时间范围、响应字段和覆盖信息，运行控制器测试验证缺失/非法/超限参数返回 400 且不会无界扫描。
- [x] 6.3 在统一请求守卫中为原始事件路径增加 token 缺失 fail-closed，运行 `AwServerSecurityTest` 验证无配置 token 返回能力不可用、缺失/错误凭据返回 401、有效 header/cookie 才能查询且认证失败不泄露 bucket 存在性。
- [x] 6.4 将 `/desktop/session` cookie 收紧为 `Path=/desktop; HttpOnly; SameSite=Strict`，并运行 Java 安全测试与 Tauri 测试验证 WebView 只向同源 `/desktop/*` 注入 header、普通业务 query token 不被接受。
- [x] 6.5 验证 ActivityWatch events、AQL、ActivityWatchTools 和 WikiTools 仍只返回合并投影，并增加回归测试证明没有注册原始事件 AQL 函数或 Agent 工具。

## 7. 状态、外部模式与 Wiki 派生

- [x] 7.1 扩展桌面状态模型与 API，返回 raw 状态、活动分区、数量、时间边界、总大小、增长速度、剩余空间、完整性结果和投影 lag，并测试响应不包含事件 data、标题、路径或认证值。
- [x] 7.2 更新桌面状态界面显示 running/degraded/blocked/unavailable、磁盘告警和投影延迟，运行 Node UI 测试并人工检查敏感 payload 未进入 DOM。
- [x] 7.3 在外部 ActivityWatch 模式禁用原始存储与查询并报告 `unavailable/external_aw`，运行 AppSession/状态集成测试验证不创建本地 raw 分区且不声称永久保留。
- [x] 7.4 调整 AppSession 启停顺序为 raw 初始化/验证、投影恢复、HTTP、watcher、Wiki/文件 worker，并用集成测试验证 raw 初始化失败不启动采集器、投影失败只降级依赖投影的消费者。
- [x] 7.5 扩展 Wiki 条目持久化以记录 fact-builder 版本、projector 版本和来源覆盖状态，运行 WikiStore round-trip 与 schema 测试验证字段有界且可迁移。
- [x] 7.6 更新 WikiFactBuilder/Worker 只消费 ActivityWatch 投影并传播投影延迟，运行 Wiki 测试验证覆盖不完整会被 Agent/桌面报告且永久原始事件不被读取或修改。

## 8. 隐私与不删除回归

- [x] 8.1 增加内容 heartbeat/events/import 的端到端秘密标记测试，验证禁止字段返回 422，且 raw SQLite、WAL、manifest、`aw.db`、日志和错误响应均不包含秘密值。
- [x] 8.2 增加文件正文秘密标记测试，验证 raw 分区、file-watch.db、ActivityWatch 投影、manifest 和日志仅出现允许元数据而不出现正文。
- [x] 8.3 增加不可变性集成测试，依次执行 heartbeat 合并、bucket 删除、文件监控根移除、Wiki 清理、投影重建和磁盘告警，验证原始事件计数、hash 和查询结果保持不变。
- [x] 8.4 增加日志与指标字段审计测试，验证事件 data、窗口/上下文标题、文件路径、token、完整请求和 prompt 不作为日志消息或 metric label 输出。
- [x] 8.5 验证首次启用不扫描、导入、修改或删除已有开发期 `aw.db` 和旧 bucket 数据库，并通过临时目录集成测试确认新 raw 层只包含启用后的事件。

## 9. 文档与完整验证

- [x] 9.1 更新 `docs/architecture.md`、`PRIVACY.md`、README 和配置说明，明确“合规原始事件永久保留、UIA/文件正文从不进入原始层、外部 AW 不提供保证、磁盘满时阻止采集”，并检查全部新增文档使用简体中文。
- [x] 9.2 运行 `mvn -pl self-analyst-aw -am test`，修复原始存储、投影、控制器和安全测试中的失败并保存通过结果。
- [x] 9.3 运行 `mvn -pl self-analyst-content -am test` 与 `mvn -pl self-analyst-file -am test`，验证 watcher 身份重试和隐私回归通过。
- [x] 9.4 运行 `mvn -pl self-analyst-wiki -am test` 与 `mvn -pl self-analyst-app -am test`，验证 Wiki 覆盖、启动降级、桌面 API 和 UI 测试通过。
- [x] 9.5 运行 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`，验证桌面 token、同源注入和浏览器会话行为。
- [x] 9.6 运行完整 `mvn test`、`cargo test --manifest-path self-analyst-axsidecar/Cargo.toml` 和 `openspec validate retain-raw-events-permanently --strict`，确认跨模块回归与 OpenSpec 严格校验全部通过。
