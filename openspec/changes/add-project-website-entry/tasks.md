## 1. 帮助入口与双语界面

- [x] 1.1 在共享 `desktop-ui/index.html` 的关于项前加入官网项，在使用指南后加入帮助文档和个人知识指南并分组，更新中英文 locales，并用 DOM 顺序及文案回归验证。
- [x] 1.2 扩展 `titlebar-help.js` 的官网及文档固定目标与原生错误重试动作；用 Node 测试覆盖桌面/Web 打开、异常、重试、草稿保留与键盘焦点。

## 2. 原生边界与文档

- [x] 2.1 扩展 `src-tauri/src/titlebar.rs` 的固定官网及文档动作映射并补充 Rust 回归；核验公开 main 中目标文档存在，确认仍使用受管窗口校验及系统默认浏览器，不新增任意 URL 参数或权限。
- [x] 2.2 同步 `README.md` 与 `README.zh-CN.md` 的帮助入口说明，检查英文版无新增中文段落、两个版本一致。

## 3. 综合验证与交付

- [x] 3.1 运行针对性及全量 Node 测试、JavaScript 语法检查、`git diff --check`；运行可用的 Rust/Maven 检查并明确记录不可执行的环境限制。
- [ ] 3.2 在受支持浏览器或桌面路径可用时验证中英文菜单、焦点及外部打开并保存真实截图；若权限或环境不可用，明确保留视觉/原生验证限制，不绕过限制。
- [ ] 3.3 同步 desktop-help 主规格，运行 OpenSpec 严格校验，只有验证满足归档条件时归档；保留任何尚未完成的验证项。
- [x] 3.4 审查最终差异并生成不含用户数据或凭据的补丁，保存 Library 交付，记录本地验证与发布状态。


## 当前验证记录

- 基线：`e9d458c27f7135d55c908d91268364a41f12de90`（v0.6.1-beta.5），独立分支 `codex/add-project-help-links`。
- 已核验 `docs/README.md`、`docs/personal-ontology.md` 存在于该 main 提交；知识指南包含 Neo4j 同步指南链接。
- 帮助专项：21/21 通过；全量 Node：229 通过、0 失败、31 Windows 专用测试在 Linux 按定义跳过。
- OpenSpec 1.11.0 全量严格校验 34/34 通过，官网现有静态测试 5/5 通过。
- JavaScript 语法和 `git diff --check` 通过。当前环境无 Maven、Cargo，`mvn test`、`cargo test` 与 `cargo fmt` 返回 command not found，Rust 回归已补但未执行。
- 视觉与 Windows 实机打开仍待验证：已知浏览器访问限制未解除，未绕过限制，也未生成替代截图。此项保留未完成。
- desktop-help 主规格已按增量同步；原生/视觉验证未完成，change 保持未归档，不声称全部验收完成。
- 已获得提交功能分支和创建草稿 PR 的授权；CI 待远端精确提交验证。未授权合并或发布。
