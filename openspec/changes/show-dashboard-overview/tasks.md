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
