## 1. 托盘提示修复

- [x] 1.1 在桌面壳测试中补充托盘提示回归用例，确认遗漏 tooltip 配置时失败；测试应覆盖实际构建配置，而非仅校验独立字符串常量。
- [x] 1.2 在 `self-analyst-desktop/src-tauri/src/lib.rs` 的托盘构建链显式设置 `SelfAnalyst` 悬停提示，运行针对性用例以及 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`，确认通过。

## 2. 验收与规格收尾

- [x] 2.1 用重新构建的 Windows 桌面壳验收主窗口可见、关闭后隐藏两种状态的托盘悬停提示，记录显示 `SelfAnalyst` 的证据，并复核左键恢复和右键菜单。
- [x] 2.2 同步 `openspec/specs/desktop-shell/spec.md`，运行 `openspec validate fix-tray-hover-tooltip --strict`，完成任务后按归档流程归档；以校验结果和归档目录为完成证据。

本变更仅补充托盘构建配置，无跨模块设计或迁移，依照条件规则省略 `design.md`。

## 验证记录

- 2026-09-14：`tray_builder_sets_product_tooltip` 在未设置 tooltip 时失败，补充配置后通过；该用例检查实际托盘初始化源码，不代表 Windows 视觉验收。
- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`：26 项测试全部通过。
- `cargo build --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --bin SelfAnalyst`：构建成功。
- 主规格已同步，变更严格校验及全部主规格校验通过。
- 2026-09-14：用户执行绿色版后确认“没有问题”，并提供显示 SelfAnalyst 的托盘悬停截图，验收由用户完成；据此完成收尾归档。
