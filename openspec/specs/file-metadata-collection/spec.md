# 文件元数据采集规格

## Purpose

定义用户显式配置目录中的本地文件元数据采集能力，确保路径发现、状态收敛、持久化、查询和桌面展示始终遵守不读取普通文件正文的隐私边界。

## Requirements

### Requirement: SPEC-FILE-001 采集字段白名单
系统 SHALL 只采集文件名、绝对路径、相对路径、监控根目录、扩展名、字节大小、文件系统创建时间、
最后修改时间，以及采集状态、重试和错误分类所需的元数据。

#### Scenario: SPEC-FILE-TST-004 正确采集允许的元数据
- **WHEN** 已启用监控根目录中出现符合过滤规则的普通文件
- **THEN** 系统记录该文件的路径、大小、创建/修改时间和采集状态，且不添加正文派生字段

### Requirement: SPEC-FILE-002 普通文件正文禁止读取
系统 MUST NOT 为采集目的打开普通被监控文件的内容流。系统 MAY 为路径过滤在进程内读取大小受限且
身份验证通过的 `.gitignore`；解析所得 matcher 可保留在进程内缓存，但规则不得持久化、写入日志或
错误记录，也不得外发。

#### Scenario: SPEC-FILE-TST-001、SPEC-FILE-TST-002 正文秘密不进入采集链路
- **WHEN** 被监控普通文件正文包含唯一秘密标记并完成元数据采集
- **THEN** 采集过程不读取其内容流，文件元数据数据库及其日志文件均不包含该秘密标记

#### Scenario: 安全解析 gitignore 例外
- **WHEN** 路径过滤需要读取监控树内符合安全限制的 `.gitignore`
- **THEN** 系统只在进程内解析过滤规则，且不把规则内容写入持久化、日志、错误记录或网络请求

### Requirement: SPEC-FILE-003 禁止正文派生数据
系统 MUST NOT 为被监控普通文件计算内容哈希，也不得生成或保存正文摘要、主题、用途、prompt、模型
名称、正文 embedding 或语义等价数据。

#### Scenario: SPEC-FILE-TST-001、SPEC-FILE-TST-003 不产生正文派生信息
- **WHEN** 文件完成采集或旧数据完成净化
- **THEN** 当前存储和查询结果不包含内容哈希、摘要、主题、prompt、模型或正文 embedding

### Requirement: SPEC-FILE-004 正文无外发路径
系统 MUST NOT 将普通文件正文发送给 LLM、embedding、MCP、搜索服务或其他进程。文件名、路径和时间
等元数据的匹配查询 SHALL 在本地完成；当这些元数据作为 Agent 工具结果使用时，MAY 进入用户配置的
LLM 会话，因此不得将“本地查询”解释为“元数据永不外发”。

#### Scenario: 本地元数据查询
- **WHEN** Agent 按文件名、路径、扩展名或时间查询文件
- **THEN** 系统在本地元数据存储中完成匹配，返回白名单元数据且不读取或外发文件正文

### Requirement: SPEC-FILE-005 文件标题事实
系统 SHALL 只把文件名及其相对路径作为文件能力的标题事实，MUST NOT 将正文首行、摘要或主题冒充
文件标题。

#### Scenario: 文件结果展示标题
- **WHEN** 文件出现在 Agent 工具或桌面查询结果中
- **THEN** 展示名称来自文件名或相对路径，而不是正文派生内容

### Requirement: SPEC-FILE-010 采集状态集合
文件元数据记录 SHALL 支持 `PENDING`、`COLLECTED`、`FAILED`、`SKIPPED` 和 `DELETED` 状态，以表达
待处理、成功、失败、跳过和已缺失的生命周期结果。

#### Scenario: 状态可被查询
- **WHEN** 客户端查询采集状态统计或文件列表
- **THEN** 系统使用约定状态值表达每条记录的当前采集结果

### Requirement: SPEC-FILE-011 新建和修改状态收敛
系统 SHALL 在发现新的合规文件或允许的元数据发生变化时将记录置为 `PENDING`，并在成功读取允许的
文件系统属性后将其置为 `COLLECTED`。

