## Context

动机见 proposal.md。前端无参数调用 `open_data_directory`，Rust 检查主窗口、当前后端 origin 和数据目录存在性后调用系统打开。`build.rs` 的 AppManifest 只声明 `save_document`，`documents.json` 也只授权另存为；现有 Node 测试模拟 invoke，无法发现真实 IPC 权限缺口。尚未从用户运行实例取得原始 IPC 错误，实际桌面验收须确认截图问题消失。

## Goals / Non-Goals

**Goals:** 恢复受管桌面主窗口打开当前数据目录的权限链路，覆盖授权与拒绝场景。

**Non-Goals:** 不授予网页通用 shell 路径访问权限，不改变存储布局或修改用户数据。

## Decisions

- 将命令加入 AppManifest，生成专用 allow/deny 权限；独立 runtime-storage capability 仅授予 main 窗口的 localhost/127.0.0.1 页面。保留 Rust 内当前后端端口和页面路径校验，以约束 capability 的端口通配范围。
- 沿用已存在的原生打开实现。本机 shell 插件源码显示 Rust `Shell::open` 使用 `open::open(None, ...)`，不经过 JavaScript URL scope，不能凭默认 URL 正则认定目录被拒绝，也无需放宽 shell scope。
- 补充真实 ACL 配置链路回归检查，验证构建命令声明、生成权限和 capability 匹配；保留前端成功/失败状态测试，增加桌面实际点击验收。

## Risks / Trade-offs

- [仅模拟 invoke 掩盖权限错误] → 检查生成 ACL 并执行受管桌面验收。
- [远程 URL 端口通配授权过宽] → 仅授予专用命令并保留命令内精确 origin 和主窗口校验。
- [用户运行旧安装包] → 验收记录所用构建；源码修改不代表已安装应用更新。

## Migration Plan

重新构建桌面壳后验证用户模式与便携模式。无数据迁移；回退桌面程序即可回滚。
