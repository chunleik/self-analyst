## 1. 展示与回归

- [x] 1.1 修改 agent.js 与 styles.css，直接展示完整概览并弱化标题；新增 Node 测试验证折叠展示、转义、去重、无概览降级及刷新。
- [ ] 1.2 验证 API insight 映射未改变且 taskSegments 完整透传，并运行全部桌面 Node 测试及 800×600 / 桌面宽度浏览器布局检查，保留截图。

## 2. 文档与收尾

- [x] 2.1 同步 desktop-summary 主规格及中英文 README 的看板说明，检查文档语言和 git diff --check。
- [ ] 2.2 执行 OpenSpec 严格校验、归档已完成 change，提交草稿 PR 及验证记录。

## 初始概览变更验证记录（主题扩展前）

- Node 针对性测试 20/20 通过；完整桌面套件 210 通过、31 个既有跳过、0 失败。
- 已只读核对后端 primaryTask → headline、summary → insight 映射，未修改后端。
- 浏览器布局及截图尚未完成：本地 Chromium 进程 socket 被环境限制，云浏览器 localhost 权限请求已被关闭，未重试该路径。
- Maven 未安装，未执行 Java/Maven 测试；本变更不修改后端。
- 保留 change 为未归档状态，等待真实浏览器布局验证完成。

## 3. 主题明细扩展

- [x] 3.1 SummaryTimelineAssembler 透传 Wiki taskSegments；新增后端完整映射及旧数据回归。
- [x] 3.2 agent.js 安全渲染完整主题，隐藏空证据；测试有效、异常、旧版数据及交互。
- [x] 3.3 使用本地已保存摘要验证映射到渲染，检查完整展示。
- [x] 3.4 同步主规格及中英文说明，执行针对性及聚合验证并记录限制。

## 主题明细扩展验证记录

- Node 针对性测试 26/26 通过；完整桌面套件 216 通过、31 个既有跳过、0 失败。
- 后端新增 SummaryTimelineAssemblerTest 4 项，并运行既有 DesktopSummaryAssemblerTest、SummaryPromptServiceTest、SummaryFactFingerprintTest：合计 39/39 通过。通过 Java 21 当前源码叠加现有依赖与 JUnit 6.1.3 运行；相关服务源码编译 -Xlint:unchecked -Werror 通过；Maven 缺失，未运行完整 Maven 聚合测试。
- 通过临时 SQLite WikiStore、当前 SummaryTimelineAssembler 和 Jackson 序列化到 API，再由正式 agent.js 渲染，核对主题顺序、字段与完整叙述；overview/headline 不变，空 evidence 不显示。未调用模型或修改原数据。本地验证内容、数据库和预览不纳入提交。
- OpenSpec 全量严格校验 33/33 通过；git diff --check 通过。
- 真实浏览器布局与截图仍未验证：沿用既有环境限制，不重试被关闭的浏览器授权。离线 HTML 不是实机截图。主规格已同步，change 因布局验证未完成而保留未归档。
- 本次草稿 PR 包含概览与主题明细的完整改动，保留未完成的视觉验收；不涉及合并、部署或发布。

## 4. 阅读状态修复

- [x] 4.1 统一 styles.css 主摘要样式；agent.js 与 events.js 保存稳定条目展开状态，隔离统计日期/时区并清理移除项。
- [x] 4.2 补 summary-load.test.mjs 多轮自动刷新、主动收起、重复点击、旧数据、排序/删除与日期切换测试，运行完整 Node 套件。
- [ ] 4.3 同步主规格/用户说明，运行 OpenSpec 严格校验并在可用浏览器完成 800×600 与桌面视觉验证；若受限明确记录。

## 本次阅读状态修复验证记录

- 基于 main 5529e99；PR #111 已合并。未修改后端、摘要生成或原始数据。
- 独立 UI 阅读状态使用稳定条目标识，跨重绘恢复展开；清理移除项；按响应时区与 04:00 统计日期隔离，并兼容 Java 固定偏移时区。
- summary-load.test.mjs 34/34 通过；完整桌面 Node 套件 224 通过、31 个既有跳过、0 失败；agent.js/events.js 语法检查与 git diff --check 通过。
- OpenSpec 1.14.1 针对 change 与 desktop-summary 严格校验通过。全量严格校验 31/33；desktop-shell 与 user-configuration 的既有需求超过新版 CLI 的 500 字限制，两个文件与基线完全相同，本次未扩大范围修改。
- 当前执行器本地 Chromium 常规启动仍因 process_singleton_posix socket() failed: Operation not permitted 失败；停止该路径，未修改 sandbox/network，未重试此前关闭的云浏览器 localhost 授权。800×600 与桌面宽度视觉未验证，无修复后截图，不将 DOM/CSS 测试当作视觉验收。
- 4.3 文档与规格同步已完成，视觉验证仍待可用环境，因此保留该项与 change 未归档。本地修复完成后，按用户“提交PR”授权提交功能分支与草稿 PR；不部署或合并。
