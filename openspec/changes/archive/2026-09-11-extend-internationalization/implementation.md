# 国际化实施记录

## 代码与兼容范围

功能分支：`codex/extend-internationalization`。当前只开放 auto、zh、en；额外语言仅作为测试夹具。语言元数据由共享注册表提供，启动期 Config 保存有效语言，页面和原生菜单读取后端结论。

前端资源已按语言拆分，增加静态 JSON 路由、有界加载与失败重试、HTML lang、日期 Locale 和语言选择入口。选择入口仅无损修改可安全识别的单行 TOML；复杂语法保留原始编辑入口，不覆盖未保存内容。

## 错误入口与稳定代码

| 入口 | 代码类别 | 兼容处理 |
| --- | --- | --- |
| 文件设置与目录校验 | `error.file.settingsType`、`pathType`、`pathsArray`、`tooManyPaths`、`pathComma`、`pathAbsolute`、`pathInvalid`、`pathMissing`、`unavailable` | 原 HTTP 状态不变，路径作为参数保留 |
| 配置读取与保存 | `error.configRead`、`error.configSave`、`error.invalidRequest` | 原错误字段保留，语法/类型诊断安全传入参数，写入失败不暴露内部异常 |
| LLM/Embedding 连接测试 | `error.llmNotConfigured`、`error.http`、`error.generic` | 保留 `ok` 与原 HTTP 状态 |
| 会话、消息管理 | `error.session.*`、`error.sessions.*`、`error.message.*`、`error.messages.*`、`error.chat.*` | 保留不存在、冲突及请求校验状态 |
| 聊天流与非流响应 | `error.chat.sessionMissing`、`wrongTurn`、`staleTurn`、`cancelled`、`emptyResponse`、`stillRunning`、`unavailable` | HTTP/SSE 采用相同代码，保留 SSE 的 status |
| 记忆与记忆策略 | `error.memory.*`、`error.session.memory.*`、`error.memory.policy.*` | 保留原对象数据、策略及状态 |
| 任务与摘要 | `error.task.*`、`error.tasks.*`、`error.summary.generate` | 只本地化固定错误文案，不改写业务内容 |
| 未知错误与旧响应 | `error.generic`、`error.invalidRequest` | 本地化通用包裹，已有可公开诊断按原样保留 |

错误类型以代码、异常类型及原操作入口确定，不根据中文字符串匹配。前端对已知码优先翻译，缺失码保留兼容路径；用户参数不作为 HTML 或二次模板执行。

## 验证结果

- 语言解析、状态、配置、Agent/摘要提示词以及错误响应的针对性 Java 测试通过。
- 2026-09-11 全量 `mvn test` 通过；Node 测试 103 项通过。
- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`：16 项通过，包含语言资源、参数、认证握手及超时。
- `mvn package -DskipTests` 与桌面 `cargo build` 通过；正式注册表只含 zh/en，额外提示词语言仅位于测试资源目录。
- 主规格同步到 `openspec/specs/internationalization/spec.md`；README 与架构文档保留并更新，历史 `docs/archive/` 文档不改写。

## Windows 视觉验收完成

2026-09-11 在隔离目录 target/i18n-qa 完成验收，采集和 LLM 均关闭，不修改用户安装、自启动注册或真实数据。

- 英文新生成页面：时间轴、无数据状态、本地建议均为英文；验收发现的后端固定中文已迁入资源，新增 LocalSummaryI18nTest 覆盖英文空页面和全部本地建议分支。
- 中文新生成页面：标签及本地生成内容为中文；切换后已有英文快照保持原样，再次生成的内容使用中文。
- 语言保存：通过页面从 English 切换中文并保存，保存后仍显示 Currently active: en，明确提示重启，其他 TOML 字段保留；重启后界面切换中文。
- 原生菜单：英文检查替换其他分发路径的长文案，中文检查自启动状态不可用的禁用项；均无截断。菜单及关于弹窗复用正式创建函数。
- 关于弹窗：中英文标题和正文完整可见；操作系统提供的确定按钮沿用系统语言。
- 配置错误：中英文缺少 API Key 提示均正确展示，未发起外部模型请求。
- 启动失败：在只含 EXE、缺少后端 JAR 的隔离目录显示系统语言的中文启动失败提示；有效语言尚不可得时不依赖后端翻译。
- 状态请求失败后恢复、参数安全、时长解析、语言保存及模型热更新的一致性由自动回归测试验证；英文时长参与快照指纹计算时与中文分钟数一致。

截图位于忽略目录 target/i18n-qa/evidence：en-agent.png、zh-agent.png、en-language-saved.png、en-menu.png、zh-menu.png、en-about.png、zh-about.png、en-settings-error.png、zh-settings-error.png、zh-startup-failure.png。

## 原生验收复现说明

手工原生验收使用 cargo test --features native-menu-review --no-run 编译的 lib 测试 EXE 副本。测试 EXE 默认缺少正式应用的 Windows manifest，可能在加载原生菜单依赖时报告 0xc0000139；用 Windows SDK 的 mt.exe 从正式 SelfAnalyst.exe 提取 manifest 并嵌入测试副本后即可启动。此操作只作用于忽略目录中的测试副本。

设置 SELF_ANALYST_MENU_REVIEW_LANGUAGE=en 或 zh，SELF_ANALYST_MENU_REVIEW_CASE=enabled、other 或 unavailable，运行 tests::native_tray_menu_review --exact --ignored。设置 SELF_ANALYST_MENU_REVIEW_ABOUT=1 可检查关于弹窗。菜单窗口取得焦点后显示正式菜单，并有自动退出时限；测试仅使用模拟自启动状态，不访问注册表。

验收使用显式空闲端口。现有 App 入口发布配置端口，events.port=0 会让桌面壳收到 0 并拒绝启动；本 change 未修改该既有入口行为。测试数据与旧快照备份保留在 target/i18n-qa。

最新全量 Maven、103 项 Node、16 项普通 Rust 测试通过；change 及 21 项主规格严格校验通过。README、docs/architecture.md 已更新；docs/archive 下历史文档保留不改写。

## 首次提交前审查

对 app 被标量占用、app.language 已为表或嵌套表的合法 TOML 草稿，语言选择器不再插入冲突字段；新增回归断言确认保留原草稿并拒绝该无损编辑操作。

修复配置保存失败后语言选择框未恢复可用的问题，并在 config-runtime.test.mjs 补充回归断言，103 项 Node 测试通过。按 CI 要求执行 Rust 格式化及 Clippy（警告作为错误），均通过。分支已快进同步最新 main，未覆盖工作区实现。

双语截图已复制到 docs/mockups/i18n，并提供中文索引 README，供 PR 直接引用；target/i18n-qa 仍是本地测试产物目录。功能交付通过分支及 PR 完成，远端必需检查应以 PR 最新提交为准。
