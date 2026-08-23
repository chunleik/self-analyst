# SelfAnalyst 集成测试模块组 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义集成测试模块组的精确行为契约，所有实现必须可追溯至本文档的某一项规格。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 新建 `self-analyst-integration-test` 测试模块组 |
| 文档状态 | Implemented |
| 日期 | 2026-06-11 |
| 目标平台 | JVM 21，Maven 3.x |
| 参考模式 | Redisson 的 `redisson-spring` → `redisson-spring-data` 三层 POM 分组结构 |
| 根 POM | `pom.xml`（将新增一个 `<module>`） |
| 涉及模块 | 全部 5 个功能模块（aw / content / audio / wiki / app） |

---

## 2. 背景和当前状态

当前项目有 5 个功能模块，测试分散在各模块内部：

| 模块 | 现有测试 |
|------|---------|
| `self-analyst-aw` | AQL 引擎单元测试 |
| `self-analyst-app` | MemoryStore 单元测试 |
| `self-analyst-content` | 内容采集测试 |
| `self-analyst-audio` | 无独立测试 |
| `self-analyst-wiki` | 无独立测试 |

当前问题：
- 没有一个统一的入口运行全部集成/冒烟测试。
- 没有验证模块间依赖关系（class loading、传递依赖完整性）的自动化手段。
- 新增模块时没有固定的测试子模块模板。

---

## 3. 设计结论

参考 Redisson 的三层 parent 分组模式，新建一个聚合 POM 模块组，每个子模块专责测试一个现有功能模块：

```
self-analyst/                              (根 pom, 新增 <module>)
└── self-analyst-integration-test/         (pom, parent=root)
    ├── self-analyst-aw-test/              (jar, parent=integration-test)
    ├── self-analyst-content-test/         (jar, parent=integration-test)
    ├── self-analyst-audio-test/           (jar, parent=integration-test)
    ├── self-analyst-wiki-test/            (jar, parent=integration-test)
    └── self-analyst-app-test/             (jar, parent=integration-test)
```

三层 parent 链：
- 根 POM（管理 versions + pluginManagement）
- `self-analyst-integration-test`（pom，管理公共 test 依赖）
- 各 `*-test` 子模块（jar，只声明对应功能模块依赖）

---

## 4. 目标

- 每个 test 子模块对其对应的功能模块执行**真实场景集成测试**：启动模块核心组件、走完整调用链路、验证真实输出。
- 测试优先使用模块的自包含能力（嵌入式服务器、SQLite 存储、可 mock 的外部依赖接口），避免依赖外部服务（LLM API、Whisper 二进制、麦克风硬件）。
- 模块无法完全自包含时（content/audio 的原生层），测试其 pure-Java 逻辑组件（pipeline 编排、数据模型、工具方法），以 mock 替代原生层。
- 根 POM 的 `<modules>` 中正确引用新聚合模块。
- 每个子模块打包为可执行 fat jar（`maven-assembly-plugin` + `jar-with-dependencies`），通过 `java -jar` 直接运行。
- 不修改任何现有功能模块的源代码。

---

## 5. 非目标

- 不使用 JUnit 或其他测试框架——验证逻辑通过 `main()` 入口 + `System.exit()` 退出码表达。
- 不新增 Maven 依赖。maven-assembly-plugin 用于打包 fat jar。
- 不修改 `dependencyManagement` 中的版本号。
- 不在 CI/CD 中配置（后续单独处理）。
- 不要求测试覆盖率阈值。
- 内容与音频模块不测试需要原生库（JNA/Tesseract/Whisper）或硬件的路径。

---

## 6. 模块架构规格

### 6.1 聚合 POM：`self-analyst-integration-test/pom.xml`

#### SPEC-ITEST-001: 聚合 POM 基本属性

- `groupId`: `com.selfanalyst`
- `artifactId`: `self-analyst-integration-test`
- `version`: `1.0.0`（与根 POM 一致）
- `packaging`: `pom`
- `name`: `SelfAnalyst :: Integration Tests`
- `parent`: 根 POM `com.selfanalyst:self-analyst:1.0.0`

#### SPEC-ITEST-002: 聚合 POM 模块声明

`<modules>` 必须包含（按字母序）：

```xml
<module>self-analyst-app-test</module>
<module>self-analyst-audio-test</module>
<module>self-analyst-aw-test</module>
<module>self-analyst-content-test</module>
<module>self-analyst-wiki-test</module>
```

#### SPEC-ITEST-003: 聚合 POM 公共依赖

必须在 `<dependencies>` 中声明以下公共测试依赖（子模块自动继承）：

