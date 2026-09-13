## 1. 功能边界与配置契约

- [x] 1.1 在 `self-analyst-app/src/main/java/com/selfanalyst/llm/settings/` 建立设置服务、DTO、存储接口和凭据操作模型，复用现有 LlmSettings 数值规则；以 `LlmSettingsServiceTest` 验证单连接、字段白名单、局部 updates/reset、冲突操作和来源展示。
- [x] 1.2 实现脱敏设置快照与预设目录，映射现有五个键，预设地址通过官方文档核对并记录来源；以 `LlmSettingsServiceTest` 验证不回显明文/后缀/摘要、只读查询不写盘及 keep/replace/clear/reset 的环境兜底结果。

## 2. 定向持久化与运行协调

- [x] 2.1 在配置包增加受限 TOML 原文编辑辅助器；以 `LlmTomlEditorTest` 覆盖表内/点分/引号键、CRLF、行尾与中文注释、缺失文件、目标删除、未知键和非目标多行值，验证非目标原文不变与不安全编辑拒绝。
- [x] 2.2 为 `ConfigApplicationService` 增加锁内读取最新原文、定向编辑、候选语义差异校验及统一 commit 的入口，并实现 TOML 存储适配器；以 `ConfigApplicationServiceTest` 验证显式空密钥不被当作删除、交错保存保留无关键、读写失败不丢数据。
- [x] 2.3 对接现有运行结果及租约机制；扩展 `LlmHotReloadIntegrationTest` 验证新入口首次补填、旧回答排空、下一轮切换、构造失败保留旧版本、Embedding 继承密钥仍提示重启且不自动启用功能。

## 3. 探测与桌面接口

- [x] 3.1 实现有界模型发现，支持草稿凭据解析、有效 ID 去重、手工输入兜底；以本地 HTTP fixture 的 `LlmConnectionProbeTest` 验证目录成功/不支持/错误、条目数及响应大小上限，确认不写盘或切换运行版本。
- [x] 3.2 实现固定最小生成请求、耗时及受限错误分类；以 `LlmConnectionProbeTest` 验证目录成功但生成失败、空结果、畸形响应、认证/限流/参数错误、慢响应总超时、禁止重定向、地址改变时 keep 密钥不外发，以及输出不含密钥或远端正文。
- [x] 3.3 新增桌面模型设置控制器，在 `DesktopServer.java` 注册六个方法/路径组合；以 `DesktopLlmSettingsControllerTest` 验证认证及回环边界、非法输入 400、响应投影、成功保存快照及各操作契约，并验证旧 raw/结构化/目录检查接口保持兼容。

## 4. 模型设置界面与高级配置

- [x] 4.1 在 `desktop-ui/llm-settings.js`、`llm-settings.css` 和 `api.js` 实现独立表单、预设、发现候选、手工输入、只写密码、继承恢复和局部保存；以 `src/test/js/llm-settings.test.mjs` 验证首次打开不请求 raw、未变字段不提交、密码输入与清除确认、非法字段及保存错误保留草稿。
- [x] 4.2 在配置窗口的 `config.js`、`events.js`、`state.js`、`index.html` 及现有实际入口中接入模型/高级页签；扩展配置 UI 测试验证默认页签、双向脏态确认、放弃后重新读取、保存期间禁止离开、旧配置键定位进入高级页签及原文逐字保存。
- [x] 4.3 增加探测请求取消/代次保护与独立运行状态区域；以 Node 测试验证改地址、改密钥、改模型、关闭后旧响应失效，发现失败仍能手工填写，刷新状态不覆盖草稿，保存成功与测试成功分别显示。
- [x] 4.4 补齐 `desktop-ui/locales/` 和 `i18n/messages/` 正式语言资源，标明旧目录检查和新生成测试的区别及测试计费提示；通过既有国际化完整性测试，并人工验证键盘焦点、标签、窄窗口滚动和长模型 ID 布局，提供无真实密钥的界面截图。

## 5. 集成验证与文档交付

- [x] 5.1 使用 JDK 21 运行针对性 Java 验证：`mvn -pl self-analyst-app -am '-Dtest=LlmSettingsServiceTest,LlmTomlEditorTest,LlmConnectionProbeTest,DesktopLlmSettingsControllerTest,ConfigApplicationServiceTest,LlmHotReloadIntegrationTest,DesktopConfigControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`；记录结果，覆盖所引用新增测试类及原配置兼容测试。
- [x] 5.2 运行 `node --test self-analyst-app/src/test/js/llm-settings.test.mjs` 和相关原文/国际化测试，再运行 `mvn test` 验证共享配置与运行链路；全部网络测试使用本地 fixture，记录成功结果及失败修复证据。
- [x] 5.3 更新 `docs/architecture.md`、`README.md` 中相关配置说明，必要时新增 `docs/llm-settings.md` 并从 README 链接；文档说明模块边界、单连接范围、凭据操作、TOML 保真限制、同字段后提交者生效和探测计费，验证链接与实际界面一致。
- [x] 5.4 运行 `openspec validate extract-llm-settings-feature --strict`，使用 openspec-sync-specs 同步 `llm-settings` 和 `user-configuration` 主规格后运行 `openspec validate --all --strict`；确认任务和验证完成，再使用 openspec-archive-change 归档并记录验证结果。
