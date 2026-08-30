# 文件采集后缀与目录过滤加固实施方案

> 状态：已实现并通过独立代码审查
>
> 编写日期：2026-08-30
>
> 基线提交：`a19dfde`（`fix(file): enforce strict metadata-only collection`）
>
> 现行契约：`docs/specs/file.md`
>
> 本文同时记录实施决策与验证结果；现行行为已同步到 `docs/specs/file.md`。

## 实施结果

- 新增 `FileFilterConfig`，统一执行后缀、目录名、root-relative glob、大小上限与
  `respectGitIgnore` 的 fail-closed 解析。
- 默认后缀收窄为 Office + Markdown；空列表不采集，只有显式 `*` 才允许全部类型。
- `PathFilter` 改为 root-aware，扫描与 WatchService 共用相同决策，并拒绝 root 外路径、
  symlink、`isOther`/junction 类特殊节点；不支持的后缀在昂贵的祖先链和 ignore 检查前直接拒绝。
- 目录名大小写无关，点前缀工具目录统一跳过；新增 Office 临时文件和常见工具输出目录。
- 使用 JGit `7.7.1.202607240634-r` 解析根与嵌套 `.gitignore`；缓存绑定目录身份，规则文件按
  size/mtime/creationTime/fileKey 校验，并在读取前后复核完整目录链。规则文件变化、目录生命周期和
  WatchService OVERFLOW 会触发缓存失效、注册修复与合并后的安全子树对账。
- 默认 `maxFileSizeKb` 改为 0，合法大型 Office 文件不再因元数据采集而被排除。
- 配置原始编辑入口会在写盘前拒绝非法后缀、目录、glob 和负数大小上限。
- 过滤错误采用三态判定：暂时无法安全判断时阻止新采集，但不得误删已有元数据。
- 独立只读代码审查最终未发现 Critical/Important 问题。
- 全仓 `mvn test` 已通过：Java 492 项（3 项因手工 UIA 或当前 Windows 符号链接权限按设计跳过），
  Node 75 项，无失败。

## 1. 背景

文件采集器已经完成严格元数据化改造：被监控文件只读取文件系统属性，不读取正文、不计算内容哈希、
不生成摘要或内容 embedding。当前 `PathFilter` 已具备扩展名白名单、内置目录名黑名单、隐藏文件、
敏感文件、易变文件、自定义目录名、文件名 glob 和大小上限过滤。

但当前过滤边界仍有以下不足：

1. `file.watch.extensions` 为空时会允许全部未排除文件，不符合严格模式的 fail-closed 原则。
2. 目录名黑名单按大小写精确比较，Windows/macOS 默认大小写不敏感文件系统上可能出现行为不一致。
3. `excludeGlobs` 只匹配文件名，不匹配监控根目录下的相对路径。
4. 无效 glob 只记录警告并忽略，配置错误可能导致本应排除的文件被采集。
5. 尚未尊重仓库中的 `.gitignore`，大型代码库仍需手工复制大量忽略规则。
6. 链接、Windows junction/reparse point 和监控根目录逃逸缺少显式过滤契约与回归测试。
7. Office 临时锁文件（例如 `~$文档.docx`）拥有合法后缀，可能进入采集队列。
8. 元数据模式仍沿用默认 `maxFileSizeKb=512`，会排除大量正常 Office 文件，且不再有隐私收益。

## 2. 目标

- 文件采集启用后，只采集显式允许后缀的文件元数据。
- 对工具目录、用户排除目录、相对路径 glob 和 `.gitignore` 规则统一执行早期剪枝。
- 初次扫描与实时 WatchService 事件使用完全相同的过滤语义。
- 配置错误时拒绝启动文件采集管线，不得静默放宽采集范围。
- 链接或文件系统特殊入口不得让扫描越出用户配置的监控根目录。
- 保持严格无正文边界；除 `.gitignore` 这类过滤规则文件外，不读取任何被监控文件正文。
- 过滤规则变更后，历史记录能够从 Agent 查询范围中及时退出。

## 3. 非目标

- 不恢复 Word、Excel、PowerPoint、Markdown 或代码正文提取。
- 不做 MIME、magic number 或真实文件类型探测。
- 不打开 Office/PDF/压缩包验证后缀真实性。
- 不运行 `git check-ignore` 或其他外部 Git 命令。
- 不在本期改变 `file-watch.db` v2 schema。
- 不在第一阶段要求扩展名和排除规则无重启热更新；现有 `restartRequired` 语义可以保留。

## 4. 核心决策

### 4.1 扩展名白名单改为 fail-closed

`file.watch.extensions` 采用以下明确语义：

