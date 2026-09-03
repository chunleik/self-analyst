## Context

参见 [proposal.md](proposal.md) 的变更动机。当前嵌入式 ActivityWatch 把 bucket 元数据和事件保存在单个 `aw.db`，heartbeat 在 `EventStore` 中通过更新最后一条等价事件的 `duration` 形成紧凑时间线。该表同时被 events API、AQL、Wiki、桌面时间线和 Agent 使用，因此现有行既不是逐次采集事实，也不能在不影响消费者的情况下独立演进。

窗口、AFK、内容标题和文件 watcher 都通过本机 HTTP 写入。内容事件已有严格字段白名单，文件链路已有 metadata-only 边界；新原始层必须位于这些隐私校验之后，不能把“永久保存”扩展成保存 UIA 正文、文件正文或请求字节。项目还支持外部 ActivityWatch 模式，但该模式的数据服务和目录不由本进程控制，无法提供同等永久性保证。

桌面壳已经为每次受管启动生成 32 字节随机 token，通过环境变量传给 Java，并为同源 `/desktop/*` fetch 注入 header。浏览器入口通过 `/desktop/session` 换取 HttpOnly cookie。现有通用桌面守卫在未配置 token 时允许开发模式访问；原始事件接口的数据敏感度要求它单独 fail-closed。

项目仍处于开发阶段。本变更从新原始事件层启用后的第一条事件建立事实源，不导入现有开发期 `aw.db`，也不设计旧 schema 兼容或历史精度标记。

## Goals / Non-Goals

**Goals:**

- 在不扩大现有隐私边界的前提下永久保存每次成功接收的合规事件。
- 将原始事实提交与 ActivityWatch heartbeat 合并、Wiki 和语义索引解耦。
- 通过月度分区、完整性 manifest 和稳定游标支持长期增长与有界查询。
- 原始提交成功后即使投影失败也不丢失事实，并能在重启后恢复投影。
- 使 `aw.db`、Wiki 和 Lucene 成为可验证、可替换的派生存储。
- 复用现有桌面 token 链路，同时保证原始事件 API 在 token 缺失时不可用。

**Non-Goals:**

- 不保存完整 HTTP 请求、header、UIA 树、UIA 正文、普通文件正文、截图、OCR 或音频。
- 不为原始事件增加 AQL 或 Agent 工具。
- 不支持外部 ActivityWatch 模式的永久原始存储。
- 不增加原始事件 TTL、自动清理或普通删除 API。
- 首版不实现压缩冷归档、云同步、静态数据库加密或受审计的不可逆销毁。
- 不迁移或重解释启用前的开发数据。

## Decisions

### 1. 以校验后事件语义作为原始边界

写入控制器先完成 JSON 解析、bucket/source schema、字段长度、请求大小和隐私策略校验，再构造规范化事件。原始层保存规范化 `data`、事件时间、接收时间、持续时间和身份元数据，不保存请求头或原始 HTTP 字节。

这一定义兼顾可重放性和隐私：投影算法需要的是事件语义，不需要 JSON 空白、字段顺序或网络元数据；禁止字段必须在任何永久介质写入前被拒绝。规范化 JSON 使用稳定字段顺序和明确数字表示，`dataSha256` 对规范化 UTF-8 字节计算，只用于完整性和诊断，不用于删除重复提交。

替代方案是保存完整 HTTP 投递。该方案能提供网络级审计，但会永久保存认证信息、无效字段和不必要字节，显著扩大敏感面，因此不采用。

### 2. 原始事件使用双重身份

每条记录具有服务端生成的时间有序 `eventId`，以及可选的 `sourceEventId`：

- 自有 watcher 在形成一个逻辑 heartbeat 时生成 `sourceEventId`，同一事件的网络重试复用该值。
- events 批次中的自有事件携带稳定 ID。
- import 使用 `importSessionId + bucketId + importOrdinal` 形成稳定身份。
- 第三方客户端没有来源 ID 时，每次成功解析的提交都生成新 `eventId`；不能仅凭 payload hash 判断重试，因为两个完全相同的事件可能都合法。

