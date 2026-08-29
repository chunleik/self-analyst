# SelfAnalyst SDD 规格说明书

> Specification-Driven Development — 本文档定义系统的精确行为契约，所有实现必须可追溯至本文档的某一项规格。

---

## 1. 系统标识

| 属性 | 值 |
|------|-----|
| 产品名称 | SelfAnalyst |
| 版本 | 1.0.0 |
| 语言 | Java 21 |
| 构建系统 | Maven 3.x |
| 主类 | `com.selfanalyst.App` |

---

## 2. 系统架构契约

### 2.1 模块边界

```
App (入口) → AppSession (生命周期管理)
                │
                ├── Config (配置加载)
                ├── UserConfigStore (用户配置读写)
                ├── AwServer (嵌入式 ActivityWatch 服务)
                │     ├── WatcherManager (窗口/AFK 采集)
                │     ├── ContentWatcher (内容采集)
                │     └── AudioWatcher (音频采集)
                ├── DesktopServer (桌面 API 路由)
                └── SelfAnalystAgent (核心智能体)
                      ├── MemoryStore (持久化)
                      │     └── GrowthProfile (数据模型)
                      ├── ActivityWatchTools (AW 数据查询工具)
                      │     └── HTTP → ActivityWatch API
                      ├── ConfigTools (配置读写工具)
                      │     └── UserConfigStore → config.properties
                      ├── DynamicMemoryContextMiddleware (动态记忆上下文)
                      └── PlanMiddleware (生命周期与预算中间件)
```

### 2.2 模块依赖规则

- **SPEC-ARCH-001**: 所有模块间的依赖必须通过构造器注入，禁止使用静态单例或服务定位器。
- **SPEC-ARCH-002**: `Config` 是不可变 record，一旦构造即不可修改。
- **SPEC-ARCH-003**: `MemoryStore` 的 `save()` 和 `load()` 是唯一访问文件系统的入口，其他模块不得直接读写 `memory.json`。
- **SPEC-ARCH-004**: `ActivityWatchTools` 是唯一发起 HTTP 请求的模块，所有 AW API 调用必须通过此模块。

---

## 3. Config 规格

### SPEC-CFG-001: 配置加载优先级

配置项按以下优先级解析（高到低）：

1. 环境变量（如 `OPENAI_API_KEY`）
2. `application.properties` 文件中的属性
3. 硬编码默认值

**验证方式**: 设置环境变量后加载 Config，确认环境变量值覆盖 properties 文件值。

### SPEC-CFG-002: 配置项清单

| 属性键 | 环境变量 | 默认值 | 类型 |
|--------|---------|--------|------|
| `llm.api-key` | `OPENAI_API_KEY` | `""` | String |
| `llm.base-url` | `LLM_BASE_URL` | `https://api.openai.com/v1` | String (URL) |
| `llm.model` | `LLM_MODEL` | `gpt-4o` | String |
| `aw.base-url` | `AW_BASE_URL` | `http://localhost:5600/api/0` | String (URL)；仅外部 AW 模式使用 |
| `aw.timeout` | `AW_TIMEOUT` | `15000` | int (毫秒) |
| `aw.port` | 无 | `5700` | int；仅由 `config.toml` 覆盖 |
| `memory.dir` | `MEMORY_DIR` | `${user.home}/.self-analyst` | Path |

`aw.port` 是端口配置的唯一用户入口。内嵌模式的 `aw.base-url` 必须由该端口派生；桌面壳不得使用环境变量或内置端口覆盖它。Java 后端完成配置加载和监听后，必须通过桌面启动握手把实际端口通知桌面壳。

### SPEC-CFG-003: `${user.home}` 占位符替换

`memory.dir` 属性值中的 `${user.home}` 必须在加载时替换为 `System.getProperty("user.home")` 的实际值。

### SPEC-CFG-004: API Key 校验

`Config.validate()` 必须在以下情况抛出 `IllegalStateException`：
- `llmApiKey` 为 null
- `llmApiKey` 为空白字符串

---

## 4. 数据模型规格 (GrowthProfile)

### 4.1 Goal