#### Scenario: 文件修改后重新采集
- **WHEN** 已采集文件的大小、创建时间或最后修改时间发生变化
- **THEN** 记录先进入 `PENDING`，成功读取最新元数据后进入 `COLLECTED`

### Requirement: SPEC-FILE-012 删除和重新出现
系统 SHALL 将确认不存在的已知文件标记为 `DELETED`；同一路径的合规文件重新出现时 SHALL 能重新进入
`PENDING` 并恢复采集。

#### Scenario: SPEC-FILE-TST-012 停机期间删除收敛
- **WHEN** 文件在监控停止期间被删除且后续完整对账确认其缺失
- **THEN** 历史记录进入 `DELETED`

#### Scenario: 文件重新出现
- **WHEN** 已标记 `DELETED` 的路径重新出现符合规则的普通文件
- **THEN** 记录重新进入 `PENDING` 并可收敛到 `COLLECTED`

### Requirement: SPEC-FILE-013 新失败的安全错误记录与退避
当前采集流程处理已入队的 `PENDING` 或 `FAILED` 记录时，新产生的属性读取或安全过滤失败 SHALL 只
记录 `FILE_METADATA_FAILED:<ExceptionType>` 形式的错误码，MUST NOT 保存异常 message，并 SHALL
按失败次数延后重试。由旧数据库迁移而来的历史错误值 MAY 保留旧前缀或旧跳过原因，不受新失败格式
约束。

#### Scenario: 当前采集失败
- **WHEN** 当前采集流程处理已入队的 `PENDING` 或 `FAILED` 记录时抛出元数据或安全过滤异常
- **THEN** 记录进入 `FAILED`，只保存异常类型组成的固定错误码并安排后续重试

### Requirement: SPEC-FILE-014 有界批处理
后台采集每轮 MUST 最多处理 256 条待处理或可重试记录，避免一次性积压占用采集线程。

#### Scenario: 待处理记录超过单轮上限
- **WHEN** 同一轮存在超过 256 条到期记录
- **THEN** 本轮最多处理 256 条，其余记录保留到后续轮次

### Requirement: SPEC-FILE-020 内置目录和点目录排除
系统 MUST 不区分大小写排除 `.git`、`node_modules`、`target`、`build`、`dist`、`.gradle`、`.idea`、
`.vscode`、`out`、`bin`、`obj`、`.mvn`、`__pycache__`、`venv`、`.venv`、`coverage`、
`bower_components`、`vendor` 和 `Pods` 目录，并 SHALL 跳过所有点前缀目录的整棵子树。

#### Scenario: SPEC-FILE-TST-017 工具目录被早期排除
- **WHEN** 扫描遇到大小写变体的内置目录名或点前缀目录
- **THEN** 系统不注册、不遍历也不采集该目录子树

### Requirement: SPEC-FILE-021 用户过滤和无效配置拒绝
系统 SHALL 排除隐藏文件或目录、超过 `maxFileSizeKb` 的文件，以及匹配用户目录名或相对监控根 glob
的路径。目录 glob 命中时 SHALL 在遍历和监控注册前跳过整棵子树；无效过滤配置 MUST 被拒绝保存或
拒绝启动相应采集。

#### Scenario: SPEC-FILE-TST-017 相对路径 glob 排除目录
- **WHEN** 某个目录的相对路径命中用户配置的排除 glob
- **THEN** 系统在扫描和注册前跳过该目录及其全部后代

#### Scenario: 无效过滤配置
- **WHEN** 用户提交无法解析或不安全的过滤 token
- **THEN** 系统拒绝保存或启用该配置，并返回可诊断但不泄露文件内容的错误

### Requirement: SPEC-FILE-022 敏感文件默认排除
系统 SHALL 默认排除 `.env`、`.env.*`、`*.pem`、`*.key`、`id_rsa*`、`*.p12` 和
`*.keystore` 等敏感文件。

#### Scenario: 敏感凭据文件出现
- **WHEN** 监控根目录中出现匹配默认敏感文件规则的路径
- **THEN** 系统不采集、不查询也不发送该路径的 heartbeat

### Requirement: SPEC-FILE-023 高频易变文件默认排除
系统 SHALL 默认排除 `*.log`、`*.tmp`、`*.temp`、`*.lock`、`*.swp`、`*~`、`~$*`、
`*.autosave` 和 `*.bak` 等高频易变或临时文件。

