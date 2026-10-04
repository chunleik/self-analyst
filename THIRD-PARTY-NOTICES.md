# 第三方组件声明

SelfAnalyst 采用 Apache License 2.0，完整文本见 [LICENSE](LICENSE)。项目依赖或在发布产物中包含
下列第三方组件；每个组件继续适用其自身许可证。若发现遗漏或许可证信息错误，请提交 Issue。

## Java 依赖

| 组件 | 用途 | 许可证 |
|------|------|--------|
| [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) | LLM Agent 框架 | Apache-2.0 |
| [Javalin](https://javalin.io) | 嵌入式 HTTP 服务 | Apache-2.0 |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | SQLite JDBC 驱动 | Apache-2.0 |
| [JNA / JNA Platform](https://github.com/java-native-access/jna) | 窗口与 AFK 状态的原生调用 | Apache-2.0 / LGPL-2.1 双许可证；本项目按 Apache-2.0 使用 |
| [Jackson](https://github.com/FasterXML/jackson) | JSON 序列化 | Apache-2.0 |
| [jsoup](https://jsoup.org/) | HTML 静态解析与格式检查 | MIT |
| [Apache Lucene](https://lucene.apache.org) | 本地向量/KNN 语义索引 | Apache-2.0 |
| [Neo4j Java Driver 5.28.15](https://github.com/neo4j/neo4j-java-driver) | 可选 Neo4j 手工同步的运行时 Bolt 驱动 | Apache-2.0 |
| [Eclipse JGit](https://www.eclipse.org/jgit/) | 解析监控目录中的 `.gitignore` 规则 | Eclipse Distribution License 1.0 / BSD-3-Clause |
| [SLF4J](https://www.slf4j.org) | 日志门面 | MIT |
| [Logback](https://logback.qos.ch) | 日志实现 | EPL-1.0 / LGPL-2.1 双许可证 |
| [tomlj](https://github.com/tomlj/tomlj) | TOML 1.0 解析 | Apache-2.0 |
| [jtokkit](https://github.com/knuddelsgmbh/jtokkit) | 本地 token 计数 | MIT |
| [JUnit 5](https://junit.org/junit5/) | 测试依赖 | EPL-2.0 |
| [Neo4j Test Harness 5.26.0](https://github.com/neo4j/neo4j) | 仅 `test` scope 的合成图谱集成测试；不进入应用运行时依赖 | GPL-3.0-or-later（官方父 POM 声明） |

Neo4j Java Driver 的 `neo4j-java-driver-parent:5.28.15` POM 声明 Apache License 2.0；
与单独运行的 Neo4j 数据库服务分别适用各自许可证。Test Harness 的 `org.neo4j:parent:5.26.0`
[官方 POM](https://raw.githubusercontent.com/neo4j/neo4j/5.26.0/pom.xml) 声明 GPL-3.0-or-later，
其 JAR 中 `META-INF/LICENSE.txt`、`NOTICE.txt` 和 `LICENSES.txt` 提供 GPL 及相关第三方声明。
Harness 及其嵌入式数据库仅用于测试，不作为 Neo4j 服务随 SelfAnalyst 应用分发。

## Tauri 桌面壳

| 组件 | 用途 | 许可证 |
|------|------|--------|
| [Tauri 2.x](https://tauri.app) | 桌面应用壳 | MIT / Apache-2.0 |
| Microsoft Edge WebView2 Runtime | Windows 系统 WebView | Microsoft 专有可再发行条款；本项目不捆绑固定 Runtime |

Rust crate 依赖声明在 `self-analyst-desktop/src-tauri/Cargo.toml`，锁定版本记录在对应 `Cargo.lock`。

## 无障碍树边车

| 组件 | 用途 | 许可证 |
|------|------|--------|
| [uiautomation](https://github.com/leexgone/uiautomation-rs) | `self-analyst-axsidecar` 的 Windows UIAutomation 客户端 | Apache-2.0 |
| [serde](https://serde.rs) / `serde_json` | sidecar JSONL 协议；桌面语言注册表与消息资源解析 | MIT / Apache-2.0 |

## 桌面前端

| 组件 | 用途 | 许可证 |
|------|------|--------|
| [Deep Chat 2.5.0](https://github.com/OvidijusParsiunas/deep-chat) | 会话消息面和输入组件 | MIT |

Deep Chat bundle 位于 `self-analyst-app/src/main/resources/desktop-ui/deep-chat.bundle.js`，许可证原文位于
`self-analyst-app/src/main/resources/third-party/deep-chat-LICENSE.txt`。bundle 来自官方
`deep-chat@2.5.0` npm 包，SHA-256 为
`12E0B5352E26E257C4D80BCA9FCFB75DC382608EE9B15CF920A469D51C3496EA`。

## ActivityWatch 兼容性说明

SelfAnalyst 内嵌 AW 服务是独立 Java 实现，在数据格式和 API 上兼容
[ActivityWatch](https://activitywatch.net)（MPL-2.0），不包含 ActivityWatch 源代码。完整 aw-webui
预编译产物不随仓库或默认发行包分发。
