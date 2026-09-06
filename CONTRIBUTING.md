# 贡献指南

感谢你考虑为 SelfAnalyst 做贡献。SelfAnalyst 是一个本地优先的个人活动分析工具，因此它对
**数据边界**和**规格一致性**的要求比一般项目更严格。在动手写代码之前，请先读完本文的
[数据边界红线](#数据边界红线)和[规格驱动流程](#规格驱动流程)两节。

项目文档统一使用简体中文，详见[文档语言](#文档语言)。

## 目录

- [先看这里](#先看这里)
- [开发环境](#开发环境)
- [仓库结构](#仓库结构)
- [数据边界红线](#数据边界红线)
- [规格驱动流程](#规格驱动流程)
- [编码风格](#编码风格)
- [测试要求](#测试要求)
- [提交规范](#提交规范)
- [拉取请求](#拉取请求)
- [文档语言](#文档语言)
- [许可与第三方组件](#许可与第三方组件)

## 先看这里

- **报告缺陷或提出需求**：请使用仓库的 Issue 模板，并按模板提供操作系统版本、`aw.mode`
  （`embedded` / `external`）和采集开关状态。缺少这些信息的问题通常无法复现。
- **报告安全漏洞**：**不要**开公开 Issue。请按 [SECURITY.md](SECURITY.md) 创建私密
  GitHub Security Advisory。
- **参与讨论**：所有互动都受 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) 约束。
- **较大改动请先开 Issue 讨论**。本项目的行为契约由 `openspec/specs/` 下的规格定义，
  未经讨论就直接提交跨模块重构或改变数据边界的 PR，很可能因为与规格冲突而无法合并。

## 开发环境

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 21 | 后端与全部 Java 模块 |
| Maven | 3.9+ | 构建与测试入口 |
| Node.js | 20+（CI 使用 24） | 桌面前端测试 |
| Rust | stable + Cargo | Tauri 壳与 accessibility sidecar |
| pnpm | 最新稳定版 | 桌面壳依赖管理 |
| OpenSpec CLI | 1.11.0 | 规格校验 |

**平台约束**：完整桌面体验和内容采集依赖 Windows 10/11 的 UIAutomation。非 Windows 环境可以
构建和运行 Java 模块测试，但无法运行 sidecar 相关验证；CI 只在 `windows-latest` 上执行。

安装 OpenSpec CLI：

```powershell
npm install --global @fission-ai/openspec@1.11.0
```

运行桌面前端测试**不需要**执行 `npm install`，Node 测试由 Maven `test` 阶段直接调用内置
test runner。

## 仓库结构

| 模块 | 职责 |
|------|------|
| `self-analyst-aw` | 嵌入式 ActivityWatch、事件策略、原始事件永久保留与历史迁移 |
| `self-analyst-content` | 前台窗口、UIA 临时查询、上下文标题提取 |
| `self-analyst-file` | 用户显式配置目录中的文件系统元数据采集 |
| `self-analyst-wiki` | 标题事实的时间聚合、摘要与语义索引 |
| `self-analyst-app` | 启动编排、Agent、桌面 API 与桌面 UI |
| `self-analyst-axsidecar` | Windows UIAutomation Rust 边车 |
| `self-analyst-desktop` | Tauri 桌面壳 |

Java 代码位于各模块的 `src/main/java` 与 `src/test/java`。桌面 UI 资源在
`self-analyst-app/src/main/resources/desktop-ui/`，其 Node 测试在
`self-analyst-app/src/test/js/`。

以下目录是输出或本地产物，均已被 `.gitignore` 忽略，**不要提交**：`target/`、`data/`、
`logs/`、`dist/`、`dist-portable/`、`artifacts/`、`tools/`、`.tmp/`、`.codegraph/`、`.agents/`。

## 数据边界红线

这些约束是产品定位的一部分，不是可以为了功能便利而放宽的实现细节。违反其中任何一条的 PR
都不会被合并，即使功能本身有价值。

- **内容采集就是标题采集。** 允许在识别时临时读取完整 UIA 控件树，但最终只允许持久化系统
  窗口标题、对话人、文章/文档/页面标题及来源、置信度和时间等元数据。**禁止**保存 UIA 正文。
  内容事件只允许 v2 标题白名单字段，不允许 `text_content`、`uia_text`、`raw_tree`、正文或截图。
- **文件模块只采集元数据。** 只允许文件名、路径、大小和创建/修改时间。除安全解析
  `.gitignore` 外不读取普通文件正文，不计算内容哈希，不生成摘要、主题或向量。
- **不引入 OCR、截图、麦克风或系统声音采集。** 这些能力已被显式移除，背景见
  [移除说明](docs/archive/removed-features/removed-ocr-audio.md)。要重新引入，必须从
  `archive/pre-remove-ocr-audio` 标签开独立分支重新设计数据边界并先提出 OpenSpec 变更，
  不要把旧实现直接合回主线。
- **敏感应用跳过 UIA 查询**，UIA 失败时退回系统窗口标题，不使用截图/OCR 回退。
- **密钥不得进入 UI、日志、聊天正文、AgentState 或上下文快照。**
- **自动化测试不得读取真实前台窗口**，也不得访问用户真实数据目录或真实 LLM 服务。只有
  显式启用的 `ManualUiaSmokeTest` 可以读取前台窗口。

外发数据边界见 [PRIVACY.md](PRIVACY.md)，本地服务的安全边界见 [SECURITY.md](SECURITY.md)。

## 规格驱动流程

现行行为契约由 `openspec/specs/<capability>/spec.md` 定义，这些规格是**权威来源**，优先于
代码注释和历史文档。`docs/` 是文档索引与归档，`docs/archive/` 保留已被取代的背景材料。

**改动行为契约时必须同步更新对应主规格。** 只改代码不改规格，或只改规格不改索引，都会被
CI 的严格校验或评审拦下。

典型流程：

1. 在 [docs/README.md](docs/README.md) 的规格索引中找到相关 capability。
2. 对于新增能力或较大改动，在 `openspec/changes/` 下提出变更提案，讨论通过后再实施。
3. 实施完成后把 delta 规格同步进主规格，并归档变更。
4. 新增、删除或重命名 capability 时，**必须**同步更新 `docs/README.md` 的索引表。
5. 提交前本地校验：

```powershell
openspec validate --all --strict
```

`.agents/skills/` 下的 OpenSpec 工作流 skill 文件由 CLI 生成、不入版本库。克隆仓库后运行
`openspec update` 即可在本地重新生成；不使用智能体工作流时，直接用上面的 `openspec`
命令即可，`openspec --help` 可查看 `change`、`spec`、`archive`、`status` 等子命令。

## 编码风格

- Java 使用 4 空格缩进；JavaScript 与 CSS 使用 2 空格缩进。
- 类型用 `PascalCase`，成员用 `camelCase`，常量用 `UPPER_SNAKE_CASE`，包名前缀 `com.selfanalyst`。
- 优先写职责单一的小型辅助方法；文件写入使用原子写入。
- 修改现有 JavaScript 时遵循周边代码的既有风格，不要顺手引入新的框架或构建步骤。
- 注释只用来说明代码本身无法表达的约束，不要复述下一行做了什么。
- Rust 代码必须通过 `cargo fmt --check` 和 `cargo clippy -- -D warnings`。

## 测试要求

Java 测试命名为 `*Test.java`，跨组件集成测试命名为 `*IntegrationTest.java`；Node 测试命名为
`*.test.mjs`。使用 JUnit 5，文件系统相关测试用 `@TempDir` 隔离，HTTP 服务用随机端口并在
`@AfterEach` 关闭。

**修复缺陷必须补充回归测试。** 先跑针对性测试，涉及跨模块改动再跑全量。

```powershell
# 全部 Java 与桌面 Node 测试
mvn test

# 单个测试类（含依赖模块）
# PowerShell 会在第一个点号处拆开未加引号的 -D 参数，这类属性必须用单引号包裹
mvn -pl self-analyst-app -am '-Dtest=ConfigTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# Rust
cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml
cargo test --manifest-path self-analyst-axsidecar/Cargo.toml
cargo clippy --manifest-path self-analyst-axsidecar/Cargo.toml --all-targets -- -D warnings

# 打包与打包后冒烟
mvn package -DskipTests
.\scripts\check-packaged-jar.ps1
```

更多场景与手动验证入口见 [docs/testing.md](docs/testing.md)。

CI（`.github/workflows/ci.yml`）在 Windows 上执行 OpenSpec 严格校验、Rust 格式检查、
Java/Node 测试、Rust 测试与 Clippy、sidecar 构建、非敏感 UIA 冒烟、JAR 打包与打包后冒烟。
本地至少通过 `mvn test` 和 `openspec validate --all --strict` 再提 PR，可以省下大量往返。

## 提交规范

使用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)，提交说明使用简体中文：

```
feat(desktop): 增加配置页语言切换
fix(file): 修正元数据过滤器忽略符号链接
docs: 更新原始事件文档索引
chore(release): 完善 Windows 发布门禁
refactor(config): 分离便携包配置目录
```

常用 scope 与模块名对应，例如 `aw`、`content`、`file`、`wiki`、`app`、`desktop`、`axsidecar`、
`config`、`release`、`openspec`。

**每个提交聚焦单一事项。** 不要把格式化、重构和功能改动混在一个提交里。

**严禁提交**密钥、用户配置、数据库、日志，以及任何被 `.gitignore` 忽略的输出文件。

## 拉取请求

PR 描述请说明：

- **行为变化**：改了什么行为，以及为什么。
- **规格影响**：涉及哪些 `openspec/specs/` 主规格，是否已同步更新；若不涉及请明确说明。
- **数据边界**：是否触及采集范围、持久化字段或外发数据。触及时必须逐条说明如何满足
  [数据边界红线](#数据边界红线)。
- **验证方式**：实际运行过的命令及结果，不要只写"已测试"。
- **关联 Issue 或规格**。
- **UI 改动附截图**。

评审关注点依次是：数据边界与隐私、规格一致性、测试覆盖、代码风格。

## 文档语言

仓库内所有文档统一使用**简体中文**，包括 README、`docs/` 下的规范与设计文档、API 文档、
用户文档，以及其他说明性 Markdown 文件。命令、代码标识符、文件路径、协议名称、产品名称及
其他不宜翻译的专有名词保留英文。

## 许可与第三方组件

本项目采用 [Apache License 2.0](LICENSE)。提交贡献即表示你同意你的贡献以相同许可证发布，
并确认你有权提交这些内容。

引入新依赖时：

- 在 PR 中说明用途，并确认许可证与 Apache-2.0 兼容。
- 同步更新 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
- vendor 第三方文件时，必须一并提交其许可证原文，并在声明中记录来源与 SHA-256 校验值
  （参见 `deep-chat.bundle.js` 的处理方式）。
- 引入 GPL/AGPL 许可的组件前请先开 Issue 讨论。
