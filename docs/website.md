# 官网维护

对应 OpenSpec change：`add-project-website`。官网用于产品介绍和下载引导，独立于桌面 UI。

## 文件与语言

- `website/index.html`：语言选择入口；脚本不可用时显示双语链接。
- `website/zh-CN/index.html`、`website/en/index.html`：完整语言页面，按用户要求提供双语正文。
- `website/assets/`：共享样式、语言选择脚本及公开产品截图。
- `scripts/website.test.mjs`：语言选择、内容完整性、静态路径和发布文件检查。

新增文案时同时维护两种语言，包括标题、描述、按钮和替代文本。外部中文文档在英文页标注语言。
显式语言路径优先于偏好；根入口优先读取本地语言偏好，否则中文浏览器使用简体中文，其他使用英文。
存储不可用不会阻止页面浏览。隐私文案以 `PRIVACY.md` 和相关主规格为准。

## 本地预览与检查

在仓库根目录使用 Node.js 20 或更高版本：

```powershell
node --test scripts/website.test.mjs
node scripts/preview-website.mjs
```

访问 `http://127.0.0.1:4173/self-analyst/`，模拟真实 Pages 仓库子路径。用 Ctrl+C 停止服务器。
检查两种语言、窄屏与桌面布局、键盘焦点、图片、直接访问及刷新。预览服务器仅监听本机回环地址。

## 下载内容依据

2026-09-13 核实正式版本 `v0.2.1` 存在 Windows x64 安装包、便携 ZIP 及 SHA-256 文件。
官网统一链接 `https://github.com/chunleik/self-analyst/releases/latest`，不写死版本和资产名称。
现有 `docs/mockups/desktop-chat-screenshot.png` 已视觉核对：展示公开产品界面和通用技术文档活动，未见密钥、身份或私人聊天信息；复制到官网目录并标注中文界面示例。

## 发布与回滚

`.github/workflows/website-pages.yml` 在相关 PR 执行只读检查；main 的相关变更或 main 手动触发才执行部署。
仅部署任务获得 Pages 和 OIDC 写权限。上传路径严格限定为 `website`，禁止改为仓库根目录。
首次发布需将仓库 Settings → Pages → Build and deployment → Source 设置为 GitHub Actions。
预期公开地址为 `https://chunleik.github.io/self-analyst/`；部署成功并实测前不能视为已上线。

官网变更遵循功能分支 → PR → 最新提交必需检查（含 Windows 全量验证）→ 授权合并流程。
发布后检查 `/zh-CN/` 与 `/en/` 的直接访问、资源加载和下载跳转。回滚通过 PR 撤销官网变更并重新部署，或恢复此前已验证的官网资源；不涉及应用数据迁移。
Actions 配置依据：[GitHub Pages 自定义工作流](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)。

## 首次实施验证记录

2026-09-13，本地 `node --test scripts/website.test.mjs` 的 5 项测试通过，`openspec validate --all --strict` 的 24 项校验通过。
浏览器验证了根入口选择、显式语言地址、切换后偏好记忆、刷新、图片加载和键盘跳过导航入口。
390px 窄屏下，两种语言文档宽度均为 375px（扣除滚动条），未出现横向溢出；桌面布局也已检查。
双语截图保存在忽略的 `artifacts/website-qa/`，不包含在 Pages 产物内。
当前记录只证明本地验证，远端 PR 必需检查、公开部署及部署后验证应在交付时另行记录。
