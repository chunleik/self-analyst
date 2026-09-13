# 文档生成功能验证记录

变更：`add-agent-document-generation`。验证日期：2026-09-13。实施分支：`codex/add-agent-document-generation`。

## 自动验证

- `mvn package` 全量验证：688 项 Java 测试，失败和错误均为 0；6 项按测试条件跳过。其中容量测试另外通过 `-Ddocument.benchmark=true` 单独执行。
- 桌面 Node 全量测试：113 项通过；新增 `document-artifacts.test.mjs` 的标识校验、响应乱序、取消与保存失败测试单独复跑通过。
- Rust：`cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`，20 项通过；`cargo fmt --check` 通过。
- 最后针对文件恢复及历史文档定位的调整，分别复跑 `DocumentStoreTest`、`DocumentPublicationTest`、`DocumentToolsTest`、`DocumentAgentIntegrationTest` 和 `AgentPromptsTest` 并重新打包，均通过。
- 覆盖内容包括七格式解析、数值类型、中文字体、跨页表格、页数与容量上限、原始分区快照、纳秒时间边界、消息裁剪、工具上下文注入、模型后续失败、同轮重放、损坏文件、发布中断、并发下载与删除、真实桌面凭据及跨会话拒绝。
- OpenSpec change 严格校验通过；六份增量规格与主规格逐条比较一致，24 份主规格严格校验通过。

## 文档与视觉检查

七种格式均生成后重新解析。Word 的长正文与跨页表格使用本机 Word 导出后逐页检查；PPT 使用 PowerPoint 导出后逐页检查；PDF 直接渲染检查。中文文字与表格未发现缺字、裁切或重叠。Office 软件仅用于验收查看，不参与产品中的文档生成。

样例保存在 `self-analyst-app/target/document-samples/`：包括七种格式、Word 长文与表格、PDF 长文与表格，以及 PPT 长文与表格。样例属于构建输出，不提交运行数据。

容量验证使用 100,000 行磁盘行集，分别生成并检查 CSV、JSON 和 XLSX，总耗时约 3.4 秒。记录的堆内存池峰值之和约 412 MB；该数值用于观察预算，不等同于同一时刻的整进程峰值。超出行数上限被拒绝，临时行集正常清理。

## 桌面与浏览器操作

使用独立 `document-review` 实例、独立目录和本地模拟模型，关闭采集，仅注入合成记录。未操作用户原有实例的数据。

- 从会话请求导出原始记录为 Excel，出现 XLSX 文件卡片。
- 使用系统另存为保存到 `.tmp/document-saved/原始记录 验收.xlsx`，界面显示“已保存”；外部副本 SHA-256 与受管成果一致。
- 取消 PPT 的系统保存对话框后显示“已取消保存”，成果仍可使用。
- 重启验收实例后，原会话与文件卡片恢复。
- 会话生成 Word 和 PPT；随后要求增加总结页，PPT 从 3 页变为 4 页，新成果显示 v2，原 v1 保留且父版本关联正确。
- 独立浏览器通过桌面会话 cookie 打开真实页面，点击下载后收到浏览器 download 事件，页面只显示“已发起下载”。业务下载 URL 不携带 token。

干净的会话成果截图已保存到 `docs/assets/document-generation.png` 并纳入使用指南；原生保存截图保存在 `.tmp/document-review/native-save.png`。

## 发行验证与保留文档

便携包内的 JRE 在仅包含发行 JRE 和 Windows System32 的 PATH 下，使用 shaded JAR 运行 `DocumentPortabilityProbe`，七种格式全部生成并通过格式检查。主机 Office 仅用于视觉验收；本次没有卸载主机软件或建立干净虚拟机。

已执行打包 JAR 启动检查及 NSIS 的临时安装、重装、资源、后端启动和卸载检查。包含历史文档列表工具的最终安装包再次执行上述检查，全部通过。

用户文档保留 `docs/document-generation.md`、`docs/architecture.md`、`docs/README.md` 和对应截图，后续随功能演进维护。本次没有需要额外迁移的历史文档。原有分发目录及旧构建 JAR 已备份到 `.tmp/document-build-backup-20260913/`。

本次未提交或推送代码。后续提交应关联本 change，并沿用功能分支、PR 和最新提交的 GitHub 必需检查流程。

最终安装包：`artifacts/SelfAnalyst_0.2.2_x64-setup.exe`；SHA-256：`b09a83fcc629e3601c760d14d711c84f0a653393794024f82d69b24771206829`。便携分发位于 `dist-portable/`。隔离验收程序、浏览器后端和模拟模型已停止，验收文件保留供复核。
