# 2026-09-20 修复与验收

## 根因与处理

专用命令权限已经存在，但开发构建的固定 `devUrl=http://localhost:5700/desktop-ui/` 让 Tauri 将默认端口后端页面识别成 Local，无法匹配 remote-only capability。此前 ACL 测试只覆盖 5701 的 Remote 情形。新增默认端口分类回归在修复前失败，移除固定 devUrl 后通过；实际窗口仍由后端握手的 External URL 创建，权限边界未扩大。

原有目录打开通过 detached PowerShell 进程中转，返回值仅表示中转进程创建成功。现改用 Windows `ShellExecuteW`，保留主窗口、当前后端 origin 与目录存在性检查，使用 UTF-16 目录参数，系统错误返回值 0..32 均报告失败，不接受前端路径参数。

用户明确要求无备份时隐藏底部提示。运行数据现跳过整块清理说明，四类容量统计仍准确显示旧库备份 0.0 MiB；有备份时继续提供迁移状态及确认清理入口。

## 测试

- 修复前：`generated_acl_allows_data_directory_only_from_managed_main_window` 对 `http://localhost:5700/desktop-ui/index.html` 报告 denied，日志 `target/storage-origin-regression.log`。
- Rust 全量：36 项通过，日志 `target/storage-fix-cargo-tests.log`。
- 前端全量：165 项通过，日志 `target/storage-fix-node-tests.log`。覆盖零备份隐藏整块提示、有备份保留操作、清理后重读容量、失败可重试。
- `cargo fmt --check`、`git diff --check`、OpenSpec change 严格校验通过。
- Maven 重新打包成功；默认桌面构建成功。测试资源、临时诊断页仅位于 `target/`，不进入发布资源。

## 真实桌面 IPC 验收

使用独立应用标识 `com.selfanalyst.storage-review-20260920`，关闭窗口、AFK 和标题采集，测试页只读取运行目录元数据并调用真实的 `open_data_directory`。

1. 用户目录模式，默认端口 5700：运行数据 API 返回新建的隔离用户目录，点击后页面显示 `IPC SUCCESS：系统目录打开调用成功`。系统产生新的资源管理器窗口；未读取其文件列表。
2. 显式便携模式，端口 5701：EXE 位于 `target/storage-ipc-review/程序 中文/`，API 返回该目录的 `data/`。点击后页面同样显示 IPC SUCCESS，验证中文、空格及非默认端口。
3. 测试页通过已认证生命周期接口关闭测试后端；随后停止隔离外壳。最终重新构建不带 review feature/identifier 的正常桌面程序，使用原用户运行目录启动。

原用户窗口的全量可访问性读取被自动审批拒绝，原因是可能包含无关活动记录；后续验收使用无活动数据的隔离实例完成，没有再读取原用户时间轴。

## 规格与文档

恢复 SPEC-DSK-DATA-001 既有目录打开行为；没有备份时保留零值统计仍符合 SPEC-CFGUI-LAYOUT-004。change 保持 `skip_specs: true`，无需主规格同步。README 与 `docs/runtime-storage.md` 的现有用户说明仍适用；历史文档和界面 mockup 保留。