来源身份建立唯一约束，但原始 data hash 不建立唯一约束。这样自有链路可幂等，第三方链路则遵循“宁可多保留，不可误删”。

### 3. 使用按接收时间划分的月度 SQLite 分区

首版在 `{aw.data-dir}/raw/<yyyy>/raw-events-<yyyy-MM>.db` 保存原始记录，另用 `catalog.db` 维护分区索引。分区依据 `receivedAt` 的 UTC 月份，而不是事件时间：接收时间由服务端控制，批量请求不会因为迟到事件跨多个数据库事务，分区轮换也不受客户端错误时钟影响。

活动分区使用 WAL、`busy_timeout`、foreign keys 和 `synchronous=FULL`。每个分区包含 `raw_events` 表和按 bucket/event time、received time 建立的普通索引。标题和 JSON 不建立全文索引。

月度 SQLite 与现有 JDBC 技术栈一致，能直接提供事务、索引和恢复。直接使用压缩对象格式容量更低，但会同时引入归档 writer、索引、解压和崩溃恢复协议，首版不采用。

### 4. catalog 是可恢复索引，manifest 是封存证据

`catalog.db` 保存分区路径、UTC 范围、状态、事件数量、首尾 ID、大小、schema 版本、文件 SHA-256 和验证时间。状态为 `ACTIVE`、`SEALING`、`SEALED` 或 `QUARANTINED`。

月份切换时先创建并启用新分区，使新写入不依赖旧分区封存完成；随后对旧分区执行 checkpoint、关闭写连接、完整性检查、计数与边界核对、文件 SHA-256，原子写 manifest，最后标记 `SEALED` 并仅以只读连接访问。

catalog 损坏时可以扫描受严格路径约束的 manifest 重建。manifest 与分区不一致时不自动修复或删除，而是隔离并在状态接口报告。

### 5. 原始先提交，投影采用至少一次调度和幂等结果

写入顺序为：

```text
解析与隐私校验
  → 原始分区事务提交
  → 尝试同步投影
  → 更新投影 checkpoint
  → 返回响应
```

原始事务失败时不调用投影。原始事务成功而同步投影失败时，接口返回 202，并包含 `rawEventId` 与 `projectionStatus=pending`；后台投影器按 checkpoint 重试。正常投影成功时保持当前兼容 endpoint 的 2xx 状态和主要响应字段，并可附加原始事件身份。

投影器以原始 `eventId` 为幂等键维护投影来源映射。单条原始事件只能成功推进一次投影；heartbeat 合并可以更新投影行，但来源映射同步扩展 `firstRawEventId`、`lastRawEventId` 和 `rawEventCount`。checkpoint 只在投影事务提交后推进。

由于原始分区和 `aw.db` 是不同数据库，无法依赖单个 SQLite 事务实现跨库原子性。“原始先提交、投影可重试”比双写后回滚更符合原始事实不可丢失目标。

### 6. `aw.db` 继续承担兼容投影

现有 buckets/events schema、events API、AQL 和 ActivityWatchTools 保持面向紧凑时间线。`EventStore` 收敛为投影存储；heartbeat 合并规则移到投影器，并使用规范化 data 结构比较而不是偶然的 JSON 序列化顺序。

投影来源表记录每个投影事件覆盖的首尾原始 ID、原始事件数和 projector 版本。bucket 删除只删除或隐藏投影 bucket，不向原始层传播删除。该行为是本变更的显式破坏性语义调整：用户删除可重建视图，不再删除永久事实。

### 7. 重建使用旁路数据库和原子切换

ActivityWatch 重建创建唯一临时目标数据库，按 catalog 分区顺序和 `receivedAt,eventId` 顺序重放。完成后验证：

- 所有健康分区的预期覆盖已推进；
- 来源映射不重复、不跨越未处理事件；
- bucket 与事件索引可用；
- 投影数据库完整性检查通过。

验证成功后关闭活动读写连接并原子替换当前投影。验证前失败不影响当前 `aw.db` 或原始分区。旧投影属于派生副本，只能在新投影成功切换后由显式维护流程清理。