### SPEC-MDL-001: Goal 结构

```java
record Goal(
    String id,          // UUID 前8位，唯一标识    — 不可变
    String description,  // 目标描述                  — 不可变
    String metric,       // 衡量指标                  — 不可变
    double baseline,     // 基线值                    — 不可变
    double target,       // 目标值                    — 不可变
    LocalDate setAt,     // 设立日期                  — 不可变
    boolean active       // 是否活跃                  — 可变
)
```

- **SPEC-MDL-001a**: `Goal.create(description, metric, baseline, target)` 工厂方法必须自动生成 8 字符 ID（UUID 前 8 位），设置 `setAt` 为当前日期，`active` 为 `true`。
- **SPEC-MDL-001b**: `id` 不允许重复（由 UUID 保证概率上不重复）。

### 4.2 KnownPattern

### SPEC-MDL-002: KnownPattern 结构

```java
record KnownPattern(
    String description,   // 模式描述
    String evidence,      // 支撑数据简述
    LocalDate confirmedAt,// 确认日期
    int confidence        // 置信度 1-10
)
```

- **SPEC-MDL-002a**: `confidence` 必须在闭区间 [1, 10] 内。当前实现不强制校验，但调用方应保证此约束。

### 4.3 ImprovementLog

### SPEC-MDL-003: ImprovementLog 结构

```java
record ImprovementLog(
    String goalId,      // 关联的 Goal.id
    String action,       // 采取的行动
    String outcome,      // 行动结果
    LocalDate observedAt // 观察日期
)
```

- **SPEC-MDL-003a**: `goalId` 可为任意字符串，不强制关联到已存在的 Goal。

### 4.4 Context Summary

### SPEC-MDL-004: buildContextSummary() 输出契约

- 当无任何数据时，必须返回包含 "暂无已存储" 的字符串。
- 当有活跃目标时，必须输出 `## 活跃目标` 段落，每行一个目标，格式为 `- {description}（基线: {baseline}，目标: {target}，设立于 {setAt}）`。
- 当有已知模式时，必须输出 `## 已确认的行为模式` 段落，每行一个模式，格式为 `- {description}（置信度: {confidence}/10）`。
- 当有改进记录时，必须输出 `## 最近的改进记录` 段落，只展示最近 5 条，按 `observedAt` 降序排列。

### 4.5 JSON 序列化

### SPEC-MDL-005: 序列化格式契约

- 所有 `LocalDate` 字段必须序列化为 ISO-8601 日期字符串（`2026-06-03`），禁止序列化为时间戳数组。
- JSON 输出必须使用缩进格式化（pretty print）。
- 反序列化时必须忽略未知字段（`@JsonIgnoreProperties(ignoreUnknown = true)`）。

---

## 5. MemoryStore 规格

### SPEC-MEM-001: 加载行为

- 如果 `memory.json` 文件不存在 → 创建空的 `GrowthProfile` 实例。
- 如果 `memory.json` 文件存在但 JSON 格式损坏 → 抛出 `IOException`。
- 加载前必须先创建父目录（`Files.createDirectories`）。
- 文件名固定为 `memory.json`，不可配置。

### SPEC-MEM-002: 保存行为

- `save()` 必须将当前 `GrowthProfile` 完整序列化到文件。
- 必须是原子写入或全量覆盖，不允许增量追加。

### SPEC-MEM-003: profile() 访问

- 返回的是内部可变引用，调用方通过此引用修改数据后需调用 `save()` 持久化。
- 此方法是幂等的，多次调用返回同一引用。

---

## 6. ActivityWatchTools 规格

### SPEC-AW-001: API 路径

- 构造器接收的 `baseUrl` 如果结尾有 `/` 则去掉，然后拼接时加回 `/`。
- 所有请求路径拼接在 `baseUrl` 之后，不再加 `0/` 前缀（即 `baseUrl` 已经是 `/api/0/`）。

### SPEC-AW-002: 工具方法清单