| 配置值 | 行为 |
|---|---|
| 未设置 | 使用内置 Office + Markdown 默认白名单 |
| 空数组/空字符串 | 不允许采集任何文件；collector 进入可诊断降级状态 |
| `"*"` | 显式允许全部未被其他规则排除的后缀 |
| 非空列表 | 仅允许列表中的后缀 |

内置默认白名单：

```text
doc, docx, docm,
xls, xlsx, xlsm, xlsb,
ppt, pptx, pptm,
pps, ppsx, ppsm,
pot, potx, potm,
md, markdown
```

规范化规则：

- 使用 `Locale.ROOT` 转小写。
- 允许配置项带一个前导点，内部统一去掉。
- 只允许 `[a-z0-9][a-z0-9+_-]*` 或单独的 `*`。
- 多个 `*`、空白项、路径分隔符、glob 元字符和无法识别的 token 均视为配置错误。
- 文件后缀仍以文件名最后一个点之后的部分为准；不进行内容探测。

兼容策略：升级后，过去依赖“默认空值等于全部类型”的用户会收窄到内置默认白名单；若确需旧行为，
必须显式设置 `extensions = ["*"]`。这是有意的隐私收紧，需要在配置说明和升级提示中明确。

### 4.2 元数据模式默认取消大小上限

将 `file.watch.maxFileSizeKb` 默认值从 `512` 改为 `0`：

- `0` 表示不按文件大小排除。
- 正数继续作为可选元数据过滤条件。
- 文件大小只通过 `BasicFileAttributes.size()` 或等价属性读取，不打开文件内容。

### 4.3 目录名统一规范化

内置目录名和 `file.watch.excludeDirs` 均：

- `trim()` 后使用 `Locale.ROOT` 转小写。
- 按完整目录名匹配，不做子串匹配。
- 在目录注册与递归扫描之前返回 `SKIP_SUBTREE`。
- 任意层级同名目录均生效。

必须继续内置：

```text
.git, .idea, .vscode, .gradle, .mvn,
node_modules, target, build, dist, out, bin,
__pycache__, venv, .venv
```

建议新增经过确认的工具/依赖输出目录：

```text
coverage, bower_components, obj, vendor, Pods
```

所有以 `.` 开头的目录仍统一排除，因此 `.agent`、`.agents`、`.codegraph`、`.next`、`.turbo`、
`.pnpm-store` 等无需逐项加入内置列表。测试必须显式覆盖这些名称，防止后续移除隐藏目录规则。

### 4.4 `excludeGlobs` 改为监控根目录相对路径匹配

不新增第二套 `excludeFiles` 配置键，避免配置面膨胀。现有 `file.watch.excludeGlobs` 升级为：

1. 先匹配以 `/` 统一分隔的 watch-root-relative path。
2. 为兼容旧配置，再匹配 basename。
3. 目录 glob 命中时直接 `SKIP_SUBTREE`；文件 glob 命中时不入队。
4. 路径不得以监控根目录之外的 `..` 开头。

示例：

```toml
[file.watch]
excludeGlobs = [
  "**/generated/**",
  "packages/*/coverage/**",
  "**/*.generated.*",
  "*.bak"
]
```

无效 glob 必须由共享配置校验器拒绝；不得再采用“记录警告并忽略”的行为。

### 4.5 支持嵌套 `.gitignore`

第一版支持监控根目录及其子目录中的 `.gitignore`：

- 支持注释、目录规则、通配符、锚定规则和 `!` 否定规则。
- 子目录 `.gitignore` 只作用于其目录树，并覆盖上层规则。
- 不读取用户全局 `core.excludesFile`，避免扫描监控根目录之外的配置文件。
- 不读取 `.git/info/exclude`；`.git` 目录本身继续整棵跳过。
- `.gitignore` 是过滤配置文件，允许在内存中读取规则，但不得保存、发送给 LLM 或作为普通文件元数据采集。

实现时优先采用成熟的 Git ignore matcher，例如 JGit 的 ignore 规则实现，不自行编写不完整解析器。
若引入 JGit，必须同步根 POM 依赖管理、`self-analyst-file/pom.xml` 和 `THIRD-PARTY-NOTICES.md`。

新增 `GitIgnoreResolver`：

- 以目录路径、`.gitignore` 大小和 mtime 为缓存键。
- 扫描进入目录时加载并压入该层规则，离开时弹出。
- WatchService 观察到 `.gitignore` CREATE/MODIFY/DELETE 时，先失效对应缓存，再请求该子树重新对账。
- 缓存只保存解析后的规则，不保存 `.gitignore` 原文。

### 4.6 链接与根目录边界