#### Scenario: SPEC-FILE-TST-017 Office 临时文件被排除
- **WHEN** 监控根目录中出现 `~$*` 或其他默认临时文件
- **THEN** 系统在进入采集队列前排除该文件

### Requirement: SPEC-FILE-024 扩展名允许列表语义
系统 SHALL 默认只允许 Office 和 Markdown 扩展名；扩展名比较 MUST 不区分大小写并接受至多一个
前导点。空允许列表 MUST 不采集任何文件，只有显式 `*` 才允许全部扩展名，非法 token MUST 被拒绝。

#### Scenario: SPEC-FILE-TST-016 空列表与通配符
- **WHEN** 扩展名列表为空
- **THEN** 系统不采集任何普通文件
- **WHEN** 扩展名列表显式包含 `*`
- **THEN** 系统允许所有其他安全过滤均通过的扩展名

#### Scenario: 不支持的扩展名
- **WHEN** 文件扩展名不在允许列表中且未配置 `*`
- **THEN** 系统仅凭文件名和已有属性尽早拒绝该文件，不读取正文

### Requirement: SPEC-FILE-025 gitignore 过滤和安全失败
启用 `respectGitIgnore` 时，系统 SHALL 支持监控根及嵌套 `.gitignore` 的目录、通配、锚定和否定规则，
并在规则变更后重新评估受影响子树。被上层规则排除的目录 MUST NOT 由其内部规则重新包含后代。
规则文件超过 1 MiB、不是安全普通文件、路径身份无法验证或读取过程中发生身份变化时，系统 MUST 对
相关路径树 fail-closed。解析后的规则 MAY 以内存 matcher 及缓存存在，但 MUST NOT 持久化、记录或外发。

#### Scenario: SPEC-FILE-TST-018 嵌套规则和运行时变更
- **WHEN** 根或嵌套 `.gitignore` 的过滤规则发生新增、修改或删除
- **THEN** 系统失效相关内存 matcher，重新评估子树并最终使采集状态符合新规则

#### Scenario: SPEC-FILE-TST-020 无法安全判定规则
- **WHEN** 系统无法证明 `.gitignore` 或其路径链在读取期间保持安全身份
- **THEN** 系统暂不采集受影响路径树，且不把既有记录误标为已删除

### Requirement: SPEC-FILE-026 链接和监控根边界
系统 MUST 不跟随符号链接，且 MUST 对文件系统属性报告为 symbolic link 或 `isOther` 的节点、监控根
之外的路径以及无法证明安全的特殊节点 fail-closed。文件判定 SHALL 验证从监控根到父目录的路径链，
而不只检查最终文件；本保证不扩展为未经平台证据验证的所有 Windows reparse point 分类。

#### Scenario: SPEC-FILE-TST-019 链接或越界路径
- **WHEN** 候选路径是链接、特殊节点或规范化后位于监控根之外
- **THEN** 系统不注册、不遍历也不采集该路径

### Requirement: SPEC-FILE-030 启动时递归发现目录
采集启用时，系统 SHALL 递归发现并监控所有通过安全过滤的子目录，同时跳过被排除或无法安全验证的
目录树。

#### Scenario: 启用已有目录树
- **WHEN** 用户启用包含多级合规子目录的监控根
- **THEN** 系统发现这些子目录，并能够接收后续文件系统变化

### Requirement: SPEC-FILE-031 创建和修改去抖
系统 SHALL 对同一路径的 CREATE/MODIFY 变化执行静默期去抖，并且只形成元数据采集意图，不读取普通
文件正文。

#### Scenario: 高频连续修改
- **WHEN** 同一路径在静默期内连续产生多个 CREATE/MODIFY 事件
- **THEN** 系统合并这些事件并在静默期后安排一次元数据采集

### Requirement: SPEC-FILE-032 文件删除处理
系统 SHALL 在收到已知文件的删除变化时更新其状态，MUST NOT 尝试读取已删除文件。

#### Scenario: SPEC-FILE-TST-012 运行期间删除文件
- **WHEN** 已采集文件在监控运行期间被删除
- **THEN** 对应记录最终进入 `DELETED`，且删除处理不读取文件

