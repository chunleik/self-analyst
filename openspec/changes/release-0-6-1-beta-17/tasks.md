## 1. 准备与验证

- [x] 1.1 核对功能 PR #157 精确 head `2c90ab1` 的五项检查全部成功，以实际合并 main `c4ab210` 建立版本分支，确认 beta.17 标签尚不存在。
- [x] 1.2 同步 13 个自身版本文件并解析 XML/JSON/TOML 确认一致；新增 `docs/releases/v0.6.1-beta.17.md`，保留真实服务与原生人工验收限制。
- [x] 1.3 在功能 change 仅补记最终 CI/截图复核/正常合并事实，保留尚未完成的验收项目，不归档。
- [x] 1.4 运行发布通道 Node、全量 Node、OpenSpec strict 与 diff 检查，记录非 Windows 条件跳过。

## 后续发布门禁

提交独立 draft 版本 PR，等待最新提交全部必需检查。版本 PR 最新检查全部成功并正常合并后，才在精确合并 SHA 建立发布标签。使用现有 GitHub Actions 远端打包，检查便携 ZIP、NSIS EXE、两份 SHA-256 及发布包 jlink runtime/安装冒烟。若工具无法继续则报告已完成部分与阻断，不改工作流、不绕过保护。发布保持 Pre-release、非 latest；发布页或草稿本身不证明构建和附件验证已完成。未获另行授权的真实 Langfuse/QQ/163 及用户电脑不访问，不自动归档。

## 本地验证记录

13 个自身版本文件逐一仅替换一个版本，XML/JSON/TOML 解析与 Cargo 包名定位一致。发布通道定向 Node 23 项因非 Windows 全部条件跳过，不能计为通过；当前未运行 PowerShell。完整 Node 454 项，422 通过、32 条件跳过、0 失败。OpenSpec 1.11.0 全项目严格校验 48/48，diff 检查通过。Windows 真实通道/完整测试和打包检查等待版本 PR 及发布工作流，不本地打包。