| 依赖 | scope | 说明 |
|------|-------|------|
| `org.junit.jupiter:junit-jupiter` | test | 版本由根 POM `dependencyManagement` 管理 |
| `org.slf4j:slf4j-simple` | test | 版本号需显式指定为 `${slf4j.version}`，与根 POM 保持一致 |

`junit-jupiter` 版本由根 POM `<dependencyManagement>` 统一控制，不在此处覆写。
`slf4j-simple` 需在聚合 POM 中显式声明 `<version>`（根 POM `dependencyManagement` 中只有 `slf4j-api`，不含 `slf4j-simple`）。

#### SPEC-ITEST-004: 聚合 POM 构建配置

不得在聚合 POM 中声明 `<build><plugins>`，所有插件配置继承根 POM 的 `pluginManagement`。

### 6.2 子模块 POM（5 个，模式相同）

#### SPEC-ITEST-005: 子模块 POM 模板

以 `self-analyst-aw-test/pom.xml` 为例，其余 4 个按相同模式替换：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.selfanalyst</groupId>
        <artifactId>self-analyst-integration-test</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>self-analyst-aw-test</artifactId>
    <packaging>jar</packaging>

    <name>SelfAnalyst :: Integration Test :: AW</name>
    <description>Integration tests for self-analyst-aw</description>

    <dependencies>
        <dependency>
            <groupId>com.selfanalyst</groupId>
            <artifactId>self-analyst-aw</artifactId>
        </dependency>
    </dependencies>