### Requirement: SPEC-FILE-032a 目录和根删除收敛
系统 SHALL 在确认目录被删除，或监控根因目录消失而失效时，将该路径下已采集的后代记录最终标记为
`DELETED`；无法确认目录不存在时 MUST NOT 淘汰历史记录。

#### Scenario: SPEC-FILE-TST-014 监控根消失
- **WHEN** 监控根目录被删除并导致其监控状态失效
- **THEN** 系统确认根不存在后将其后代历史记录标记为 `DELETED`

### Requirement: SPEC-FILE-033 heartbeat 节流
系统 SHALL 按配置对同一路径的文件元数据 heartbeat 进行节流，避免短时间内重复发送等价事件。

#### Scenario: 节流窗口内重复变化
- **WHEN** 同一路径在 heartbeat 节流窗口内多次完成去抖变化并准备发送 heartbeat
- **THEN** 系统不会为每次重复状态生成等量 heartbeat

### Requirement: SPEC-FILE-034 heartbeat 字段白名单
文件 heartbeat 数据 SHALL 只包含 `path`、`relative_path`、`watch_root`、`event_type`、`extension`、
`size_bytes`、`file_created_at` 和 `last_modified`，MUST NOT 包含正文、内容哈希、摘要、主题或向量。

#### Scenario: heartbeat 负载审计
- **WHEN** 文件元数据变化生成 heartbeat
- **THEN** heartbeat 只包含约定八个数据字段且不存在正文派生字段

### Requirement: SPEC-FILE-035 规则、溢出和新目录对账
`.gitignore` 的创建、修改或删除 SHALL 只触发过滤缓存失效和受影响子树对账，不作为普通文件采集。
监控事件溢出或发现已填充的新目录时，系统 SHALL 修复目录发现状态并请求安全子树对账，以补回可能
遗漏的变化。

#### Scenario: SPEC-FILE-TST-018 规则变化和事件溢出
- **WHEN** `.gitignore` 变化或文件系统事件发生溢出
- **THEN** 系统重新发现安全目录并对账相关子树，使最终元数据状态收敛

### Requirement: SPEC-FILE-040 首次扫描仅采集元数据
首次扫描 SHALL 只遍历安全路径并读取普通文件的基础文件系统属性，MUST NOT 打开普通文件内容流。

#### Scenario: SPEC-FILE-TST-002 首次扫描正文隔离
- **WHEN** 首次扫描遇到含秘密正文的合规文件
- **THEN** 系统只读取路径和基础属性，秘密正文不进入采集器或持久化

### Requirement: SPEC-FILE-041 元数据变化判定
首次扫描或子树对账判断已知记录是否需要重新采集时，系统 SHALL 仅比较大小、文件系统创建时间、
最后修改时间和路径归属，MUST NOT 根据正文或正文哈希判断变化。实时 CREATE/MODIFY 事件 MAY 直接
形成 `PENDING` 意图，不受扫描对账比较条件限制。

#### Scenario: 仅正文变化但元数据时间变化
- **WHEN** 文件最后修改时间或大小发生变化
- **THEN** 系统依据这些元数据安排采集，而不比较正文内容

### Requirement: SPEC-FILE-042 失败重试仍遵守正文边界
处理 `PENDING` 或 `FAILED` 记录时，系统 MUST NOT 打开普通文件内容流；系统 MAY 执行存在性检查、
读取基础文件系统属性，以及完成隐藏项、链接、祖先目录和 `.gitignore` 安全过滤所需的检查。

#### Scenario: SPEC-FILE-TST-002 失败重试不读取正文
- **WHEN** 失败记录到达重试时间
- **THEN** 系统只执行元数据和路径安全检查，不因重试而读取普通文件正文

### Requirement: SPEC-FILE-043 与 Agent 和模型服务解耦
文件元数据采集 SHALL 不依赖 Agent、LLM、embedding 配置或 API key；这些服务未配置或不可用时，
采集仍 SHALL 能启动并处理本地元数据。

#### Scenario: SPEC-FILE-TST-007 模型服务不可用
- **WHEN** Agent 或模型服务未配置、初始化失败或离线
- **THEN** 已启用的本地文件元数据采集仍可运行