- `Files.walkFileTree` 明确不使用 `FOLLOW_LINKS`。
- 目录扫描与 WatchService 注册前拒绝 `attrs.isSymbolicLink()`、`attrs.isOther()` 和已知 reparse/junction。
- 进入目录前验证规范化路径仍位于对应 watch root 下。
- 新建目录事件走同一检查，不得绕过首次扫描规则。
- 无法证明安全的特殊文件系统节点一律 fail-closed。
- 不对链接目标调用内容读取、哈希或 MIME 探测。

Windows junction 的行为需要独立集成测试；测试环境无法创建 junction 时允许按权限条件跳过，但普通符号链接
和路径逃逸测试不得跳过。

### 4.7 Office 与编辑器临时文件

内置易变文件模式增加：

```text
~$*
*.autosave
*.bak
```

既有 `*.tmp`、`*.temp`、`*.lock`、`*.swp`、`*~` 继续保留。过滤顺序必须在扩展名白名单之前，
确保 `~$计划.xlsx` 不会因为 `xlsx` 合法而进入队列。

## 5. 设计调整

### 5.1 新增不可变过滤配置

在 `self-analyst-file` 增加类似以下模型：

```java
public record FileFilterConfig(
        boolean allowAllExtensions,
        Set<String> allowedExtensions,
        Set<String> excludedDirectoryNames,
        List<String> excludedGlobs,
        long maxFileSizeBytes,
        boolean respectGitIgnore) {}
```

构造前由 `FileFilterConfigParser` 完成规范化与校验。`PathFilter` 不再接收多个未验证的字符串列表。

### 5.2 PathFilter 改为 root-aware

当前 `isExcludedDir(Path)` / `isExcludedFile(Path)` 缺少监控根目录上下文。改为等价的 root-aware API：

```java
FilterDecision evaluateDirectory(Path watchRoot, Path directory,
                                 BasicFileAttributes attrs);
FilterDecision evaluateFile(Path watchRoot, Path file,
                            BasicFileAttributes attrs);
```

`FilterDecision` 至少包含 `included/excluded` 与稳定 reason code，便于日志、测试和桌面诊断；不得包含文件正文。

建议 reason code：

```text
hidden, excluded_directory, excluded_glob, gitignore,
extension_not_allowed, sensitive_name, volatile_name,
oversized, symbolic_link, outside_watch_root, invalid_path
```

### 5.3 扫描与 watcher 共用入口

- `FileIndexWorker.reconcileScan` 捕获当前 root 并调用 root-aware PathFilter。
- `FileWatcher.registerRecursive` 在 `preVisitDirectory` 使用同一目录决策。
- FileWatcher 收到文件事件时先匹配 root，再调用 root-aware 文件决策。
- FileWatcher 收到目录 CREATE 时先执行链接与边界检查，再注册子树。
- `.gitignore` 事件走控制路径，不作为普通文件变化 heartbeat。

不得在扫描器和 watcher 中各维护一份扩展名或目录过滤逻辑。

### 5.4 `.gitignore` 变更对账

为 `FileIndexWorker` 增加有界、去重的子树 reconcile 请求：

```java
void requestReconcile(Path watchRoot, Path subtree);
```

- 同一子树的重复请求合并。
- 请求必须验证 subtree 位于 watchRoot 内。
- 对账使用新的 ignore 规则。
- 新排除的历史记录进入 `DELETED`，重新包含的文件进入 `PENDING`。
- 对账发生访问错误时不得批量淘汰历史记录。

## 6. 配置与界面

### 6.1 配置示例

```toml
[file.watch]
enabled = true
paths = "D:/Documents,D:/Projects"
extensions = [
  "doc", "docx", "docm",
  "xls", "xlsx", "xlsm", "xlsb",
  "ppt", "pptx", "pptm",
  "pps", "ppsx", "ppsm",
  "pot", "potx", "potm",
  "md", "markdown"
]
excludeDirs = ["coverage", "generated"]
excludeGlobs = ["**/*.generated.*", "packages/*/tmp/**"]
maxFileSizeKb = 0
respectGitIgnore = true
```

新增键：

| 键 | 默认值 | 说明 |
|---|---:|---|
| `file.watch.respectGitIgnore` | `true` | 是否读取监控树内 `.gitignore` 规则 |

同步修改：

- `SupportedKeys` 默认值、类型和中英文描述。
- `Config` 解析字段。
- `DesktopConfigController.RESTART_REQUIRED`。
- `application.properties` 注释。
- `docs/specs/config-toml.md` 与 `docs/specs/file.md`；只有实现完成后才更新现行规格。

### 6.2 第一阶段仍要求重启

`extensions/excludeDirs/excludeGlobs/maxFileSizeKb/respectGitIgnore` 第一阶段继续标记为 restart required。
文件设置弹窗仍只热更新 enabled/paths，避免同时引入动态 PathFilter 替换与配置事务复杂度。

