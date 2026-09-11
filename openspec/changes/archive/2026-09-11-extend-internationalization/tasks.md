## 1. 语言注册与后端资源

- [x] 1.1 在 `self-analyst-app/src/main/resources/i18n/` 增加共享语言清单，调整 Java `com.selfanalyst.i18n` 的注册与解析；扩展 `LangResolverTest` 验证显式值、大小写、auto、zh-TW、未知系统语言和注入测试语言。
- [x] 1.2 调整 `Config`、配置应用服务与桌面状态通道，固定启动期有效语言并返回日期 Locale、可选语言；通过配置及状态测试验证保存 en 后仅刷新仍为 zh，其他配置热更新不提前应用语言。
- [x] 1.3 将提示词选择与后端确定性提示接入通用资源加载，移除业务层中英二选一分支；通过 `SummaryPromptI18nTest` 及相关 Agent 测试验证双语、资源回退和测试语言，用户原文不变。

## 2. 桌面页面与设置

- [x] 2.1 拆分 `desktop-ui/i18n.js` 的目录到 `desktop-ui/locales/`，保留 t() 并统一英文回退；新增 Node 测试验证缺失 key、命名参数、HTML 特殊字符及参数不递归解释。
- [x] 2.2 调整 `index.html`、`init.js`、`state.js` 与 `utils.js` 的初始化和日期 Locale，提供有界失败及幂等重试；测试首次状态失败后恢复 zh、目录缺失、无重复计时器/事件绑定及 HTML lang 更新。
- [x] 2.3 在 `config.js` 及设置标记中增加 auto/中文/English 选择与有效语言显示，复用 TOML 草稿和保存流程；扩展配置 Node 测试验证保留无关编辑、无效草稿提示、保存失败、放弃修改和重启提示。

## 3. 用户可见错误

- [x] 3.1 清点 `desktop/controller`、桌面服务及 UI API/SSE 消费处的应用自有用户错误，在本 change 的实施记录中列出入口和稳定错误码映射，覆盖配置、文件、聊天、记忆、摘要与连接测试；确认没有以中文文本匹配错误类型。
- [x] 3.2 给已清点的桌面错误增量加入 `errorCode`/`errorParams` 并按有效语言生成既有消息字段，HTTP 状态不变；控制器回归测试覆盖中英目录校验及配置失败，确认路径原样保留且无新增内部详情。
- [x] 3.3 调整 `desktop-ui/api.js` 及各错误展示入口优先消费已知错误码，兼容 SSE、未知码和旧响应；扩展 `api-error.test.mjs` 及聊天相关测试验证通用提示、原字段兼容和安全转义。

## 4. 原生桌面

- [x] 4.1 在 `self-analyst-desktop/src-tauri` 增加共享清单与原生消息资源加载，迁移 `lib.rs` 的托盘、关于、自启动和原生失败提示；Rust 单元测试验证中英、参数及测试语言选择，菜单 ID 与行为不变。
- [x] 4.2 将系统临时语言与后端就绪语言接入壳启动流程，通过受认证实际端口读取后端语言后创建正常入口；Rust 测试验证显式语言覆盖、超时、无效响应、失败清理和第二实例提示，不改变单实例及 token 边界。

## 5. 完整性与系统验证

- [x] 5.1 为各域目录与提示词接入构建校验，检查重复/缺失 key、参数集合及正式资源存在性；破损资源夹具必须使校验失败并报告语言与 key，测试用第三语言不得进入正式资源或选项。
- [x] 5.2 运行针对性 Java 测试，例如 `mvn -pl self-analyst-app -am '-Dtest=LangResolverTest,SummaryPromptI18nTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，并运行本变更新增的配置、错误和状态测试；记录实际测试类及结果。
- [x] 5.3 运行 `node --test self-analyst-app/src/test/js/*.test.mjs`、`cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` 和跨模块 `mvn test`；记录结果并修复失败，确认正式打包包含语言资源且无测试语言。
- [x] 5.4 在 Windows 分别以 zh/en 进行页面、托盘、关于、自启动及错误提示视觉验收，覆盖保存后重启、系统与显式语言不同、后端启动失败和页面请求恢复；提供截图及可复现结果，确认英文无截断、历史内容不变。

## 6. 规格同步与完成

- [x] 6.1 使用 openspec-sync-specs 将增量同步到 `openspec/specs/internationalization/spec.md`，更新 `docs/architecture.md` 和现有中文用户操作说明中的语言入口、生效方式及扩展步骤；检查稳定规格 ID 和重启语义一致。
- [x] 6.2 运行 `openspec validate extend-internationalization --strict` 及主规格严格校验，在实施记录写明验证结果和保留文档；全部任务完成后使用 openspec-archive-change 归档。

## 7. 首次提交前审查修正

- [x] 7.2 对 TOML 中已被标量或子表占用的 app.language 路径禁用无损编辑，补充 `i18n.test.mjs` 冲突回归测试。

- [x] 7.1 修复 `config.js` 保存失败后语言选择框未恢复的问题，补充 `config-runtime.test.mjs` 回归测试并运行 Node 测试；将双语验收截图纳入 PR 可访问的文档目录。
