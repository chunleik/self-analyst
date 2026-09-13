## 1. 渲染基础与样例验证

- [x] 1.1 在 `self-analyst-app/pom.xml` 锁定 Office/PDF 依赖，在资源目录选择可分发中文字体并补齐许可记录；以最小 DOCX、XLSX、PPTX、PDF 样例验证生成、解析和中文显示，记录版本、包体与 JRE 模块需求。
- [x] 1.2 在 `com.selfanalyst.document` 定义结构化生成源、格式能力、查询描述和预算校验；新增 `DocumentRequestTest` 验证非法组合、深度、内容大小、字段类型及未知 schemaVersion。
- [x] 1.3 实现 CSV、JSON、Markdown 渲染器；在 `DocumentRendererTest` 中 验证 UTF-8、引号换行、公式前缀、嵌套 data、空结果及来源说明。
- [x] 1.4 实现 XLSX 明细、多表和元数据工作表；在 `DocumentRendererTest` 中 验证字符串不执行公式、数值类型、多页数据写入、临时文件清理及格式重读。
- [x] 1.5 实现 DOCX 标题、段落、列表及表格分页；在 `DocumentRendererTest` 中 验证内容完整，并渲染中文长文、宽表样例，保存无截断的视觉验收证据。
- [x] 1.6 实现 PDF 有界排版与中文字体嵌入；在 `DocumentRendererTest` 中 验证提取文字、页数、表格跨页和无外链加载，逐页检查样例无缺字和溢出。
- [x] 1.7 实现 PPTX 16:9 主题、封面、分节、正文和表格拆页；在 `DocumentRendererTest` 中 验证对象可编辑、文字完整、页数限制，并逐页检查长标题和表格样例。

## 2. 受控数据导出

- [x] 2.1 为 events 原始查询增加内部导出适配器及固定高水位，保留普通桌面查询语义；新增原始导出测试验证跨分区分页、持续新写入、覆盖缺口、范围限制、外部模式和原始存储不变性。
- [x] 2.2 在文档服务接入投影、Wiki 和文件元数据查询适配器，建立有界读取快照/临时数据集；新增 `DocumentDataSourceTest` 验证来源区分、类型、时区、空数据及快照失败，不读取监控文件正文。
- [x] 2.3 将导出数据集接入七格式渲染，记录来源与覆盖元数据；新增 `DocumentExportIntegrationTest` 对照固定事实样例逐字段、逐条核对，并验证大量数据不进入模型消息或工具结果。
- [x] 2.4 校准初始条数、页数、文件、时间及总存储预算；使用接近限制的数据集记录耗时和峰值内存，验证超限明确失败且没有静默截断或残留临时数据。

## 3. 文档存储、版本与恢复

- [x] 3.1 扩展 `ChatSessionStore` 的 SQLite schema，新增文档 job/artifact 元数据及分页查询；新增 `DocumentStoreTest` 验证旧会话兼容、writer lease、服务端归属、消息裁剪后的文件保留，并明确旧版数据库回退策略。
- [x] 3.2 实现受管目录、生成源、格式检查和 READY 发布协议；新增 `DocumentPublicationTest`，对写入、移动、事务提交、空间不足及重启各断点注入失败，验证半成品不可见且成功文件可恢复。
- [x] 3.3 实现规范化请求去重、父版本关联及受限生成源读取；在 `DocumentStoreTest` 和 `DocumentExportIntegrationTest` 中 验证同轮重试复用、新轮修改保留旧版、其他会话不能读取，以及 raw 生成源不能泄露正文。
- [x] 3.4 将文档清理接入既有会话删除 intent 和启动恢复；新增会话生命周期测试覆盖 busy 删除、下载并发删除、中断重启、孤儿清理及外部保存副本不受影响。

## 4. Agent 和桌面 API

- [x] 4.1 在 `AppSession`、`SelfAnalystAgent` 和工具包注册生成、导出及生成源读取工具，注入可信会话/轮次/认证上下文；新增 `DocumentToolsTest` 验证串行执行、跨线程身份、无会话降级、取消与预算计量。
- [x] 4.2 在桌面控制器和 `DesktopServer` 增加文档列表、详情及内容接口，扩展会话成果摘要；新增 `DesktopDocumentControllerTest` 验证 token/cookie、无 token 失败关闭、拒绝 query token、归属校验、分页和响应头。
- [x] 4.3 在 `DesktopAgentController` 及 Agent 提示词中接入文档状态，使用有界工具返回；新增流式集成测试覆盖文件成功后回答失败、取消发布竞态、断线重试和伪造文档引用，不放宽既有空文本规则。

## 5. 会话界面与系统保存

- [x] 5.1 在 `desktop-ui/` 实现文件卡片、会话成果列表及生成期间状态刷新，兼容增强消息面和 fallback；新增 `src/test/js/document-artifacts.test.mjs` 验证切换会话、乱序响应、历史恢复、去重、裁剪和文本转义。
- [x] 5.2 在 `self-analyst-desktop/src-tauri/` 添加受限保存命令、系统对话框及能力配置；Rust 测试验证仅接受受管会话文件标识、可信页面限制、固定后端来源、认证和禁止重定向。
- [x] 5.3 实现目标同目录临时写入、完整性检查及保留原文件的替换；Rust 测试和 Windows 手工验证覆盖中文/空格路径、覆盖确认、目标被更改、拒绝权限、磁盘失败、取消及受管数据路径保护。
- [x] 5.4 接入 WebView 原生保存与浏览器附件下载，补齐各语言资源；Node 测试验证成功/取消/失败和浏览器“已发起下载”的区别，浏览器实测确认无 URL token、无外部凭据传播和无大文件 JS 缓冲。

## 6. 集成验证、文档与交付准备

- [x] 6.1 运行针对性测试：`mvn -pl self-analyst-app -am '-Dtest=*Document*Test,Document*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test`，补跑实际命名的原始导出、会话生命周期测试；运行 `node --test self-analyst-app/src/test/js/document-artifacts.test.mjs` 和 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`，记录命令与结果。
- [x] 6.2 针对跨模块改动运行 `mvn test`；执行 `.\scripts\check-packaged-jar.ps1` 并构建/检查便携与安装包，在无 Office/Python/Node 的 Windows 环境验证七格式生成、重启恢复和另存为，记录验收证据。
- [x] 6.3 使用真实桌面会话完成“导出原始记录为 Excel”“生成 Word 报告”“生成 PPT 并增加总结页”“保存到另一目录”四条端到端路径；保留截图、中文排版检查记录及文件内容核对结果。
- [x] 6.4 更新 `docs/architecture.md` 和中文文档生成指南，说明格式基线、数据来源、资源限制、版本、会话删除及另存为行为；使用 openspec-sync-specs 同步本 change 的六个能力规格，核对原始查询例外与其余隐私边界一致。
- [x] 6.5 运行 `openspec validate add-agent-document-generation --strict` 并确认所有实施任务完成、主规格已同步，整理归档所需的验证记录。用户授权提交时遵循功能分支与 PR 流程，合并须等待最新提交的必需检查，包括 `Windows 全量验证`。