### Requirement: SPEC-FILE-044 完整扫描后的缺失记录淘汰
只有完整结束且未发生访问错误的根目录扫描，才 SHALL 将扫描中未再出现的历史记录标记为 `DELETED`。
扫描被取消或发生访问错误时 MUST NOT 执行缺失记录淘汰；扫描期间并发新增或更新且文件仍存在的记录
MUST NOT 被误删。

#### Scenario: SPEC-FILE-TST-013 并发创建保护
- **WHEN** 文件在扫描快照之后由实时监控新增并入队
- **THEN** 完整扫描对账不会把仍存在的并发新记录标记为 `DELETED`

#### Scenario: SPEC-FILE-TST-020 扫描访问错误
- **WHEN** 扫描期间发生访问错误或被取消
- **THEN** 系统保留既有记录，等待后续安全对账恢复收敛

### Requirement: SPEC-FILE-045 有界子树对账
系统 SHALL 接受位于当前监控根内的子树对账请求，使用与完整扫描一致的安全过滤，并最终使新排除的
记录进入 `DELETED`、重新包含的记录进入 `PENDING`。越过当前监控根的请求 MUST 被拒绝。

#### Scenario: 过滤规则改变包含关系
- **WHEN** 规则变化使某个安全子树从包含变为排除或从排除变为包含
- **THEN** 子树对账使相关记录分别收敛到 `DELETED` 或 `PENDING`

### Requirement: SPEC-FILE-050 当前存储和模型字段边界
当前文件元数据存储、正常采集模型和查询响应 MUST 不提供 `content`、`file_hash`、`summary`、
`main_topics`、`model`、`prompt`、`embedding` 或语义等价的专用字段；正常采集路径 SHALL 只写入
允许的元数据。该保证描述字段和写入路径边界，不声称通用文本字段在类型上无法容纳任意字符串。

#### Scenario: SPEC-FILE-TST-003、SPEC-FILE-TST-005 字段结构审计
- **WHEN** 检查当前数据库结构、Agent 文件工具响应和桌面文件响应
- **THEN** 不存在正文或正文派生专用字段，允许的文本字段只承载约定元数据或安全错误分类

### Requirement: SPEC-FILE-051 旧数据库派生数据净化
打开旧版或未标版本的文件数据库时，系统 SHALL 只迁移允许的元数据，将旧成功状态映射为当前成功
状态，并在物理净化成功后才确认当前 schema 版本。净化中断后 SHALL 能在下次启动继续。迁移 MAY
保留旧记录已有的重试计数、重试时间和旧错误分类，但不得保留正文派生列或数据。

#### Scenario: SPEC-FILE-TST-003 旧派生列净化
- **WHEN** 系统打开含旧摘要、主题、模型或向量字段的旧数据库
- **THEN** 迁移后的当前结构只保留允许元数据，且旧派生内容经物理净化后不可从数据库文件恢复

#### Scenario: SPEC-FILE-TST-010 净化中断恢复
- **WHEN** 进程在删除旧结构后、确认当前版本前中断
- **THEN** 下次启动识别中间态，继续净化并在成功后确认当前版本

### Requirement: SPEC-FILE-052 并发访问可靠性
本地文件元数据存储 SHALL 使用可恢复日志和有界竞争等待，并 SHALL 串行化同一存储实例的跨线程访问，
以避免正常并发采集和查询造成数据库损坏或瞬时锁竞争直接失败。

#### Scenario: 采集与查询并发
- **WHEN** 后台采集与桌面或 Agent 查询并发访问文件元数据
- **THEN** 系统协调访问且保持数据库结构和记录一致性

### Requirement: SPEC-FILE-053 旧语义索引的保守清理
启动期清理旧文件语义索引前，系统 MUST 验证目标的目录类型、规范化路径边界、完整文件集合和旧索引
提交证据。文件系统根、链接、混合目录、伪造或无法验证的目录 MUST 在删除任何文件前整体拒绝。清理
中断后 SHALL 保留足以再次验证的提交证据，并在后续启动安全重试。

