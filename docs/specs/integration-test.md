# 集成验证规格

> 状态：现行
>
> 规格前缀：`SPEC-ITEST-*`

## 1. 结构

`self-analyst-integration-test` 是独立 Maven 聚合器，包含 AW、Content、Wiki 和 App 验证程序。
每个子模块只依赖对应功能模块，并通过 `maven-assembly-plugin` 生成可直接执行的
`jar-with-dependencies`。

```text
self-analyst-integration-test/
├── self-analyst-aw-test/
├── self-analyst-content-test/
├── self-analyst-wiki-test/
└── self-analyst-app-test/
```

声音集成模块已随声音功能移除，不得保留空壳或条件构建入口。

## 2. 通用约束

### SPEC-ITEST-001：自包含

验证优先使用嵌入式服务器、临时 SQLite 和可替换的外部依赖。不得要求真实 LLM 服务、用户数据库
或桌面交互。所有临时文件使用独立临时目录，服务和数据库在退出前关闭。

### SPEC-ITEST-002：可执行结果

每个验证程序至少执行四个有明确断言的步骤；失败返回非零退出码并指出步骤名，不输出密钥、UIA
正文或数据库原始内容。

## 3. 模块验证

### SPEC-ITEST-AW-001

验证嵌入式服务启动、bucket 创建、heartbeat/events 写入、AQL 查询以及内容事件 v2 策略。正文键
写入内容 bucket 必须返回拒绝，普通 bucket 的通用字段保持兼容。

### SPEC-ITEST-CONTENT-001

`ContentVerification` 只验证 UIA 标题链路：

- 从微信 UIA 输入识别对话人；
- 从已验证的微信 `Document.Name` 识别文章标题，并拒绝未验证应用的通用 Document 名称；
- 过滤正文型、多行、URL 和超长候选；
- `ContentEvent.toHeartbeatData()` 只产生标题白名单字段；
- 输入中的秘密标记不得进入结果或事件。

验证不依赖真实桌面边车，可用构造的 `UiaNode` 或查询替身；不得引用截图、OCR 引擎或混合合并类。

### SPEC-ITEST-WIKI-001

验证小时/日聚合只消费标题事件和既有派生字段，不查询旧正文列；外部 LLM 使用替身。

### SPEC-ITEST-APP-001

用随机端口启动 `DesktopServer`，验证健康、状态、配置和会话端点。构造 `AppSession`/服务器依赖时
只包含现行 watcher，不应存在声音 watcher 参数。

## 4. 构建和执行

```powershell
mvn test
mvn package -f self-analyst-integration-test/pom.xml -DskipTests
java -jar self-analyst-integration-test/self-analyst-content-test/target/*-jar-with-dependencies.jar
```

### SPEC-ITEST-010：Reactor 一致性

根 POM 与集成聚合 POM 的模块列表必须与磁盘目录一致。被移除模块不得出现在
`dependencyManagement`、依赖树、测试启动器或文档命令中。

### SPEC-ITEST-011：原生边界

Java 集成测试不要求真实 UIAutomation 窗口。Rust accessibility sidecar 使用自己的
`cargo test --manifest-path self-analyst-axsidecar/Cargo.toml` 验证协议与平台逻辑。

## 5. 追溯

| 规格 | 验证 |
|------|------|
| `SPEC-ITEST-AW-001` | AW 可执行验证与内容事件策略单元测试 |
| `SPEC-ITEST-CONTENT-001` | `ContentVerification`、`TitleCaptureTest`、`ContextCapturePolicyTest` |
| `SPEC-ITEST-WIKI-001` | Wiki 可执行验证与聚合测试 |
| `SPEC-ITEST-APP-001` | App 可执行验证、桌面控制器测试 |
| `SPEC-ITEST-010` | Maven reactor 构建 |
| `SPEC-ITEST-011` | Rust sidecar 测试 |
