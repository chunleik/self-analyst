## 1. 格式和生成源

- [x] 1.1 在 `self-analyst-app/src/main/java/com/selfanalyst/document/DocumentFormat.java` 与 `DocumentRequest.java` 增加 HTML/SVG 和 schemaVersion=2 专用源码，保留版本 1；通过请求测试验证格式匹配、未知字段、超限、空源码和旧七种格式兼容。
- [x] 1.2 更新同目录 `DocumentTools.java` 的工具说明与参数示例，区分 HTML/SVG 源码、普通结构和直接导出；通过工具描述测试确认模型能发现新格式及限制。

## 2. 渲染和校验

- [x] 2.1 在文档包新增 HTML 渲染与非执行结构检查，接入 `DocumentRenderer.java` 和 `DocumentFormatVerifier.java`，实现内嵌 CSS/JS/SVG、UTF-8、受控 CSP 和静态外部资源拒绝；测试脚本保留、内部 SVG 引用、外部依赖及生成阶段不执行源码。
- [x] 2.2 实现独立 SVG 写入和安全 XML 校验；测试中文、viewBox、路径、渐变、marker/use 内部引用，以及 DTD、实体、事件、script、foreignObject、CSS 外部引用和错误命名空间被拒绝，失败不发布文件。
- [x] 2.3 为版本 1 正文/表格及 `DocumentDataSources.java` 的直接导出接入本地 HTML 模板；测试特殊字符、脚本结束标记、空结果、来源元数据和分页完整性，并验证 SVG 直接导出及不兼容转换明确失败。

## 3. 生命周期与下载

- [x] 3.1 核对并适配 `DocumentService.java` 的源码保存、读取、哈希和版本；通过服务测试验证修改生成新版本、同轮重试复用、重启恢复、预算/取消失败及旧版本保留，源码读取超限不静默截断。
- [x] 3.2 核对 `DesktopDocumentController.java`、桌面 UI 文件卡片与 `self-analyst-desktop/` 原生另存为格式处理，仅按必要范围适配；接口及 Node 测试验证 HTML/SVG MIME、附件名、扩展名、鉴权、跨会话拒绝和元数据转义，确认无预览或自动打开。

## 4. 综合验收和文档

- [x] 4.1 运行文档和下载控制器针对性 Java 测试，使用 `mvn -pl self-analyst-app -am '-Dtest=*Document*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test`，并运行受影响桌面 Node 测试；保存命令与结果，跨模块改动再运行 `mvn test`。
- [x] 4.2 用固定测试数据生成交互 HTML 与独立 SVG 样例，在离线 Chromium 浏览器中打开下载文件，验证筛选、计算、SVG 更新、中文、内部引用及无外部资源依赖；记录浏览器版本、操作结果和截图。此为受控测试样例验收，生产生成流程不运行源码。
- [x] 4.3 更新 `README.md` 英文说明与 `README.zh-CN.md` 中文说明，说明两种格式、离线交付和无内置预览；核对完整差异及两种语言信息一致性。
- [x] 4.4 使用 openspec-sync-specs 同步 `agent-document-generation` 主规格及格式数量 Purpose，保留稳定 ID；运行 `openspec validate add-html-svg-output --strict` 与相关主规格严格校验，核对需求和验收证据一致。
- [x] 4.5 实现与验证完成后使用 openspec-archive-change 归档，记录测试结果、主规格同步及 README 更新情况，确认归档产物完整。