Wiki 不直接访问原始桌面 API。Wiki 继续从 ActivityWatch 投影构造事实，并记录 fact-builder 版本、projector 版本和各来源 bucket 覆盖状态；存在投影延迟时标记摘要覆盖不完整。Lucene 仍只索引 Wiki 摘要和任务片段。

### 8. 原始查询使用独立桌面 API

新增：

```text
GET /desktop/raw-events
    ?bucketId=<required>
    &start=<required ISO instant>
    &end=<required ISO instant>
    &limit=<optional>
    &cursor=<optional>
```

查询服务先通过 catalog 找出相交分区，再依次打开只读连接。排序键固定为 `receivedAt,eventId`；cursor 绑定规范化 bucket、时间范围和排序锚点，不能跨查询条件复用。响应包含 `events`、`nextCursor` 和分区/投影覆盖摘要。API 不提供无 bucket、无时间范围或“全部历史”模式。

内容事件仍只返回 v2 允许字段。文件历史可以返回永久保存的合规路径元数据，但 FileTools 的当前监控根限制保持不变。原始查询不注册为 AQL 函数或 Agent 工具。

### 9. 原始 API 在 token 缺失时 fail-closed

路由仍位于 `/desktop/*`，复用 Host、Origin、header token 和 cookie 校验。额外增加敏感路径判定：如果 `SELF_ANALYST_DESKTOP_TOKEN` 为空，`/desktop/raw-events` 和原始状态/导出类接口返回能力不可用，不能沿用通用桌面开发模式的无 token 放行。

Tauri WebView 继续只为同源 `/desktop/*` fetch 注入 `X-SelfAnalyst-Token`。系统浏览器继续通过 `/desktop/session?token=...` 完成一次交换，但 cookie 改为 `Path=/desktop; HttpOnly; SameSite=Strict`。除 session 交换外，业务接口不从 query 读取 token。凭据比较继续使用常量时间比较。

### 10. 磁盘压力通过背压处理

原始事件没有保留天数、最大分区数或自动清理配置。后台定期读取原始目录所在文件系统的可用空间：

- 低于 warning 阈值：状态显示告警，采集继续。
- 低于 block 阈值：停止投影重建、Wiki 回填和其它非必要派生工作；新原始提交返回空间不足并使 collector 进入 blocked。
- 原始写入实际返回空间不足：即使采样值尚未越过阈值，也立即进入 blocked。

系统不通过覆盖活动分区、删除 sealed 分区或截短 WAL 恢复空间。WAL checkpoint 只回收已提交日志结构，不删除原始事件语义。

### 11. 配置与路径锁定

首版支持以下用户配置：

- `aw.raw.dir`，默认 `{aw.data-dir}/raw`；
- `aw.raw.query.maxRangeDays=31`；
- `aw.raw.query.maxPageSize=1000`；
- `aw.raw.lowDisk.warnBytes=10737418240`；
- `aw.raw.lowDisk.blockBytes=1073741824`；
- `aw.raw.integrity.verifyOnStartup=latest`，可选 `latest/all`；
- `aw.raw.projector.batchSize=1000`。

嵌入式模式不提供关闭永久原始层的用户键。分区固定按月，不提供只有一个有效值的 `partition` 配置。已经存在分区后，普通配置保存拒绝改变有效 `aw.raw.dir`，避免新旧目录被静默割裂；未来如需搬迁，由单独的显式转存设计处理。

### 12. 启动与关闭顺序

嵌入式启动顺序调整为：

```text
加载并校验配置
  → 初始化 raw catalog 与活动分区
  → 验证配置要求的 sealed/active 分区
  → 初始化 ActivityWatch 投影
  → 恢复待处理投影直至达到可用水位
  → 启动 HTTP
  → 启动 watcher
  → 启动 Wiki/embedding/文件 worker
  → 启动桌面服务
```

原始层无法初始化时，不启动会产生采集事件的 watcher，桌面仍可启动并报告 blocked。投影恢复失败时可启动原始接收和桌面诊断，但 Wiki 与依赖完整投影的摘要能力保持 degraded。

