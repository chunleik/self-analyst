## Context

现有桌面关于通过同步 Windows MessageBox 展示，Web 关于和检查更新已经使用可关闭的共享帮助弹窗。发布构建发生在 GitHub Release 公开之前，无法预先写入可靠的 published_at。

## Goals / Non-Goals

**Goals:** 精确展示当前版本发布时间，不阻塞应用；离线降级可靠，安全边界保持不变。

**Non-Goals:** 不改发布流程、更新策略或 Web 版本识别，不新增运行时依赖，不获取用户数据。

## Decisions

- 复用共享帮助弹窗，由受保护的 help_action 返回本地关于信息，单独异步查询版本发布时间。避免原生消息框等待网络且无法更新内容。现有产品名、Tauri、后端地址全部保留。
- 使用精确 `/releases/tags/v{version}`，校验返回 tag 与版本一致且非 draft，允许 beta。Rust 复用 ureq 超时、响应大小限制和禁止重定向；仅公开 GET，不带凭据。前端只格式化经过严格校验的 UTC 时间。
- 前端以版本为键缓存公开发布时间，并以会话内 Promise 合并重复请求；localStorage 不可用时保留内存缓存。由于后端端口可能变化，持久缓存只保证同一应用 origin 可用，任何缺失均显示明确状态，不把缓存可用性作为功能前提。
- debug 构建返回开发状态，跳过公开时间查询；发行 profile 沿用自身版本号对应的公开 Release。关闭弹窗和新内容沿用 generation 防止迟到结果覆盖。

## Risks / Trade-offs

- GitHub 网络失败或限流 → 短超时、同版本缓存、清楚不可用提示，不影响基本关于信息。
- 缓存不可用或端口变化 → 内存缓存与不可用降级，不新增用户数据目录格式。
- Linux 环境不能运行原生 Tauri Windows 集成 → 准备独立 Rust 逻辑测试与 Chromium 弹窗检查；如工具链或浏览器启动受到环境限制，则明确记录未运行项，不以 Node 模拟测试代替真实 Windows 集成。
