# 验证记录

## 实现范围

- 配置窗口按已确认预览实现模型设置、高级配置、运行数据三个页签，复用真实 API、事件处理与状态模型。
- 新样式位于 `self-analyst-app/src/main/resources/desktop-ui/config-layout.css`，限定在 `#config-modal` 内；不影响文件采集设置。
- 实际页面没有演示状态、运行模式切换、示例数值或模拟保存。浏览器验证单独使用 `target/config-layout-qa.cjs` 的虚构后端，未读取或修改用户配置。

## 自动验证

- 针对性配置、运行状态、模型设置与运行数据测试通过。
- `node --test self-analyst-app/src/test/js/*.test.mjs`：164 项通过，无失败。
- 覆盖：模型定向保存及凭据语义、失败保留草稿、保存期间编辑锁定、三页签懒加载与草稿确认、过期请求失效、原生命令不传路径、重复操作保护、备份清理确认、成功刷新及失败重试、未知容量不显示为零。
- `openspec validate reorganize-config-editor-layout --strict`：通过。
- `openspec validate --specs --strict`：28 项通过。
- `git diff --check`：通过。README 完整差异已核对，英文新增段落无中文混入，中文对应信息一致。

## 浏览器验证

使用实际 `index.html`、CSS、JavaScript 和中英文资源，后端由本地测试夹具提供虚构响应。

- 1280×800、800×600、390×700：三个页签共 9 组布局检查，无弹窗整体横向溢出；模型与原文保存栏始终位于视口内。
- 原文含长路径、长行及多行内容，编辑器内部滚动；配置来源明细可展开。
- 模型候选可点选；新密钥可显隐；保存后新密钥输入清空；加载失败显示重试，原文加载失败只读且禁止保存。
- 模型及原文的超长保存错误均完整保留在可滚动反馈区，草稿不丢失，保存栏不被挤出视口。
- 英文运行数据包含目录、容量和清理入口；配置及运行数据的可访问名称关联已翻译标题。
- 键盘 Shift+Tab 在配置窗口内循环，来源明细可获得焦点；隐藏页签中的控件不进入焦点循环。
- 文件采集设置在 800×600 下仍保持原布局及蓝色强调色。
- 浏览器控制台未发现页面 JavaScript 错误。

截图与几何数据保存在本地输出 `target/config-layout-qa/`：`llm-1280.png`、`raw-1280.png`、`storage-1280.png`，以及对应 `800`、`390` 版本；另有 `model-error-390.png`、`file-settings-800.png` 和 `layout-checks.json`。测试日志在 `target/config-layout-node-tests.log`。这些验证输出不提交。

## 边界与文档

本次未修改 Java、Rust、原生权限或后端 API，未重跑 Java/Rust 全量构建。原生目录打开成功/失败使用命令桥单元测试覆盖；未声称本次在已安装 Tauri 应用中完成系统文件管理器验收。已有 `fix-open-data-directory-permission` change 的待验收任务保持原状。

README 两个语言版本已同步。`docs/mockups/config-layout-reorg.html` 保留为已有设计参考，未改写；`docs/llm-settings.md` 和其它历史文档继续保留，其既有操作说明仍适用。主规格同步本次 4 条布局要求。