| 方法 | HTTP 方法 | 路径 | 描述 |
|------|----------|------|------|
| `listBuckets()` | GET | `buckets/` | 列出所有 bucket |
| `queryEvents(...)` | GET | `buckets/{bucketId}/events?limit=...` | 查询事件 |
| `executeAQL(...)` | POST | `query/` | 执行 AQL 查询 |
| `getServerInfo()` | GET | `info` | 服务器信息 |
| `getSettings()` | GET | `settings` | 服务器设置 |

### SPEC-AW-003: queryEvents 参数契约

- `limit` 必须出现在 query string 中（`?limit=N`）。
- `startTime` 非空时追加 `&start={value}`。
- `endTime` 非空时追加 `&end={value}`。
- 参数不做 ISO-8601 格式校验，直接透传到 API。

### SPEC-AW-004: executeAQL 请求体格式

```json
{
  "timeperiods": ["{timeperiod}"],
  "query": ["{line1};", "{line2};"]
}
```

- 输入的 `aqlQuery` 按 `;` 分割，去除空白后非空的行添加到 query 数组。
- query 数组中每行结尾追加 `;`。
- 如果某行包含双引号，内部转义为 `\"`。
- 请求 Content-Type 为 `application/json`。

### SPEC-AW-005: 错误处理

- 所有 HTTP 异常（`IOException`、`InterruptedException`）必须捕获，返回 JSON 格式错误字符串 `{"error": "{message}"}`。
- 不得向上层抛出异常。

### SPEC-AW-006: 超时

- 连接超时使用构造器传入的 `timeoutMillis`。
- 请求超时同样使用此值（通过 `HttpRequest.timeout()`）。

### SPEC-AW-007: 工具注解

- 所有公开的方法（除构造器和私有方法）必须标注 `@Tool` 注解。
- 所有参数必须标注 `@ToolParam` 注解（无参方法除外）。

---

## 6b. ConfigTools 规格

### SPEC-CFG-TOOL-001: 工具方法清单

| 方法 | 参数 | 描述 |
|------|------|------|
| `getConfig()` | 无 | 读取所有配置项的有效值，API key 字段脱敏（前4后4位，中间 `****`） |
| `setConfigValue(key, value)` | key: 点分配置键；value: 新值（空字符串=删除用户覆盖） | 写入单个配置项到 `config.properties` |

### SPEC-CFG-TOOL-002: 白名单键集合

`setConfigValue` 只接受以下键（共 26 个），其他键返回错误信息：

```
llm.api-key, llm.base-url, llm.model, llm.temperature
websearch.enabled, websearch.mcp-url, websearch.api-key
agent.summaryRefreshMinutes, agent.allowAgentTasks, agent.cacheSummaries
desktop.hideToTray, desktop.autoOpenWindow, desktop.autoStartBackend
aw.mode, aw.port
aw.collection.window, aw.collection.afk, aw.collection.content
aw.ocr.engine, aw.audio.enabled, aw.audio.whisperPath
embedding.enabled, embedding.base-url, embedding.api-key
embedding.model, embedding.dimensions, embedding.send-encoding-format
```

### SPEC-CFG-TOOL-003: 重启提示键集合

以下键修改后，工具返回信息中须注明"需重启 SelfAnalyst 后才能生效"：

```
llm.model, llm.temperature, aw.mode, aw.port,
aw.collection.window, aw.collection.afk, aw.collection.content,
agent.summaryRefreshMinutes, desktop.autoStartBackend,
websearch.enabled, websearch.mcp-url, websearch.api-key
```

> 说明：`llm.temperature` 在 Agent 构建时注入对话模型；`aw.collection.*` 在嵌入式 AW 启动时决定是否启用对应采集器（window/afk/content）。二者均在启动阶段读取，故修改后需重启生效。

### SPEC-CFG-TOOL-004: 写入行为

- 通过 `UserConfigStore.set(key, value)` 持久化，底层使用原子写入（temp 文件 + rename）。
- `DesktopConfigController` 和 `ConfigTools` 各自持有独立的 `UserConfigStore` 实例，共享同一 `config.properties` 文件。原子写入保证无数据损坏，最后写入者的值生效。

---

## 7. SelfAnalystAgent 规格

