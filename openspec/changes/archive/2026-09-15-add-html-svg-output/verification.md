# HTML/SVG 输出验证记录

## 实施结果

- HTML 专用源码、内嵌 JavaScript/CSS/SVG、普通结构 HTML 模板和直接数据导出已接入受管文档流程。
- 独立 SVG 保留矢量源码，校验命名空间、画布、内部资源引用及被动内容边界。
- 原生另存为新增 html/svg 白名单，浏览器沿用附件下载；无应用内预览或自动打开。
- 源码恢复、重试复用、修改版本、失败不发布、跨会话拒绝均有回归覆盖。

## 自动验证

环境：Windows，JDK 21；Maven 使用本机 `settings-aliyun.xml` 镜像。

- `mvn -s <本机镜像配置> -pl self-analyst-app -am '-Dtest=*Document*Test' '-Ddocument.visual.samples=true' '-Dsurefire.failIfNoSpecifiedTests=false' test`：33 项文档测试通过，1 项性能基准默认跳过。
- `mvn -s <本机镜像配置> test`：全量 Java 727 项，721 项通过、6 项按已有条件跳过，0 失败/错误；Node 130 项通过。
- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml documents::tests`：2 项通过。
- `cargo fmt --manifest-path self-analyst-desktop/src-tauri/Cargo.toml -- --check`：通过。
- `openspec validate add-html-svg-output --strict`：通过。
- `openspec validate --specs --strict`：26 份规格通过。
- `git diff --check`：通过；英文 README 新增段落无中文说明，中文版内容对应。

## 离线浏览器验收

先运行上面的样例生成测试，再将 NODE_PATH 指向本机已安装的 Playwright：

```text
node scripts/markup-browser-qa.cjs self-analyst-app/target/document-samples
```

Edge 153.0.4234.32，无头独立浏览器上下文、offline=true：

- 输入 8，点击计算后显示 16，内嵌 SVG 柱宽更新为 160。
- 点击筛选后隐藏“待处理”行。
- 独立 SVG 的中文、渐变、两个 use 内部引用及箭头显示正常，图形包围盒 540×80。
- 外部 HTTP 请求、页面错误及控制台错误均为 0。
- 已人工查看 `interactive.png` 与 `diagram.png`，文字和图形无缺失。

样例、截图和 `browser-qa.json` 位于 `self-analyst-app/target/document-samples/`，属于忽略的构建输出，可按上述命令重建。完整日志位于仓库 `target/html-svg-*.log`。

## 调试记录与限制

- 首次 Maven 编译受沙箱网络限制，使用权限提升和现有镜像后通过。
- SVG 的 Playwright 全页截图超时；固定视口截图正常，实际 SVG 打开与图形断言均通过。
- 手动浏览器脚本最初放在 Node 自动发现的测试目录，造成缺少 Playwright 的默认测试失败；已迁至 `scripts/`，默认测试不增加浏览器依赖，全量重跑通过。
- 生成时仅静态解析，不执行模型源码；CSP 和引用校验不等同于任意 JavaScript 的完整沙箱或逻辑正确性证明。
- 用户文档已同步 `README.md` 与 `README.zh-CN.md`，新增依赖已记录于 `THIRD-PARTY-NOTICES.md`；本次未新增或迁移 `docs/` 历史文档，已有文档保持原位。
- 主规格 `agent-document-generation` 已同步，SPEC-DOC-001/006 保留稳定 ID，新增 SPEC-DOC-007/008/009。
- 本次只完成本地实施与验证；尚未提交、推送或创建 PR，远端必需检查未运行。
