## Context

动机见 proposal.md。现有 WikiEntry.TaskSegment 带 evidenceFactIds/claimType，但没有跨周期实体 ID，也没有精确任务时间。WikiStore 的有效性与隐私口径、MemoryStore 的 memory.json 权威必须保持。桌面通过 Javalin 受管认证，前端为无打包器共享脚本，Agent 的工具模板和当前实例都需要注册。

## Goals / Non-Goals

**Goals:** 将本体的定义、用户决策和来源投影分离，实现跨日期关联、纠错优先、来源可解释、离线可用和本地恢复；将项目、主题、目标、模式与改进关系完整呈现给用户。

**Non-Goals:** 不从窗口标题推断完成状态、精确任务耗时或心理特征；不引入云服务、普通文件内容采集、OWL 推理器或额外模型调用；不修改既有记忆 API 的管理权限和存储权威。正式 release/tag 发布不属于本次提交/push 的授权，版本代码准备为 0.6.0。

## Decisions

### 模块与类型

新增 self-analyst-ontology，依赖既有 Jackson、SQLite 和测试库，定义 Entity、Assertion、Evidence、SourceSnapshot、OntologyStore/Service，避免依赖 app 形成环。应用中的适配器读取 Wiki/Memory 并生成不可变快照。project/topic 可手工维护，其余实体从已有来源生成并只读；源项目记忆也只读，用户可建立独立项目并关联。

关系白名单为 activity-relatedTo-project、activity-uses-application、activity-about-topic、project-supports-goal、pattern-relatedTo-project/topic、improvement-tracks-goal。每条关系保存来源、断言性质、证据、时间和状态，用户关系必须明确标识 manual。类型校验代替第一版通用 RDF 引擎，减少部署复杂度。

### 持久化与并发

独立 ontology.db 使用版本准入；用户实体/别名、合并重定向、用户关系和否定记录为权威数据，派生实体/关系是可替换投影。使用单连接串行访问及事务原子重建，不在源库连接或模型调用中持有写事务。用户变更在同一提交路径校验与写入。重建保留用户数据，失败回滚；损坏与未知版本不覆盖。旧版本回退可忽略新库，备份需要保留整个用户数据根。

### 来源适配与新鲜度

读取当前口径 SUMMARIZED Wiki 的任务片段，优先小时粒度用于活动时间线，其他层级作为无小时覆盖区间的回退，避免把不同层级当成独立耗时。活动 ID 带 entry ID 及片段内容指纹，事实引用带 entry 命名空间。保留来源层级、周期时间、引用和覆盖；不填造没有的精确时长。无证据 legacy 片段展示 legacy/证据缺失。

Memory 通过受控同步快照提取 active 项及有效 legacy goals/patterns/logs，使用来源 ID 标识，goalId 缺失只标记缺失。查询前协调最新来源，来源不可用时返回错误，不把旧正文交给用户或 Agent；指纹未变跳过投影写入与关系重算；用户决定提交后使关系缓存失效。通过分页读取和明确处理上限限制一次重建成本，覆盖状态报告未处理来源，不能宣称全量。应用关闭先停止本体访问再关闭 Wiki/Memory。

### 解析与用户决定

规范化名称/别名做 Unicode、大小写和边界匹配；中文允许连续短语匹配，拉丁词避免 Java 命中 JavaScript。项目/主题别名可多个。唯一项目命中生成 inferred，多个命中保留歧义，无匹配为未归类；用户确认/改正优先，拒绝记录阻止同关系复活。项目合并仅同类型手工实体，事务迁移别名、关系和否定记录并建立重定向，拒绝自合并/环。规则命中仅是分类依据，不升级源活动的完成或因果断言。

### 桌面与 Agent

新增 /desktop/ontology 下的状态、搜索、实体详情、实体写操作、关系写操作、活动项目纠错、合并与重建 API，沿用桌面认证，输入长度/类型/时间/分页参数校验，错误不泄露原始异常。

新增知识页，包含类型筛选、搜索、可分页列表和实体详情、时间过滤、证据/歧义/来源说明；手工项目/主题的表单与别名编辑；关系选择和确认/拒绝/改正；目标/模式/改进只读来源提示；重建结果及错误反馈。采用现有双语资源和安全 DOM/textContent，列表而非强制图形布局保证键盘和小窗口可用性。

只读 Agent 工具支持搜索和详情（含关系、活动和证据），固定条数及序列化预算；注册当前 toolkit 与热更新模板。指导模型保留 inferred/legacy、缺失证据和周期时间限制。

### 0.6 交付

统一 Maven 子模块、Tauri/Cargo/package 及面向用户的当前版本标识为 0.6.0，历史 changelog 不改写。更新中英文 README、中文本体指南和架构。通过 OpenSpec 严格校验、核心与 API/JS 回归、mvn test、Rust 版本相关检查及打包检查；截图附 PR。功能分支提交/push 后等待最新提交 Windows 全量验证，未经授权不合并或打 tag。

## Risks / Trade-offs

- 标题别名误匹配 → 可解释 inferred、保留歧义、拒绝规则及用户纠错优先。
- 重建数据量增长 → 有界来源读取、明确覆盖、分页查询与指纹避免无效重写。
- 来源失效与模型缓存 → 查询前校验最新来源，停用/删除的正文不再通过工具返回。
- 缺少精确任务区间 → 明示周期范围、不提供伪精确项目耗时。
- 用户决定被重建覆盖 → 独立表、单事务校验、失败回滚、重启/重建回归。

## Migration Plan

新增模块与独立 schema 1 本体库；首次进入或查询时从现有允许来源构建。升级不重算 Wiki、不迁移 memory.json、不读取普通文件。备份用户数据根涵盖 ontology.db；回退 0.5 保留新库，回到 0.6 后继续使用。未知格式/损坏返回本体不可用并保留文件供恢复。无格式标记的数据根接纳流程需识别 ontology.db 的 schema 与记录结构，验证过程只读。
