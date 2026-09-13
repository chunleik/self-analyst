# 实施验证记录

验证日期：2026-09-12。功能分支：`codex/extract-llm-settings-feature`。

## 自动化验证

- JDK 21 针对性 Maven 测试通过：`LlmSettingsServiceTest`、`LlmTomlEditorTest`、`LlmConnectionProbeTest`、`DesktopLlmSettingsControllerTest`、`ConfigApplicationServiceTest`、`LlmHotReloadIntegrationTest`、`DesktopConfigControllerTest`，共 58 项 Java 测试。
- `mvn test` 全模块成功；Surefire 报告合计 677 项，0 失败、0 错误、5 项按现有条件跳过。Node 测试 110 项全部通过。
- 最后的焦点与状态刷新调整之后，模型草稿、国际化及静态配置相关 Node 测试 13 项通过。
- `openspec validate extract-llm-settings-feature --strict` 通过；同步后 `openspec validate --specs` 22 项通过，`openspec validate --all --strict` 23 项通过。
- `git diff --check` 通过。

## 界面验收

使用 `node scripts/llm-settings-preview.mjs` 的本地内存假数据，加载实际桌面 HTML、JS、CSS 和中文语言资源。
验证默认模型页签、生成参数和密码状态、模型发现、长模型 ID 输入、保存结果、原文页签、Tab/Enter 导航，
以及默认窗口和 540×780 窄窗口布局。窄窗口面板、表单、字段网格的 scrollWidth 均未超过 clientWidth。
测试过程未输入真实密钥、读取真实配置或调用外部模型。

截图位于构建输出目录，不进入源码提交：

- `self-analyst-app/target/llm-settings-qa/desktop.png`
- `self-analyst-app/target/llm-settings-qa/desktop-details.png`
- `self-analyst-app/target/llm-settings-qa/narrow.png`

## 修正记录

- 测试最初误用 `LLM_API_KEY`；按现有解析器改为 `OPENAI_API_KEY`，环境来源、清空和恢复继承测试通过。
- HTTP 集成测试放入 `com.selfanalyst.events` 测试包，以使用已有包可见的显式 token 构造器；没有扩大生产 API。
- Node 自动测试会发现测试目录中的预览脚本，常驻服务阻止测试退出；预览脚本移至 `scripts/` 后全量验证通过。
- 生成测试拒绝空或非对象协议结果；表单状态刷新隔离已替换的快照，保存后焦点回到结果区域。

## 规格与文档

新增 `llm-settings` 主规格，更新 `user-configuration` 中三个既有要求，保留其余内容。
`README.md` 的配置章节、`docs/architecture.md` 和 `docs/llm-settings.md` 是本次更新的用户向/架构文档；
既有 `docs/archive/` 历史资料保持原状，不作为新增行为的权威规格。

本次完成工作区实现与本地验证；未执行提交、推送、PR 或远端 Actions 检查，未合并 main。
