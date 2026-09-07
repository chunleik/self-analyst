# 测试与集成验证规格

> **文档状态：** 本文保留为跨模块测试命令与手动验证指南；现行行为验收场景已分布在各 OpenSpec 主规格中。

> 状态：现行
>
> 规格前缀：`SPEC-ITEST-*`

## 1. 目标

所有自动化验证必须由所属模块的标准测试生命周期执行。根目录 `mvn test` 是 Java 和桌面 Node
测试的统一入口；不再维护独立的 `self-analyst-integration-test` 聚合模块或位于 `src/main/java`
中的可执行验证器。

### SPEC-ITEST-001：测试归属

- 单个类或纯逻辑组件使用所在模块的 JUnit 测试。
- 真实 HTTP、SQLite、Lucene 等多组件场景仍放在所属模块的 `src/test/java` 中，以
  `*IntegrationTest.java` 命名。
- 桌面前端使用 Node 内置 test runner，并由 `self-analyst-app` 的 Maven `test` 阶段调用。
- Rust accessibility sidecar 使用 Cargo 测试。
- 需要真实桌面、前台窗口或操作系统权限的检查必须显式启用，不能在普通构建中自动探查用户界面。

## 2. 自动化集成场景

### SPEC-ITEST-AW-001：事件服务 HTTP 链路

`EventServerIntegrationTest` 启动绑定临时端口的真实嵌入式服务，并使用临时 SQLite 目录验证：

- bucket 创建与查询；
- HTTP 批量事件写入；
- `start/end` 闭区间过滤；
- heartbeat 在 pulsetime 内合并；
- AQL 查询及汇总元数据。

内容事件隐私策略、Host/Origin 安全边界等独立契约继续由专门测试覆盖。

### SPEC-ITEST-APP-001：桌面 API 链路

`DesktopServerIntegrationTest` 使用临时配置、临时事件服务数据库和随机端口启动真实 Javalin
路由，验证：

- 状态响应及已移除采集器不再出现；
- 配置读取、保存和再次读取；
- 任务创建、查询、更新、完成、归档和删除；
- 未配置 Agent 时聊天端点返回明确错误。

### SPEC-ITEST-CONTENT-001：标题投影

微信对话、富文档文章、未验证 `Document.Name`、聊天消息防误判、敏感应用排除及正文秘密标记等
场景，由 `TitleCaptureTest`、`ContextCapturePolicyTest`、`ContextTitleExtractorTest` 和
`ContentWatcherTest` 在普通 `mvn test` 中执行。

### SPEC-ITEST-WIKI-001：Wiki 存储与摘要

`WikiStoreTest`、`WikiSummarizerTest`、`WikiSemanticIndexTest` 等测试覆盖 SQLite CRUD、唯一周期、
状态迁移、语义文档生命周期、假 LLM 摘要和持久化边界，不再重复打包一套验证程序。

## 3. 手动 UIA 冒烟测试

### SPEC-ITEST-UIA-000：非敏感自动冒烟

`SyntheticUiaSmokeTest` 仅创建一个位于屏幕外、标题随机且不含用户内容的测试窗口，取得该窗口 HWND，
再通过真实 Windows UIAutomation sidecar 连续执行冷、热两次查询。该测试不会读取当前前台窗口，供
Windows CI 和 release workflow 显式启用：

```powershell
.\scripts\build-axsidecar.ps1
mvn -pl self-analyst-content -am '-Dselfanalyst.synthetic.uia=true' `
  '-Dtest=SyntheticUiaSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

### SPEC-ITEST-UIA-001：显式启用

`ManualUiaSmokeTest` 默认由 JUnit 跳过。只有开发者在已知前台窗口可被读取、并确认当前桌面内容适合
测试时，才可以显式执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
mvn -pl self-analyst-content '-Dselfanalyst.manual.uia=true' '-Dtest=ManualUiaSmokeTest' test
```

测试输出只包含应用名、系统标题是否存在、UIA 字符计数和是否识别到上下文标题，不输出 UIA 原文
或标题内容。

## 4. 标准命令

PowerShell 会在第一个点号处拆开未加引号的 `-D` 参数（例如 `-Dsurefire.failIfNoSpecifiedTests=false`
会被拆成 `-Dsurefire` 和 `.failIfNoSpecifiedTests=false`），因此本文档和 CI 中的 Maven 属性一律用
单引号包裹。

```powershell
# 全部 Java、模块集成和桌面 Node 测试
mvn test

# 单独运行事件服务 HTTP 集成测试
mvn -pl self-analyst-events '-Dtest=EventServerIntegrationTest' test

# 运行桌面 API 集成测试及其依赖（同时执行桌面 Node 测试）
mvn -pl self-analyst-app -am '-Dtest=DesktopServerIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

# Rust accessibility sidecar
cargo test --manifest-path self-analyst-axsidecar/Cargo.toml

# 打包后端的真实进程、认证、状态、静态资源和关闭冒烟
.\scripts\check-packaged-jar.ps1

# 对最新 NSIS 产物执行工作区内静默安装、重装、资源检查、后端冒烟和卸载
.\scripts\check-installer.ps1
```

## 5. 资源与隐私

### SPEC-ITEST-010：隔离

- 数据库、配置和索引使用 JUnit `@TempDir`。
- HTTP 服务使用随机或临时端口，并在 `@AfterEach` 中关闭。
- 测试不得访问用户真实数据目录、真实 LLM 服务或提交密钥。
- 自动化测试不得读取真实前台窗口；只有 `ManualUiaSmokeTest` 的显式入口可以这样做。
- Windows CI 的 UIA 验证只允许查询测试进程自己创建的受控窗口，不得取得或遍历用户前台窗口。

### SPEC-ITEST-011：构建一致性

- 根 POM 只聚合生产模块。
- `mvn test` 不得出现承载测试代码但报告 `No tests to run` 的测试专用子模块。
- 修复缺陷时先运行针对性测试，再运行根项目全量测试。
- GitHub Actions 的 Windows CI SHALL 执行 OpenSpec 严格校验、Java/Node/Rust 测试、Rust 格式与
  Clippy、可执行 JAR 打包及真实进程冒烟。
- Windows release workflow SHALL 重建 portable ZIP 和 NSIS 安装包，校验内置 JAR/JRE、生成
  SHA-256，并完成隔离目录中的静默安装与卸载。由 `v*` 标签触发时 SHALL 在校验通过后创建或更新
  同名 GitHub Release，并附上上述分发文件；`workflow_dispatch` 只构建和上传 artifact，不发 Release。
