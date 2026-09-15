## 1. 权限修复与回归

- [x] 1.1 在桌面权限测试中补充打开数据目录的命令声明、生成权限、受管 main 页面授权和外部页面拒绝检查，记录修复前失败结果。Rust 实际 ACL 解析回归测试在修复前因缺少 `open_data_directory` 授权失败。
- [x] 1.2 修改 `self-analyst-desktop/src-tauri/build.rs` 并新增专用 capability，构建生成命令权限；验证回归检查通过且不增加通用 shell 授权。
- [x] 1.3 在 `self-analyst-app/src/test/js/runtime-storage.test.mjs` 补充成功调用与按钮恢复测试；执行 `node --test self-analyst-app/src/test/js/runtime-storage.test.mjs` 验证成功和失败反馈。4 项全部通过。

## 2. 集成验收与收尾

- [x] 2.1 执行 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`，确认权限构建与原生测试通过。27 项通过。
- [ ] 2.2 使用新构建桌面程序验证用户模式和便携模式打开实际 data 目录，含中文与空格路径；记录构建、操作与结果，并确认错误来源已消失。
- [ ] 2.3 执行 `openspec validate fix-open-data-directory-permission --strict`；确认本次恢复既有规格无需同步主规格或更新用户文档，完成后按归档技能归档。

## 验证记录

- 修复前：真实 Tauri ACL 解析测试失败，错误为 `open_data_directory must have an explicit application permission`。
- 修复后：桌面 Rust 27 项、前端 Node 4 项全部通过；专用权限、受管主窗口和外部来源拒绝均已覆盖。
- 现有 SPEC-DSK-DATA-001 无契约变化，无需同步主规格或修改 README、docs。
- 实际桌面验收未完成：隔离测试构建（`document-review` 特性、独立 `com.selfanalyst.storage-review` 标识）已成功构建；便携测试后端成功启动，但首次沙箱启动无可操作窗口，随后交互启动遭遇前测试进程目录占用。已清理本次测试的 SelfAnalyst 进程。独立命名启动最终返回 `Computer Use app approval timed out`，尚无成功点击打开目录的证据。任务 2.2 保持未完成，暂不归档。