### SPEC-AGT-001: 智能体配置

| 属性 | 值 |
|------|-----|
| 名称 | `SelfAnalyst` |
| 模型类型 | `OpenAIChatModel` (OpenAI 兼容 API) |
| Temperature | 0.7 |
| Stream | true |
| Max Iterations | 15 |

### SPEC-AGT-002: System Prompt 契约

System Prompt 必须包含以下四部分（按顺序）：

1. **身份声明**: "你是 SelfAnalyst，一个基于数据的自我提升伙伴。"
2. **三层工作模式**: 感知(Perceive) → 认知(Understand) → 改进(Improve)
3. **工作流程**: 回顾→判断→计划→工具调用→洞察→记录
4. **关键原则**: 给判断不给数据、具体可执行、追踪闭环、目标锚点
5. **当前日期**: `当前日期：{yyyy-MM-dd}`（动态生成）
6. **用户记忆**: `buildContextSummary()` 的输出

- **SPEC-AGT-002a**: 每次 `chat()` 调用时，System Prompt 中的日期必须是当天的实际日期，不可缓存。

### SPEC-AGT-003: chat() 契约

- 输入: 用户消息字符串（非空）
- 输出: `Mono<String>`，包含 Agent 的完整文本响应
- 消息构造: 通过 `Msg.builder().textContent(userInput).build()`
- 工具集: 注册了以下 `@Tool` 方法：
  - `ActivityWatchTools` — AW 数据查询（listBuckets、queryEvents、executeAQL 等）
  - `ConfigTools` — 配置读写（getConfig、setConfigValue），仅当 `UserConfigStore` 可用时注册
  - `WikiTools` — Wiki 摘要查询（可选，依赖 WikiStore）
  - Web Search MCP — 联网搜索（可选，依赖 websearch.enabled）

### SPEC-AGT-004: Memory 管理

- 构造时通过 `MemoryStore.load(config.memoryDir())` 加载记忆。
- `saveMemory()` 委托给 `MemoryStore.save()`。
- `memory()` 返回 `MemoryStore` 的公开引用，供上层直接操作。

---

## 8. PlanMiddleware 规格

### SPEC-HOK-001: Middleware 事件处理

| Middleware 阶段 / 事件 | 日志级别 | 输出内容 |
|------|---------|---------|
| `onReasoning` 开始 | DEBUG | Agent 正在思考... |
| `TextBlockDeltaEvent` 汇总完成 | DEBUG | 推理文本前 3 行 |
| `onActing` 完成 | DEBUG | 工具执行完成 |
| `AgentResultEvent` | INFO | Agent 回复文本 |
| 其他事件 | — | 忽略 |

- **SPEC-HOK-001a**: 所有洋葱阶段必须调用 `next.apply(input)` 并原样透传事件；仅在预算阻断时可追加 `RequestStopEvent`。
- **SPEC-HOK-001b**: 中间件不得修改既有事件内容。
- **SPEC-HOK-001c**: 使用 SLF4J Logger 输出，不使用 System.out/err。
- **SPEC-HOK-001d**: 优先使用 `ModelCallEndEvent` 的 `ChatUsage` 精确计量；未返回 usage 时才按文本长度估算。

---

## 9. AppSession 规格

### SPEC-APP-001: App.main() 契约

- 创建 `AppSession` 实例，初始化所有服务（AW、Desktop、Agent、Watcher）。
- 通过 `CountDownLatch` 阻塞主线程，等待 shutdown hook 触发。
- 注册 `Runtime.getRuntime().addShutdownHook`，在 Ctrl+C 时调用 `AppSession.close()` 优雅关闭。
- 启动失败时 `log.error` 并 `System.exit(1)`。

### SPEC-APP-002: AppSession 生命周期

