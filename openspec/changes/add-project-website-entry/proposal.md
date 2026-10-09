## Why

软件已有帮助菜单，但缺少直接访问项目仓库首页、完整帮助文档和个人知识指南的入口。新增“项目官网”可让用户从应用内快速查看项目介绍与源码，目标按用户要求固定为 GitHub 仓库首页。

## What Changes

- 在桌面和 Web 共用的帮助菜单中，于“关于 SelfAnalyst”前新增“项目官网”（英文为“Project website”）。
- 保留现有“使用指南”，新增“帮助文档”与“个人知识指南”，分别指向已存在的 `docs/README.md` 与 `docs/personal-ontology.md`，文档组、反馈更新组、官网关于组以分隔线区分。
- 点击后打开固定地址 `https://github.com/chunleik/self-analyst`：桌面使用系统默认浏览器，Web 使用独立标签页，保留当前应用页面和会话。
- 沿用既有固定目标与本地化失败提示，补充双语、外链安全边界、失败恢复和键盘导航回归测试。
- 同步桌面帮助主规格及中英文 README；本变更不修改现有公开介绍网站。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `desktop-help`: 扩展菜单顺序，新增项目官网和帮助文档的固定目标、打开方式及失败行为要求。

## Impact

影响共享桌面 UI 的帮助菜单、双语资源、帮助脚本、Tauri 帮助动作固定目标映射、对应 Node/Rust 测试及 README。复用现有打开方式与受管窗口校验，不新增依赖、不扩大任意 URL 权限，按后续授权通过功能分支和草稿 PR 交付，不包含合并或发布。