后续如需热更新，应整体构造新的 `FileFilterConfig + PathFilter`，停止旧 watcher/worker 后一次性切换，
不得在运行中的过滤器对象上逐项修改集合。

## 7. 数据兼容与历史记录

- 不修改 SQLite schema version。
- 重启后的完整成功扫描会把不再满足新规则的旧记录标记为 `DELETED`。
- FileTools 继续只查询当前 active roots 且状态为 `COLLECTED` 的记录。
- 扫描失败、访问被拒绝或被取消时不得因为“未看到”而删除历史记录。
- 从空白名单旧语义迁移到内置白名单属于有意收窄，不提供自动恢复全部类型；用户可显式配置 `*`。

## 8. 实施步骤

### 阶段 1：先建立失败测试

修改/新增：

- `PathFilterTest`
- `FileIndexWorkerTest`
- `FileWatcherTest`
- `DesktopConfigControllerTest`
- 必要时新增 `GitIgnoreResolverTest`

测试必须先覆盖：

1. 空白白名单拒绝，`*` 显式允许全部。
2. 大小写后缀和带点配置项规范化。
3. Office 宏/二进制扩展名允许。
4. `~$文档.docx`、autosave、bak 排除。
5. 目录名大小写统一、完整名称匹配和隐藏工具目录。
6. root-relative glob、basename 兼容、Windows 分隔符归一化。
7. 无效扩展名和无效 glob 拒绝配置。
8. symlink、junction、outside-root 拒绝。
9. 根与嵌套 `.gitignore`、否定规则和运行时规则变化。
10. 新排除记录退出 FileTools 查询。

### 阶段 2：配置模型与 PathFilter

1. 新增 `FileFilterConfig` 与 parser/validator。
2. 修改 `PathFilter` 为 root-aware。
3. 统一目录名和扩展名规范化。
4. 实现 root-relative glob 与 fail-closed 错误。
5. 增加 Office 临时模式并把默认大小上限改为 0。
6. 运行 `mvn -pl self-analyst-file -am test`。

### 阶段 3：仓库 ignore 与链接边界

1. 选择并引入成熟 ignore matcher。
2. 新增 `GitIgnoreResolver` 和规则缓存。
3. 首次扫描接入嵌套规则栈。
4. WatchService 接入 `.gitignore` 失效与子树对账。
5. 加入链接、junction 和根目录逃逸保护。
6. 运行文件模块测试并进行 Windows 集成验证。

### 阶段 4：应用配置与文档

1. 更新 `Config`、`SupportedKeys`、`DesktopConfigController` 和默认 properties。
2. 更新中英文配置说明与隐私提示。
3. 更新正式 `docs/specs/file.md`，将完成的行为纳入现行契约。
4. 更新依赖声明和第三方许可证（如引入 JGit）。
5. 运行针对性 App/Node 测试。

### 阶段 5：完整验证与审查

```powershell
mvn -pl self-analyst-file -am test
mvn -pl self-analyst-app -am `
  '-Dtest=DesktopConfigControllerTest,DesktopFileControllerTest,AgentPromptsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn test
```

完成后执行独立只读代码审查，重点检查：

- 是否存在白名单为空却放行全部文件的路径。
- 初次扫描与实时事件是否共享同一过滤逻辑。
- `.gitignore` 变更是否能使旧记录退出查询。
- 链接、junction 或大小写差异能否绕过目录过滤。
- 无效配置是否会静默降级为更宽松的采集范围。
- 是否意外引入普通文件正文读取。

## 9. 验收标准

全部满足后才可声明实现完成：

1. 默认只采集内置 Office + Markdown 后缀的文件系统元数据。
2. 空白白名单不采集任何文件；只有显式 `*` 才允许全部后缀。
3. `.git`、`.idea`、`.agent/.agents`、`.codegraph`、`node_modules` 等目录均在遍历前跳过。
4. 目录匹配不受大小写差异影响，且不会误伤仅包含相似子串的普通目录。
5. root-relative glob 在 Windows 与 Unix 分隔符下语义一致。
6. 无效后缀、glob 或路径配置使文件 collector 可诊断降级，不继续放宽采集。
7. 根与嵌套 `.gitignore`、`!` 规则及运行时规则变化均有测试。
8. symlink、junction 和特殊文件系统节点不能逃出 watch root。
9. Office 临时文件不会进入 PENDING/heartbeat/SQLite。
10. 大于 512KB 的合法 Office 文件在默认配置下仍可采集元数据。
11. 被新规则排除的历史记录不再出现在 Desktop API 或 Agent FileTools。
12. 文件模块除读取 `.gitignore` 规则外，不打开普通被监控文件的内容流。
13. 全仓 `mvn test` 通过，独立代码审查无 Critical/Important 问题。