- 构造时加载 Config，根据 `aw.mode` 决定是否启动嵌入式 AW 服务。
- 在构造 SelfAnalystAgent **之前**创建 `UserConfigStore`，并传入 Agent 构造器，使 `ConfigTools` 可用。
- 构造 DesktopServer 并注册 `/desktop/*` 路由（当 AW 以 embedded 模式运行时）。DesktopServer 内部独立创建自己的 `UserConfigStore` 实例供配置页使用。
- `saveAndShutdown()` 持久化 Memory，依次关闭 AudioWatcher → ContentWatcher → WatcherManager → AwServer。
- `close()` 委托给 `saveAndShutdown()`。
- 实现 `AutoCloseable`，支持 try-with-resources。

---

## 10. 测试规格

### SPEC-TST-001: MemoryStore 测试

| 测试用例 | 预期结果 |
|---------|---------|
| `shouldSaveAndLoadProfile` | 保存 Goal 和 Pattern 后重新加载，数据完整一致 |
| `shouldHandleEmptyProfile` | 空目录加载不抛异常，返回空 GrowthProfile，summary 含 "暂无" |
| `shouldRoundTripImprovementLog` | 保存 ImprovementLog 后重新加载，数据一致 |

### SPEC-TST-002: 测试环境约束

- 文件系统测试必须使用 `@TempDir` 隔离，不得污染真实文件系统。
- 测试不依赖外部服务（ActivityWatch、LLM API）。

---

## 11. 构建与打包规格

### SPEC-BLD-001: Maven 构建

多模块项目（`self-analyst-aw` + `self-analyst-content` + `self-analyst-audio` + `self-analyst-app` + Tauri 桌面壳），从根 pom 构建 Java 部分：

- `mvn compile`: 编译四个 Java 模块所有源文件，Java 21 target。
- `mvn test`: 运行所有 JUnit Jupiter 测试（38 个：aw AQL + app Memory + content capture）。
- `mvn package -DskipTests`: 在 `self-analyst-app/target/` 生成 fat jar（maven-shade-plugin）。
- `pnpm tauri build`: 在 `self-analyst-desktop/` 构建桌面安装包（MSI/NSIS）。

### SPEC-BLD-002: Fat Jar 约束

- 合并所有依赖的 `META-INF/services/` 文件（`AppendingTransformer`）。
- 排除签名文件（`*.SF`、`*.DSA`、`*.RSA`）避免签名冲突。
- 主类在 MANIFEST.MF 中声明。
- AW 模块 (`self-analyst-aw`) 为库模块（无 mainClass），app 模块依赖 aw 模块。

---

## 12. 非功能需求

### SPEC-NFR-001: 启动时间

从 `java -jar` 到所有服务就绪（HTTP 端口监听），在正常硬件上不超过 5 秒（不含 LLM 首次连接时间）。

### SPEC-NFR-002: 内存占用

空闲状态下（无请求）堆内存不超过 128MB。

### SPEC-NFR-003: 配置错误处理

- API Key 缺失时必须在启动阶段报错，不得在首次对话时才报错。
- ActivityWatch 不可达时不阻止启动，仅在调用工具时返回错误 JSON。

### SPEC-NFR-004: 字符编码

所有源代码和配置文件使用 UTF-8 编码。日志输出中的中文文本必须正确显示。

---

## 规格追溯矩阵

| 规格 ID | 对应源文件 | 对应测试 |
|---------|-----------|---------|
| SPEC-ARCH-001 | 所有模块 | — |
| SPEC-CFG-001..004 | `config/Config.java:16-58` | — |
| SPEC-MDL-001..005 | `memory/GrowthProfile.java:14-109` | MemoryStoreTest |
| SPEC-MEM-001..003 | `memory/MemoryStore.java:27-41` | MemoryStoreTest |
| SPEC-AW-001..007 | `tools/ActivityWatchTools.java:13-121` | — |
| SPEC-AGT-001..004 | `agent/SelfAnalystAgent.java:16-95` | — |
| SPEC-HOK-001 | `agent/PlanMiddleware.java` | `PlanMiddlewareTest` |
| SPEC-APP-001..002 | `AppSession.java`, `App.java` | — |
| SPEC-TST-001..002 | `memory/MemoryStoreTest.java` | — |
| SPEC-BLD-001..002 | `pom.xml`, `self-analyst-aw/pom.xml`, `self-analyst-app/pom.xml` | — |