关闭时先停止 watcher 接收新事件，再等待原始事务与投影批次完成，checkpoint 活动分区，最后关闭 catalog 和数据库连接。正常关闭不封存当前月份分区。

### 13. 外部 ActivityWatch 模式明确不可用

外部模式不初始化本地原始分区，也不代理外部 events 到原始层。桌面状态返回 `unavailable` 和原因 `external_aw`，原始事件 API 返回能力不可用。这样避免把不受应用控制的远端或第三方存储错误描述为“永久保留”。

### 14. 不迁移开发期数据

实现不读取、复制、重解释或删除启用前的 `aw.db` 和旧 bucket 数据库。新原始目录从首次成功启动开始记录新事件；测试和开发环境如何重置旧投影由开发流程处理，不进入产品迁移代码。

现有内容事件净化属于当前独立行为，不被本变更扩展为新原始层的历史导入路径。只有在新写入入口通过当前内容策略后产生的事件才进入永久原始层。

### 15. 首版使用文件系统 ACL，不增加静态加密

原始目录沿用本地用户数据目录的访问边界，并在创建时限制为当前用户可访问。首版不引入 SQLCipher 或自定义 payload 加密，因为密钥恢复、索引能力和便携部署需要单独设计；永久数据一旦因密钥丢失不可恢复，风险高于本变更可安全覆盖的范围。

## Risks / Trade-offs

- **[永久保存导致磁盘持续增长]** → 使用月度分区、容量与增长指标、warning/block 阈值和明确扩容提示；不承诺固定空间，也不自动删除。
- **[逐 heartbeat 行数很大]** → 普通查询只访问合并投影，原始查询强制 bucket、时间范围、页大小和稳定 cursor；首版压测一个月等价数据量。
- **[跨库双写不能原子提交]** → 原始先提交，投影以原始事件 ID 幂等重试，checkpoint 只在投影事务后推进。
- **[第三方客户端没有稳定来源 ID]** → 不用 payload hash 删除，接受可能重复原始记录；2xx pending 响应减少不必要重试。
- **[bucket 删除语义与用户直觉不同]** → UI 和 API 明确说明删除的是可重建投影，原始事件仍永久保留。
- **[标题和文件路径永久保存仍具敏感性]** → 校验先于落盘、原始 API 强认证、无 Agent/AQL 原始入口、日志不含 payload，并维持现有采集白名单。
- **[catalog 与 manifest 状态不一致]** → manifest 作为 sealed 证据，catalog 可重建；任何不一致 fail-closed 并隔离，不自动删除或猜测修复。
- **[投影延迟导致 Wiki 摘要不完整]** → 暴露投影 lag 和覆盖状态，Wiki 标记不完整且不声称全量覆盖。
- **[无 token Java 开发模式不能查看原始事件]** → 这是敏感接口的预期 fail-closed 行为；需要查看时应从受管 Tauri 启动或显式配置安全启动 token。

## Migration Plan

本变更没有历史数据迁移，采用开发阶段直接切换：

1. 增加原始事件模型、月度分区、catalog、配置和单元测试，但暂不改变现有查询消费者。
2. 修改自有 watcher 和批量导入，使其携带稳定来源身份。
3. 将 heartbeat/events/import 写入口切换为“校验 → 原始提交 → 幂等投影”，增加 pending 恢复测试。
4. 将 heartbeat 合并收敛到投影器，增加来源映射、checkpoint 和旁路重建。
5. 增加原始桌面查询、强制 token、cookie Path 收紧和安全测试。
6. 增加月度轮换、封存 manifest、catalog 恢复、磁盘背压与状态展示。
7. 更新 Wiki 版本/覆盖元数据并验证其只消费 ActivityWatch 投影。
8. 运行模块测试、全量 Maven 测试、Rust 桌面壳测试和一个月等价数据量压力测试。
9. 更新主规格、架构、隐私和用户文档后再将 change 归档。

回滚只允许回滚派生投影与消费者切换，不得删除已经提交的新原始分区。若新链路存在严重问题，停止 watcher 和新写入、保留原始文件用于修复，然后从最近健康投影恢复只读能力；不得回退到绕过原始层继续采集。