</project>
```

约束：
- `<parent>` 指向 `self-analyst-integration-test`（不是根 POM）。
- `<packaging>` 固定为 `jar`。
- `<dependencies>` 只声明**一个**对应的功能模块依赖，版本由根 POM `dependencyManagement` 管理。
- 不重复声明 `junit-jupiter` 或 `slf4j-simple`（从聚合 POM 继承）。
- `<name>` 格式：`SelfAnalyst :: Integration Test :: <MODULE>`，其中 `<MODULE>` 为大写模块缩写（AW / Content / Audio / Wiki / App）。

#### SPEC-ITEST-006: 5 个子模块对照表

| 子模块 artifactId | 对应功能模块依赖 | `<description>` |
|---|---|---|
| `self-analyst-aw-test` | `self-analyst-aw` | Integration tests for self-analyst-aw |
| `self-analyst-content-test` | `self-analyst-content` | Integration tests for self-analyst-content |
| `self-analyst-audio-test` | `self-analyst-audio` | Integration tests for self-analyst-audio |
| `self-analyst-wiki-test` | `self-analyst-wiki` | Integration tests for self-analyst-wiki |
| `self-analyst-app-test` | `self-analyst-app` | Integration tests for self-analyst-app |

---

## 7. 验证程序规格

### 7.1 通用约束

#### SPEC-ITEST-007: 验证程序路径和命名

每个子模块在 `src/main/java` 下包含一个 `main()` 入口验证程序：

```
src/main/java/com/selfanalyst/integration/<module>/<Module>Verification.java
```

| 子模块 | 主类全限定名 |
|--------|------------|
| `self-analyst-aw-test` | `com.selfanalyst.integration.aw.AwVerification` |
| `self-analyst-content-test` | `com.selfanalyst.integration.content.ContentVerification` |
| `self-analyst-audio-test` | `com.selfanalyst.integration.audio.AudioVerification` |
| `self-analyst-wiki-test` | `com.selfanalyst.integration.wiki.WikiVerification` |
| `self-analyst-app-test` | `com.selfanalyst.integration.app.AppVerification` |

#### SPEC-ITEST-008: 验证程序通用约束

- 不依赖 JUnit 或任何测试框架。验证逻辑通过 `main()` 入口 + `System.exit(0/1)` 表达。
- 自行管理临时目录（`Files.createTempDirectory`），自行清理。
- 涉及网络端口的验证（aw、app）获取随机端口：`try (ServerSocket ss = new ServerSocket(0)) { port = ss.getLocalPort(); }`。
- 不得依赖外部服务（LLM API、ActivityWatch 外部进程、网络上的其他主机）。
- 每个步骤实时打印 `[PASS]` 或 `[FAIL]` + 描述。全部通过→ exit 0，任何失败→ exit 1。
- 各子模块 POM 使用 `maven-assembly-plugin` + `jar-with-dependencies` 打包为 fat jar，`maven-jar-plugin` 声明 `mainClass`。
- 运行方式：`java -jar target/<artifactId>-<version>-jar-with-dependencies.jar`

### 7.2 AW 模块集成测试

#### SPEC-ITEST-AW-001: 全链路 HTTP 集成测试

测试类 `AwIntegrationTest` 必须启动嵌入式 `AwServer` 并通过 HTTP 客户端验证完整链路。

测试方法（至少 5 个）：

| 方法 | 规格 |
|------|------|
| `createBucket` | POST `/api/0/buckets/{id}` 创建 bucket，GET `/api/0/buckets/` 列表中包含该 bucket |
| `insertEvents` | POST `/api/0/buckets/{id}/events` 插入单个 event 和 event 数组，GET `/api/0/buckets/{id}/events` 验证数量 |
| `heartbeatMerge` | POST `/api/0/buckets/{id}/heartbeat` 发送相同 data 的 heartbeat（pulsetime 内），验证合并为一条 duration 累加 |
| `queryEvents` | GET `/api/0/buckets/{id}/events?limit=N&start=...&end=...` 按时间范围过滤 |
| `aqlQuery` | POST `/api/0/query/` 执行 AQL（query_bucket + sort_by_duration），验证返回 rows |

约束：
- `AwServer` 构造参数：`new AwServer(tempDir, port)`，然后调用 `server.start()`。
- HTTP 客户端使用 `java.net.http.HttpClient`（JDK 11+）。
- 每个测试方法独立创建 server（或 `@BeforeEach`/`@AfterEach` 管理生命周期）。

### 7.3 Wiki 模块集成测试

#### SPEC-ITEST-WIKI-001: WikiStore 真实 CRUD 测试

测试类 `WikiIntegrationTest` 使用 `@TempDir` SQLite 数据库验证 WikiStore 完整操作。

测试方法（至少 5 个）：

| 方法 | 规格 |
|------|------|
| `crudRoundTrip` | upsert WikiEntry → query 验证字段完整 → upsert 更新 → 验证覆盖 |
| `uniqueConstraint` | 同一 level+periodStart+periodEnd+timezone 的组合 upsert 两次，验证只有一条记录 |
| `statusTransitions` | PENDING → updateStatus(SUMMARIZED) → markFailed → markSkipped → 验证状态流转 |
| `semanticDocFlow` | upsertSemanticDoc → findPendingSemanticDocs → markSemanticDocIndexed → markSemanticDocsStale → 验证完整生命周期 |
| `summarizeRoundTrip` | 用 `WikiSummarizer(prompt -> preCannedJson)` 生成 SummaryResult → updateStatus 持久化 → query 验证 summary/primaryTask/taskSegments 字段完整 |

约束：
- `WikiStore` 构造参数：`new WikiStore(tempDir.resolve("test.db"))`。
- `preCannedJson` 必须至少包含 summary、primaryTask、taskSegments 字段的合法 JSON。
- 不需要真实的 LLM API 或 Embedding API。
- `WikiSummarizer` 接受 `Function<String, String>`，可注入返回固定 JSON 的 lambda。

### 7.4 App 模块集成测试

#### SPEC-ITEST-APP-001: DesktopServer HTTP 端点集成测试

测试类 `AppIntegrationTest` 必须启动 Javalin + DesktopServer（最小依赖图）并验证 HTTP 端点。

最小依赖图：
- `Config` 直接构造（apiKey 为空字符串）
- `EventStore` 用 temp dir SQLite（`new Database(tempDir)` + `new EventStore(db, PulseTimeConfig.DEFAULT)`）
- `agent` / `watcherManager` / `contentWatcher` / `audioWatcher` 全部传 `null`
- Javalin `app.start(0)` 获取随机端口

测试方法（至少 4 个）：

| 方法 | 规格 |
|------|------|
| `statusEndpoint` | GET `/desktop/status` → 返回 JSON 含 backend/aw/collectors/llm 四段，collectors 全部显示 disabled |
| `configRoundTrip` | GET `/desktop/config` → 验证默认值和 metadata（effectiveValue/savedValue/source），PUT `/desktop/config` 覆盖 model → GET 验证生效 |
| `taskCrud` | POST → GET list → PUT update → POST complete → POST archive → DELETE → GET 验证删除，覆盖完整生命周期 |
| `chatWithoutAgent` | POST `/desktop/chat` → agent=null 时返回包含 "LLM 未配置" 的 message，suggestedTasks 为空数组 |

约束：
- `DesktopServer` 构造参数完整传入 8 个参数（nullable 位置传 null）。
- `@TempDir` 同时用于 memoryDir（Config 构造）和 SQLite dataDir。
- HTTP 客户端使用 `java.net.http.HttpClient`。

### 7.5 Content 模块集成测试

#### SPEC-ITEST-CONTENT-001: ContentCapture pipeline + UiaTreeWalker 工具测试

测试类 `ContentIntegrationTest` 不依赖原生层（JNA/Tesseract），通过 mock `ScreenCapturer` 和 `OcrEngine` 验证 pipeline 编排和工具方法。

测试方法（至少 4 个）：

| 方法 | 规格 |
|------|------|
| `uiaOnlyCapture` | 构造非 thin 的 UiaNode 树（内容密度 ≥30%），验证 ContentCapture 走 UIA-only 路径、不调用 OCR |
| `thinOcrCapture` | 构造 thin UiaNode 树（<30% density 且不含 canvas），mock OcrEngine 返回已知文本，验证走 hybrid 路径且 merge 结果包含 OCR 文本 |
| `extractTextRules` | 调用 `UiaTreeWalker.extractText(node)` 验证：role=scrollbar/thumb/separator 的节点文本被跳过、password 控件文本替换为 `***`、combo/edit 控件优先取 value 而非 name |
| `contentEventHeartbeat` | 构造 `ContentEvent`（部分字段为 null），调用 `toHeartbeatData()`，验证 null 被替换为空字符串、source 默认 "uia"、map key 集合正确 |

约束：
- `ContentCapture` 构造参数中的 `ScreenCapturer` 可传一个返回 `new BufferedImage(1,1,TYPE_INT_RGB)` 的 lambda。
- `OcrEngine` 可传 `img -> "mock ocr text"` 的 lambda。
- `ThinDetector` 和 `HybridMerger` 用真实实例（pure Java，无原生依赖）。
- UiaNode 树通过 `UiaNode.create()` + `addChild()` 手动构造。

### 7.6 Audio 模块集成测试

#### SPEC-ITEST-AUDIO-001: AudioCapturer 静态工具方法测试

测试类 `AudioIntegrationTest` 不需要麦克风或 Whisper 二进制，仅测试 pure-Java 的静态工具方法。

测试方法（至少 4 个）：

| 方法 | 规格 |
|------|------|
| `rmsComputation` | 构造已知 16-bit 单声道 PCM 字节数组（正弦波或方波），验证 `AudioCapturer.rms()` 返回值在预期容差内 |
| `wavHeaderCorrectness` | 调用 `AudioCapturer.toWav(pcm)` 验证 WAV 容器：offset 0-3 = "RIFF"，offset 4-7 = 文件大小-8（little-endian），offset 8-11 = "WAVE"，offset 12-15 = "fmt "，采样率=16000，bit depth=16，channels=1，data chunk 长度 = PCM 长度 |
| `littleEndianEncoding` | `AudioCapturer.shortToBytes()` 和 `intToBytes()` 的边界值测试（0、正数、负数、max/min） |
| `rmsSilenceDetection` | 全零数组 rms≈0.0，满幅 32767 方波 rms≈1.0（16-bit PCM 归一化），验证 VAD 可用性 |

约束：
- 不需要 `AudioSystem`、`TargetDataLine` 或任何音频硬件。
- 不需要 `whisper-cli.exe` 或 GGML 模型文件。
- 全部测试方法为 `static` 调用，不需要实例化 `AudioCapturer`。

---

## 8. 根 POM 修改规格

### SPEC-ITEST-011: 根 POM module 声明

在根 `pom.xml` 的 `<modules>` 中新增一行：

```xml
<module>self-analyst-integration-test</module>
```

插入位置：现有 5 个 module 声明之后（或之前，位置不影响构建）。

修改后 `<modules>` 应为 6 项：

```xml
<modules>
    <module>self-analyst-aw</module>
    <module>self-analyst-app</module>
    <module>self-analyst-content</module>
    <module>self-analyst-audio</module>
    <module>self-analyst-wiki</module>
    <module>self-analyst-integration-test</module>
