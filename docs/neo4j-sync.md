# 可选 Neo4j 手工同步

Neo4j 是当前知识图谱的一份可替换快照投影。事件、Wiki、`ontology.db`、`memory.json` 和 Lucene
继续保留现有本地存储、查询与权威边界；同步不迁移原始事件、Wiki 数据库或向量索引，也不能代替本地备份。
没有 Neo4j 时，“知识”、采集、聊天和摘要继续工作。

此功能默认关闭。保存配置、启动应用、刷新状态、重建本体及聊天都不会触发同步；只有在“知识”页面
逐次确认目标和发送范围后才连接 Neo4j。不提供后台同步计划或 Agent 同步工具，也不新增模型或 embedding 调用。

## 1. 准备目标与密码

自行准备可用的 Neo4j 数据库，并选择可写入图数据、创建约束及执行 `SHOW CONSTRAINTS` 的账号。应用使用官方 Neo4j Java Driver
`5.28.15` 的官方 `neo4j-java-driver-all` 隔离打包版本，不改变应用其他组件的 Netty/Reactor 依赖，不需要 APOC。它会按需创建实体 `(namespace, id)` 联合唯一约束和同步元数据的 `namespace`
唯一约束，并读取实际定义进行核对；权限不足、约束名称冲突或既有数据不满足约束时，同步报错。
远端已有未知的 `schemaVersion` 时拒绝写入，不自动覆盖或迁移。应用不会安装 Neo4j、创建账号或授予权限。

为**当前本地数据集**选择一个独占的 `namespace`，例如仅用于合成试验的 `demo-local-001`。
不要让其他设备或独立数据集使用同一数据库里的同一命名空间：每次同步会替换该命名空间的应用投影，
后一次快照会撤销前一次不再存在的实体与关系。命名空间用于隔离同步范围，不是 Neo4j 的访问控制机制。

在启动 SelfAnalyst 前，把账号密码设置到应用进程能够继承的环境变量中，默认变量名为
`SELF_ANALYST_NEO4J_PASSWORD`。只在环境变量中保存密码值，TOML 只填变量名；不要把密码写入 URI、
配置文件、聊天、脚本示例或截图。此指南不提供任何已部署服务或真实凭据。

更改操作系统或启动终端的环境变量后，完全退出应用（包括托盘后台进程），再从能继承新环境的入口启动。
已经运行的进程不会自动获得新的环境变量。

## 2. 配置并保存

打开“设置 → 高级配置”，在现有 TOML 中添加或更新一份 `[neo4j]`，不要重复同名表。以下是默认配置：

```toml
[neo4j]
enabled = false
uri = ""
database = "neo4j"
username = "neo4j"
password-env = "SELF_ANALYST_NEO4J_PASSWORD"
namespace = ""
timeout-seconds = 15
```

准备好服务后填写实际地址、数据库、用户名和独占命名空间，最后把 `enabled` 改为 `true` 并保存。
例如本机直连地址可以是 `bolt://127.0.0.1:7687`；地址示例不表示本机已经运行了服务。

| 配置项 | 约束与含义 |
|---|---|
| `enabled` | 默认 `false`；启用只开放手工同步，不自动发送 |
| `uri` | 必填；接受下面列出的直连/TLS 地址，不含密码 |
| `database` | 默认 `neo4j`；1–63 个英文字母、数字、点、下划线或连字符，首字符为字母或数字；不能使用 `system` |
| `username` | 默认 `neo4j`；填写实际目标账号，不能为空 |
| `password-env` | 默认 `SELF_ANALYST_NEO4J_PASSWORD`；环境变量名称最多 128 字符，只含大写英文字母、数字和下划线，不能以数字开头；不是密码值 |
| `namespace` | 必填，无默认共享值；1–128 个英文字母、数字、点、下划线或连字符，首字符为字母或数字 |
| `timeout-seconds` | 默认 `15`，范围 `1`–`120`；快照准备完成后的网络操作共用这一总超时预算，连接、连接获取与事务另设相同超时；退出时驱动清理最多再等待 5 秒 |

地址限制：

- 明文只允许直连 `bolt://localhost`、`bolt://127.0.0.1` 或 `bolt://[::1]`，可带有效端口。
- 远端必须使用 `bolt+s://` 或 `neo4j+s://`，并通过有效、受信任 CA 证书和主机名校验。
- 明文 `neo4j://` 即使指向本机也不接受，避免路由到其他明文目标；不接受跳过证书信任的 `+ssc`。
- URI 不能包含用户名/密码、路径（包括末尾 `/`）、查询参数或片段；数据库名写在 `database`，不要放进 URI。

