## Context

动机见 proposal.md。前端无参数调用 `open_data_directory`，Rust 检查主窗口、当前后端 origin 和数据目录存在性后调用系统打开。`build.rs` 的 AppManifest 只声明 `save_document`，`documents.json` 也只授权另存为；现有 Node 测试模拟 invoke，无法发现真实 IPC 权限缺口。尚未从用户运行实例取得原始 IPC 错误，实际桌面验收须确认截图问题消失。

## Goals / Non-Goals

**Goals:** 恢复受管桌面主窗口打开当前数据目录的权限链路，覆盖授权与拒绝场景。

**Non-Goals:** 不授予网页通用 shell 路径访问权限，不改变存储布局或修改用户数据。

## Decisions

- 2026-09-20 调查补充：本机 Tauri 2.11.2 的 `is_local_url` 会将可相对 `get_app_url` 定位的 URL 归为 Local；开发模式的 app URL 来自 `build.devUrl`。当前固定 5700 的配置导致默认端口调用不匹配 remote-only capability，而旧测试仅检查 5701 的 Remote 授权。去掉固定 devUrl，使用本地 frontendDist 作为框架 app URL，仍由 `create_main_window` 使用经过握手的 External 后端 URL。这样无需增加 Local 授权或放宽窗口、origin、路径校验。
- 按用户截图反馈，在没有旧库备份时省略底部清理说明块，容量卡片继续显示 0.0 MiB；有备份时保留迁移状态与风险确认。
- 桌面验收使用隔离的测试页面及临时数据目录，只读取运行数据和 IPC 返回值，不读取用户活动时间轴。
- 将命令加入 AppManifest，生成专用 allow/deny 权限；独立 runtime-storage capability 仅授予 main 窗口的 localhost/127.0.0.1 页面。保留 Rust 内当前后端端口和页面路径校验，以约束 capability 的端口通配范围。
- Rust 命令校验通过后直接用 Windows `ShellExecuteW` 打开目录，使用 UTF-16 路径并检查系统返回值。调查确认当前 shell 插件通过 detached PowerShell 进程中转，其成功只说明进程创建成功，不能反馈系统打开失败。新实现不经过命令字符串或前端任意路径，也不扩大 shell scope。
- 补充真实 ACL 配置链路回归检查，验证构建命令声明、生成权限和 capability 匹配；保留前端成功/失败状态测试，增加桌面实际点击验收。

## Risks / Trade-offs

- [仅模拟 invoke 掩盖权限错误] → 检查生成 ACL 并执行受管桌面验收。
- [远程 URL 端口通配授权过宽] → 仅授予专用命令并保留命令内精确 origin 和主窗口校验。
- [用户运行旧安装包] → 验收记录所用构建；源码修改不代表已安装应用更新。

## Migration Plan

重新构建桌面壳后验证用户模式与便携模式。无数据迁移；回退桌面程序即可回滚。