#### Scenario: SPEC-FILE-TST-006 合法旧索引被净化
- **WHEN** 旧索引目录完整通过目录类型、路径边界、文件集合和提交证据验证
- **THEN** 系统按可恢复顺序清理旧语义索引 artifact

#### Scenario: SPEC-FILE-TST-009、SPEC-FILE-TST-015 危险或伪造目录被拒绝
- **WHEN** 目标是根目录、链接、混合目录，或只伪造文件名前缀而没有有效提交证据
- **THEN** 系统在删除任何文件前拒绝整个清理操作

### Requirement: SPEC-FILE-054 旧语义配置不再受支持
旧 `file.watch.semantic.*`、`maxContentChars` 和 `minReindexIntervalMinutes` 配置 MUST NOT 再启用
正文提取或语义索引。旧语义索引路径 MAY 只在安全净化期间读取。

#### Scenario: 载入旧配置
- **WHEN** 用户配置仍包含旧文件语义键
- **THEN** 系统不会启动正文、摘要或 embedding 功能，且当前配置界面不把这些键作为受支持能力暴露

### Requirement: SPEC-FILE-060 FileTools 响应边界
FileTools SHALL 只返回文件名、路径、监控根、扩展名、大小、文件系统时间、采集时间、状态和根目录
统计等白名单元数据，MUST NOT 返回摘要、主题、内容哈希、模型、prompt 或正文。

#### Scenario: SPEC-FILE-TST-005 Agent 文件工具响应
- **WHEN** Agent 调用任一 FileTools 查询
- **THEN** 工具结果只包含白名单元数据且不包含正文派生字段

### Requirement: SPEC-FILE-061 FileTools 本地元数据匹配
文件搜索 SHALL 在本地元数据中按文件名、相对路径、目录、扩展名和修改时间匹配，MUST NOT 调用
embedding 或语义搜索服务。匹配结果作为 Agent 工具消息时 MAY 进入用户配置的 LLM。

#### Scenario: 文件关键词搜索
- **WHEN** Agent 使用关键词和可选目录、扩展名或时间条件搜索文件
- **THEN** 系统在本地路径字段中完成匹配并返回白名单元数据，不读取正文或调用 embedding

### Requirement: SPEC-FILE-062 正文问题的能力说明
当用户询问文件正文内容时，启用 FileTools 的 Agent SHALL 明确说明该能力无法读取正文，只能提供
文件名、路径、大小和时间等元数据。

#### Scenario: 用户询问文件写了什么
- **WHEN** 用户要求根据文件采集结果总结或回答普通文件正文
- **THEN** Agent 说明 FileTools 的 metadata-only 边界，不声称已读取或理解文件内容

### Requirement: SPEC-FILE-063 FileTools 当前监控根限制
FileTools 查询 SHALL 只暴露当前启用监控根内的元数据；禁用文件采集或移除监控根后，FileTools
MUST 隐藏对应历史名称、路径和时间。本保证只覆盖 FileTools，不扩展到通用 ActivityWatch 查询工具
或原始桌面历史接口。

#### Scenario: SPEC-FILE-TST-011 禁用或移除监控根
- **WHEN** 文件采集被禁用，或某个监控根从当前设置中移除
- **THEN** FileTools 不再返回该范围的历史文件元数据

### Requirement: SPEC-FILE-070 桌面文件接口字段边界
`GET /desktop/files` SHALL 始终返回采集启用状态、运行状态、设置变更是否需重启、各配置根的状态
计数、总计和文件列表；每个文件 SHALL 提供名称、路径、相对路径、监控根、扩展名、状态、大小和
创建/修改/采集时间。`reason` SHALL 只在运行状态存在原因时返回，`error` SHALL 只在存在启动或存储
错误时返回，`latestCollectedAt/latestPath` SHALL 只在至少存在一条最近采集记录时返回。响应 MUST NOT
提供 `semantic`、`summary`、`mainTopics` 或 `lastIndexedAt`。禁用采集本身不保证原始接口清空仍配置
根的历史数据。

#### Scenario: SPEC-FILE-TST-005 桌面响应字段审计
- **WHEN** 客户端请求桌面文件状态
- **THEN** 响应只包含约定状态和元数据字段，不含语义、摘要、主题或旧索引时间字段

