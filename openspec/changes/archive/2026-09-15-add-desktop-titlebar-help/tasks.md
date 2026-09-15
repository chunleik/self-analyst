## 1. 窗口交互验证

- [x] 1.1 在 self-analyst-desktop/src-tauri 的隔离验收入口验证自定义标题栏、Windows 命中测试和窗口控制路径；记录拖动恢复、Alt+Space、边缘缩放及 Windows 11 最大化悬停贴靠的结果到本 change 的 verification.md，并根据结果补充 design.md 的具体 API 与清理策略。
- [x] 1.2 明确当前受管页面与动态端口的原生命令授权方案，在标题栏及帮助命令测试中验证合法来源成功、外部来源拒绝，不扩大已有命令权限。

## 2. 标题栏和导航

- [x] 2.1 修改 self-analyst-desktop/src-tauri/src/lib.rs 的主窗口创建及必要窗口模块，实现自定义标题栏对应的窗口行为；运行 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`，并实测关闭隐藏、托盘恢复、正常退出和单实例行为。
- [x] 2.2 修改 desktop-ui/index.html、styles.css 和初始化逻辑，增加桌面标题栏、移除桌面重复品牌并保留 Web 品牌及帮助入口；新增 src/test/js/titlebar-help.test.mjs，验证环境分支、导航保留及窗口按钮状态同步，运行 `node --test self-analyst-app/src/test/js/titlebar-help.test.mjs`。
- [x] 2.3 接入最小化、最大化、恢复、关闭及拖动区域，补充中英文可访问名称；使用针对性测试验证控件不会触发拖动，实际最大化事件能更新按钮状态，原生命令失败不会伪装成功。

## 3. 帮助功能

- [x] 3.1 新增共享帮助模块并接入中英文资源，实现菜单开关、定位、键盘及焦点管理；在 titlebar-help.test.mjs 覆盖外部点击、失焦、Esc、Tab、方向键和菜单执行，并验证不会清除会话草稿。
- [x] 3.2 实现固定指南与反馈地址、桌面受限打开命令及 Web 新标签页行为；从正式仓库地址验证中英文 README 和 Issues 可达，测试未知操作拒绝、无凭据外发及可检测打开失败的本地化提示。
- [x] 3.3 复用托盘关于信息并实现 Web 关于对话框；测试桌面入口信息一致、Web 不调用原生接口且不伪造版本，检查关闭后焦点回到帮助入口。

## 4. 集成与视觉验收

- [x] 4.1 在 self-analyst-app 目录运行 `node --test`，随后在仓库根运行 `mvn test`，并运行 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` 与 `cargo build --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`；在 verification.md 记录结果和失败处理。
- [x] 4.2 验收桌面与 Web 中英文在 1200×800、800×600 下的菜单和导航，包含文件入口显示、菜单展开和关于界面；在 docs/screenshots/titlebar-help/ 保存代表截图并记录无重叠、裁切和正文横向溢出。证据：8 组 DOM 布局检查与截图、中文原生预览截图及用户确认英文原生界面验收完成。
- [x] 4.3 实机验证 Windows 拖动、双击、最大化拖动恢复、Alt+Space、贴靠、最小化、关闭到托盘、自动隐藏启动和第二实例恢复；覆盖 100%、125%、150%、200% 显示缩放，在 verification.md 记录系统版本与实际结果，未验证项保持未完成。用户因无多显示器明确免除本次混合 DPI 多显示器实机验收，记录为未验收而非通过，不阻塞本次归档。

## 5. 文档与规格收尾

- [x] 5.1 同步更新 README.md 和 README.zh-CN.md 的帮助入口及标题栏说明；检查完整差异，确认英文首页无新增中文说明且双语信息一致。
- [x] 5.2 使用 openspec-sync-specs 同步 desktop-help 和 desktop-shell 主规格，运行 `openspec validate add-desktop-titlebar-help --strict` 和 `openspec validate --specs --strict`，确认新增稳定 ID 与现有契约一致。
- [x] 5.3 全部实施及验收完成后使用 openspec-archive-change 归档本 change，保留 verification.md 和用户向截图的可追溯路径；确认归档成功且主规格校验通过。