驱动连接方案的背景见 [Neo4j 官方连接文档](https://neo4j.com/docs/java-manual/current/connect-advanced/)；
SelfAnalyst 使用上述更严格的允许列表。

通过应用保存后，`neo4j.*` 的新值会用于**下次手工同步**，无需重启。保存本身不连接目标，不验证远端密码或权限。
直接从外部编辑 TOML 时，仍需回到应用保存或重启后端才会应用；密码进程环境的变更另需重启。

## 3. 核对范围并手工同步

1. 打开“知识 → Neo4j 手工同步”。“配置 Neo4j”可回到配置，“刷新本地状态”只读取本地状态。
2. 核对地址、数据库、独占命名空间、用户名、密码环境变量名与超时。状态为“等待手工确认”只说明本地配置可用，
   不表示已经连接或验证目标。
3. 展开“查看发送范围与隐私说明”。同步的是当前有界图快照，**不是当前页面筛选结果，也不是全部历史记录**。
4. 点击“确认并同步…”。界面会再次读取目标并显示确认框；核对目标和数据范围后继续，取消则不连接、不发送。
   确认后若目标配置已改变，请刷新并重新确认，不会静默转发到新目标。
5. 等待结果。执行中不重复提交；失败不会禁用本地其他功能。成功状态表示快照写入完成，不证明来源覆盖完整或推断正确。

每次发送包括：

- 当前有效的来源实体，以及用户手工项目/主题的稳定 ID、类型、名称、描述、别名与 `mergedIds` 合并身份。
- 用户纠错后仍有效的最终关系，保留 `predicate`、`claimType`（如 `inferred`、`confirmed`）和
  `status`（`accepted` 或 `candidate`）；被否定的关系不作为当前关系发送。
- 允许的来源标识、周期起止、证据引用及证据文本，以及实体 `evidenceJson`、`attributesJson` 和关系 `evidenceJson`。
- 当前来源的覆盖说明；缺失、不可用、粗粒度省略或达到来源上限不能被解释为完整覆盖。

这些字段可能包含私密标题、项目名称和用户描述。同步不读取新的普通文件正文、截图、OCR/UIA 原文、
完整 prompt 或被过滤的敏感记忆。Neo4j 服务及其管理员可以访问收到的内容，请只使用你信任并有权发送数据的目标。

来源仍受本体既有上限约束：最多读取最近 5000 个有效 Wiki 条目，来源投影最多约 20000 个实体和
100000 条关系，并保留覆盖报告。添加手工实体、合并和纠错后，导出另设 **60000 个实体、100000 条断言、
16 MiB 序列化负载**的硬上限；超过任一导出预算时整体失败，连接前停止，不截断后发送，也不清理远端。

## 4. 远端结构与清理语义

| 对象 | 标识与主要属性 |
|---|---|
| `SelfAnalystEntity` 节点 | `namespace` + `id`；业务类型在 `type` 属性，当前实体 `tombstone=false` |
| `SELF_ANALYST_ASSERTION` 关系 | `namespace` + `id`；实际谓词在 `predicate` 属性，不生成任意关系类型 |
| `SelfAnalystSync` 元节点 | 每个 `namespace` 一个；记录 `syncedAt`、数量、`bytes`、`coverageJson`、`schemaVersion` 与 `periodMeaning` |

`evidenceJson`、`attributesJson` 和 `coverageJson` 是 JSON 字符串。别名与 `mergedIds` 是列表；
合并后的旧 ID 由目标实体的 `mergedIds` 表达，不单独保留一个有效旧实体。周期 `start`/`end` 是时间字符串，
未知时为空字符串；这些周期不是任务精确起止时间，不应相加生成项目耗时。

唯一约束的准备独立执行；所有快照实体/关系的写入、过期清理和同步元信息更新在**同一个图数据事务**中完成。
重复同步稳定身份不会新增副本；图数据事务失败时不会发布半份快照。网络在提交附近中断可能使客户端无法确认结果，
这时检查元信息再手工重试，不要把未收到成功响应解释为远端必定没改变。

网络操作超时后不会继续排队发送新的批次或提交。若已经发出提交请求，却在等待确认时超时，远端仍可能已经成功提交。
应用会关闭连接；若驱动清理尚未完成或失败，暂不允许新的同步，以免叠加悬挂请求。等待清理成功后再手工重试；
若持续不可用，完全退出并重启应用后重新核对目标。此过程不会改变本地权威数据。

- 清理仅限当前命名空间内的应用投影，不删除其他命名空间、无关节点或外部关系。
- 过期应用节点若仍有外部关系引用，会移除其应用内容属性，并保留 `tombstone=true` 的身份占位节点，外部关系不删除。
  查询当前业务实体时必须排除墓碑。
- 本地纠错、删除或来源失效不会立即通知远端；**下次成功手工同步**才移除相应旧关系和实体内容。
- **关闭同步不会擦除远端数据**；更换地址、数据库或命名空间也不会清理旧目标。远端副本、备份、外部附加内容和清理操作
  由目标数据库的管理者负责。改用新命名空间后，不要假设旧副本已经消失。
- 对投影的远端修改不会写回本地，后续同步可能覆盖应用拥有的字段；不要用 Neo4j 代替本地纠错和备份。

## 5. 只读 Cypher 示例

在 Neo4j Browser 中选择配置对应的数据库。先执行以下参数命令，把示例值替换为你的独占命名空间；
这只设置查询参数，不写入数据库。参数用法见 [Neo4j Browser 官方文档](https://neo4j.com/docs/browser/operations/query-parameters/)。

```cypher
:param ns => 'demo-local-001'
```

查看此命名空间最近一次成功快照及覆盖说明：

```cypher
MATCH (s:SelfAnalystSync {namespace: $ns})
RETURN s.syncedAt AS syncedAt, s.entities AS entities,
       s.assertions AS assertions, s.bytes AS bytes,
       s.coverageJson AS coverageJson, s.periodMeaning AS periodMeaning;
```

查看当前项目和主题，排除已失效的墓碑：

```cypher
MATCH (n:SelfAnalystEntity {namespace: $ns, tombstone: false})
WHERE n.type IN ['project', 'topic']
RETURN n.id AS id, n.type AS type, n.name AS name,
       n.aliases AS aliases, n.mergedIds AS mergedIds
ORDER BY type, name
LIMIT 100;
```

查看有效关系图，限制两端节点和关系的命名空间：

```cypher
MATCH (a:SelfAnalystEntity {namespace: $ns, tombstone: false})
      -[r:SELF_ANALYST_ASSERTION {namespace: $ns}]->
      (b:SelfAnalystEntity {namespace: $ns, tombstone: false})
RETURN a, r, b
LIMIT 100;
```

查看项目的已接受活动关联，保留断言性质和证据，不把候选当作确认：

```cypher
MATCH (a:SelfAnalystEntity {namespace: $ns, tombstone: false, type: 'activity'})
      -[r:SELF_ANALYST_ASSERTION {namespace: $ns, predicate: 'relatedTo', status: 'accepted'}]->
      (p:SelfAnalystEntity {namespace: $ns, tombstone: false, type: 'project'})
RETURN p.name AS project, a.name AS activity,
       r.claimType AS claimType, a.start AS periodStart, a.end AS periodEnd,
       r.evidenceJson AS evidenceJson
ORDER BY periodStart DESC
LIMIT 100;
```

以上只读示例使用标准 [Cypher MATCH](https://neo4j.com/docs/cypher-manual/current/clauses/match/)，不需要 APOC。
`accepted` 仍可能是规则推断；`confirmed` 只表示用户确认关系，不证明项目完成、目标达成或因果关系。

## 6. 状态与故障处理

| 状态/错误 | 检查方式 |
|---|---|
| 已关闭 | 确认确实需要发送后，在高级配置启用并保存 |
| 配置不完整、缺少密码 | 检查环境变量名称及启动进程能否继承非空密码；更改环境后完全退出再启动 |
| 配置无效 | 检查 URI 允许列表、独占命名空间、数据库名和超时范围 |
| 身份验证失败 | 核对目标账号和密码环境；不要把密码粘贴到报错截图或配置 |
| 无法连接 | 检查服务可用性、主机、端口、网络和受信任证书；不要改用 `+ssc` 绕过证书校验 |
| 同步超时 | 远端可能已提交但未返回确认；检查同步元信息，等待连接清理结束后再手工重试，不假定未发送或未写入 |
| 同步失败 | 检查写入、创建约束、`SHOW CONSTRAINTS` 权限、约束定义和 `schemaVersion`；未知版本或冲突不自动覆盖，应用只显示分类错误 |
| 快照超过容量 | 本次未连接或清理目标；检查来源覆盖和本地实体规模，不把更换命名空间当作缩小快照的方法 |
| 正在同步 | 等待请求及连接清理结束，再刷新状态；不要重复点击，若清理失败持续阻断则完全退出并重启应用 |

## 验证范围

2026-10-04 在 Linux、Java 21 上完成合成验收，使用官方隔离打包驱动 `neo4j-java-driver-all:5.28.15`
与认证开启、仅环回监听的临时 Neo4j 5.26 测试实例；未连接真实用户数据库或调用模型。

- `mvn test`：1029 个 Java 测试，1025 通过、4 跳过、0 失败；桌面 Node 测试 200 通过、31 跳过、0 失败。
- 本体模块 28 个测试全通过，包括 5 个认证集成测试及 2 个真实环回协议/驱动关闭超时测试。
- 覆盖重复同步、纠错/合并、来源失效、参数注入防护、命名空间隔离、外部关系保留、晚批次事务回滚、
  同名错误约束及未来 schema 拒绝、取消、重复请求与目标变化。
- `mvn package -DskipTests` 构建成功；打包 JAR 的默认关闭服务及隔离驱动加载冒烟通过，未请求连接。
- 应用运行依赖仍使用原有 Lucene 和 Reactor；测试用 Neo4j harness 和 Lucene 9 不进入应用运行依赖。

可单独运行 `mvn -pl self-analyst-ontology -am test` 复验同步器，或运行完整 `mvn test`。
四个 Java 跳过项为 Windows/UIA 环境测试和未启用的手工/基准测试；Node 跳过项遵循既有测试环境条件。
未验证真实远端 TLS 服务、原生 Windows 桌面和 Windows 全量 CI。云浏览器未允许打开合成 localhost
预览，因此未完成可视截图验收；UI 交互依据 Node/HTTP 回归测试验证。本记录不表示已正式发布。