### Requirement: SPEC-FILE-071 桌面界面元数据术语
桌面界面 SHALL 使用“已采集”和“元数据”等准确术语，MUST NOT 将文件能力描述为内容索引、摘要或
语义检索。采集禁用时界面 SHALL 显示禁用状态，而不是把历史元数据渲染为正在采集的结果。

#### Scenario: SPEC-FILE-TST-008 文件页面展示
- **WHEN** 用户打开启用或禁用状态下的文件页面
- **THEN** 界面只以元数据采集术语展示当前状态，并且不暗示系统读取正文

### Requirement: SPEC-FILE-072 桌面隐私提示
桌面文件功能的隐私提示 MUST 明确说明系统不读取普通文件正文、不计算内容哈希，也不向 LLM 或
embedding 服务发送文件正文。

#### Scenario: SPEC-FILE-TST-008 用户查看隐私说明
- **WHEN** 用户查看文件采集设置或状态页面的隐私提示
- **THEN** 提示准确声明 metadata-only 边界和正文不外发保证

### Requirement: SPEC-FILE-073 文件设置热更新
`PUT /desktop/files/settings` SHALL 支持启用状态与监控目录列表的热更新，并 SHALL 拒绝非绝对、
无法解析、不存在、不是目录或包含逗号的路径，以及超过 100 个路径条目的列表。该接口不负责更新其他
过滤配置。设置响应 SHALL 明确指示变更是否需要重启。

#### Scenario: 更新监控目录
- **WHEN** 用户提交有效目录列表和启用状态
- **THEN** 系统更新当前采集范围并返回运行状态及重启提示

#### Scenario: 普通文件不能作为监控根
- **WHEN** 用户把普通文件路径提交为监控根
- **THEN** 系统拒绝该设置且不启动对应采集

### Requirement: SPEC-FILE-080 文件采集配置默认值和语义
文件采集 SHALL 默认关闭且监控目录默认为空；`maxFileSizeKb` SHALL 默认为 `0`，后台采集间隔 SHALL
默认为 60 秒，去抖和 heartbeat 节流 SHALL 分别默认为 5 秒。系统 SHALL 支持扩展名允许列表、目录/
glob 排除和 `respectGitIgnore` 设置；默认扩展名 SHALL 只包含 Office 与 Markdown 格式，目录和 glob
排除列表 SHALL 默认为空，`respectGitIgnore` SHALL 默认启用。

#### Scenario: 首次使用默认配置
- **WHEN** 用户尚未显式启用文件采集或配置监控目录
- **THEN** 系统不采集文件，且不会因为缺少 Agent、LLM 或 embedding 配置而改变默认状态

### Requirement: SPEC-FILE-090 文件元数据原始事件永久保留
通过文件过滤与字段白名单的每个已发送文件 heartbeat SHALL 在 ActivityWatch 投影前作为合规原始事件永久保存。原始文件事件 MUST 继续只包含允许的路径与文件系统元数据，MUST NOT 读取或保存普通文件正文、内容哈希、摘要、主题或 embedding。去抖与 heartbeat 节流发生在采集器生成原始事件之前，不得通过删除已提交原始事件实现节流。

#### Scenario: 文件 heartbeat 投影合并
- **WHEN** 两个已发送文件 heartbeat 被 ActivityWatch 时间线合并
- **THEN** 两个原始元数据事件均保持存在且只包含文件 heartbeat 白名单字段

#### Scenario: 普通文件正文保持隔离
- **WHEN** 被监控文件正文包含秘密标记并产生元数据原始事件
- **THEN** 原始事件、manifest、日志和投影均不包含正文秘密标记

### Requirement: SPEC-FILE-091 当前文件状态与永久历史分离
文件当前状态存储与 FileTools SHALL 继续按当前启用监控根限制暴露记录；禁用文件采集、移除监控根或把文件标记为 `DELETED` MUST NOT 删除已经提交的原始文件元数据事件。原始历史只能通过受保护的桌面原始事件 API 按明确 bucket 和时间范围查询。

#### Scenario: 移除监控根
- **WHEN** 用户从当前配置移除一个监控根
- **THEN** FileTools 不再返回该根的历史元数据，但永久原始事件计数与内容保持不变