</modules>
```

---

## 9. 构建和验证规格

### SPEC-ITEST-012: 构建验证

```bash
mvn package -f self-analyst-integration-test/pom.xml -DskipTests
```

验收：
- Exit code 为 0。
- 每个子模块 `target/` 下生成 `*-jar-with-dependencies.jar`。

### SPEC-ITEST-013: 验证执行

```bash
java -jar self-analyst-integration-test/<module>/target/<module>-1.0.0-jar-with-dependencies.jar
```

验收：
- 每个模块所有步骤输出 `[PASS]`。
- 最后一行输出 `=== X Verification: N passed, 0 failed ===`。
- Exit code 为 0。

### SPEC-ITEST-014: 回归验证

```bash
mvn test
```

验收：
- 原有功能模块测试不受影响，全部通过。
- BUILD SUCCESS。

---

## 10. 边界条件

### 10.1 端口冲突

AW 和 App 模块的集成测试使用 `new ServerSocket(0)` 获取随机空闲端口，不硬编码端口号。如果操作系统端口耗尽（极端情况），测试将在 `@BeforeEach` 阶段失败，不执行后续 HTTP 调用。

### 10.2 临时目录清理

所有测试使用 JUnit `@TempDir`，测试完成后 JUnit 自动删除。如果删除失败（Windows 文件锁定），`@AfterEach` 中先调用 `server.stop()` 释放 Javalin 端口、`store.close()` 关闭 SQLite 连接。

### 10.3 原生库不可用（Content/Audio 模块）

Content 和 Audio 测试不依赖 JNA/Tesseract/Whisper/麦克风。如果对应功能模块的核心类因原生库缺失导致 `ClassNotFoundException` 或 `UnsatisfiedLinkError`，集成测试仍应通过（因为测试只触及 pure-Java 路径）。

### 10.4 SQLite 驱动不可用

Wiki 和 App 模块依赖 `sqlite-jdbc`。如果驱动不在 classpath 上，`WikiStore` 和 `Database` 构造将抛异常。这是预期行为 —— 传递依赖由 Maven 保证，若缺失说明 POM 配置错误。

### 10.5 新增第 6 个功能模块

应在 `self-analyst-integration-test/` 下参照现有模式新建对应的 `*-test` 子模块，并在聚合 POM 的 `<modules>` 中注册。选择该模块可自包含测试的组件设计集成测试。

---

## 11. 实现注意事项

- 所有 POM 文件使用 UTF-8 编码，缩进使用 4 空格。
- 测试类包名遵循 `com.selfanalyst.integration.<module>` 约定，类名为 `<Module>IntegrationTest`。
- 不创建 `src/main/java` 目录（测试模块只有 test 源集）。
- 不提交 IDE 配置文件（`.idea/`, `*.iml`），确认 `.gitignore` 已覆盖。
- 根 POM 修改仅限于 `<modules>` 块，不影响其他配置。

---

## 12. 规格追溯矩阵

| 规格 ID | 对应文件/内容 | 验收方式 |
|---------|-------------|----------|
| SPEC-ITEST-001 | `self-analyst-integration-test/pom.xml` groupId/artifactId/packaging/name/parent | Maven 解析验证 |
| SPEC-ITEST-002 | 聚合 POM `<modules>` 5 个子模块声明 | `mvn validate` |
| SPEC-ITEST-003 | 聚合 POM `<dependencies>` junit-jupiter + slf4j-simple | 子模块编译通过 |
| SPEC-ITEST-004 | 聚合 POM 无 `<build><plugins>` | 文件检查 |
| SPEC-ITEST-005 | 5 个子模块 POM 模板 | 文件检查 |
| SPEC-ITEST-006 | 子模块 artifactId 与依赖对照 | 文件检查 |
| SPEC-ITEST-007 | 验证程序路径（Verification.java 替换 IntegrationTest） | 文件检查 |
| SPEC-ITEST-008 | 验证程序通用约束（main()入口, 自行管理临时目录, [PASS]/[FAIL] 输出, System.exit 退出码） | 代码审查 |
| SPEC-ITEST-009 | 根 POM `<modules>` 新增 + `dependencyManagement` 补充 app | `mvn validate` |
| SPEC-ITEST-AW-001 | `AwVerification` 全链路 HTTP（≥5 steps） | java -jar 运行 |
| SPEC-ITEST-WIKI-001 | `WikiVerification` WikiStore CRUD + Summarizer（≥5 steps） | java -jar 运行 |
| SPEC-ITEST-APP-001 | `AppVerification` DesktopServer HTTP 端点（≥4 steps） | java -jar 运行 |
| SPEC-ITEST-CONTENT-001 | `ContentVerification` pipeline + 工具方法（≥4 steps） | java -jar 运行 |
| SPEC-ITEST-AUDIO-001 | `AudioVerification` 静态工具方法（≥4 steps） | java -jar 运行 |
| SPEC-ITEST-012 | 构建验证（fat jar 生成） | `mvn package -f self-analyst-integration-test/pom.xml -DskipTests` |
| SPEC-ITEST-013 | 验证执行 | `java -jar ...-jar-with-dependencies.jar` |
| SPEC-ITEST-014 | 回归验证（原功能模块测试不受影响） | `mvn test` |
