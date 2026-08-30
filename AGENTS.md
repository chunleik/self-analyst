# 仓库指南

## 项目结构与模块组织

SelfAnalyst 是一个使用 Java 21 的 Maven 多模块项目。`self-analyst-app` 包含后端、智能体以及桌面端 API/UI；其他同级模块负责实现各项功能服务。集成测试位于各模块的 `src/test/java`，Tauri 外壳位于 `self-analyst-desktop/`，Rust 边车程序位于 `self-analyst-axsidecar/`。

Java 代码使用 `src/main/java` 和 `src/test/java` 目录。桌面端资源和 Node 测试分别位于 `self-analyst-app/src/main/resources/desktop-ui/` 和 `src/test/js/`。规范文档位于 `docs/specs/`；运行时目录和构建目录均为输出目录。

## 构建、测试与开发命令

- `mvn test` — 运行 JUnit 5 测试和桌面端 UI 的 Node 测试套件。
- `mvn -pl self-analyst-app -am -Dtest=ConfigTest -Dsurefire.failIfNoSpecifiedTests=false test` — 构建依赖模块并运行一个应用测试类。
- `mvn package -DskipTests` — 构建并生成 JAR 包。
- `cd self-analyst-desktop && pnpm install && pnpm tauri dev` — 以开发模式运行桌面端外壳。
- `cargo test --manifest-path self-analyst-axsidecar/Cargo.toml` — 测试 Rust 边车程序。

使用 JDK 21 和 Node.js 20 或更高版本；运行 UI 测试无需执行 npm 安装。

## 编码风格与命名约定

Java 使用四个空格缩进，JavaScript/CSS 使用两个空格缩进。类型使用 `PascalCase`，成员使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`，包名使用 `com.selfanalyst`。优先使用职责单一的小型辅助方法、原子写入，并遵循周边 JavaScript 代码的既有风格。

## 测试规范

Java 测试文件命名为 `*Test.java`，Node 测试文件命名为 `*.test.mjs`。使用 JUnit 5，并通过 `@TempDir` 处理文件系统测试。修复缺陷时应补充回归测试；先运行针对性测试，涉及跨模块变更时再运行 `mvn test`。

## 提交与拉取请求规范

提交信息遵循 Conventional Commits 规范，例如：`feat(desktop): ...`、`fix: ...`、`docs: ...` 或 `chore: ...`。每个提交应聚焦单一事项。拉取请求应说明行为变化、列出验证方式、关联相关问题或规范，并为 UI 变更附上截图。严禁提交密钥、用户配置、数据库、日志或已被忽略的输出文件。

## 文档语言规范

当前项目的所有文档统一使用简体中文，包括但不限于 README、`docs/` 下的规范文档、设计文档、API 文档、用户文档，以及提交到仓库中的其他说明性 Markdown 文件。新增或修改文档时必须使用简体中文；命令、代码标识符、文件路径、协议名称、产品名称及其他不宜翻译的专有名词可以保留英文。

## 智能体专用说明

保留工作区中与当前任务无关的变更；行为契约发生变化时，应同步更新 `docs/specs/` 中的相关文档。

<!-- CODEGRAPH_START -->
## CodeGraph

对于已经由 CodeGraph 建立索引的仓库（仓库根目录中存在 `.codegraph/` 目录），在需要理解或定位代码时，应先使用 CodeGraph，再使用 grep/find 或直接读取文件：

- **MCP 工具**（可用时）：`codegraph_explore` 通常可以通过一次调用回答大多数代码问题，并返回相关符号的原始源代码以及符号之间的调用路径。`codegraph_node` 返回单个符号的源代码及其调用方，也可读取带行号的完整文件。如果工具已列出但处于延迟加载状态，请通过工具搜索按名称加载。
- **Shell**（始终可用）：`codegraph explore "<符号名称或问题>"` 和 `codegraph node <符号或文件>` 会输出相同的信息。

如果不存在 `.codegraph/` 目录，则完全跳过 CodeGraph；是否建立索引由用户决定。
<!-- CODEGRAPH_END -->
