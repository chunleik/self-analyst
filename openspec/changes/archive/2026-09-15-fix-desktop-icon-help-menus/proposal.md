## Why

桌面标题栏图标显示为损坏图片：静态资源路由将 PNG 二进制按 UTF-8 文本转换，导致响应字节损坏。帮助菜单缺少检查更新入口，托盘中的关于入口需要移除，统一从帮助菜单查看关于信息。

## What Changes

- 修复静态图片响应，保留原始字节并返回正确图片内容类型，使标题栏应用图标正常显示。
- 帮助菜单新增「检查更新」，按用户选择在应用内检测最新稳定版本并展示结果和下载入口，不自动下载安装。
- 保留帮助菜单的关于信息，移除托盘右键菜单中的关于项及相应事件分支。
- 同步中英文文案、相关规格和两个语言版本的 README，补充图片响应、更新检测和菜单交互回归验证。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `desktop-help`: 增加检查更新入口及结果反馈，关于信息统一从帮助菜单访问。
- `desktop-shell`: 明确标题栏图标可正常解码显示，移除托盘关于入口。

## Impact

- Java 后端 `DesktopServer` 静态资源响应及对应 HTTP 集成测试。
- 共享前端 `index.html`、`titlebar-help.js`、中英文语言资源及 Node 测试。
- Tauri 帮助命令、版本检测逻辑、托盘菜单和 Rust 测试；复用现有 HTTP 客户端。
- `README.md`、`README.zh-CN.md`、`openspec/specs/desktop-help` 和 `desktop-shell`。
- 仅手动检查时访问项目公开版本元数据；不发送活动内容或本地凭据，不增加后台更新任务或自动安装机制。
