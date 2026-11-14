## 1. 准备与验证

- [x] 1.1 PR #154 最新 head `4810920` 五项必需检查全部成功并正常合并，版本分支已重定位到 main `3a51619e563d2b9ee9e40b078f0628e19925fa6c`；远端 beta.18 标签不存在。
- [x] 1.2 同步 13 个自身版本文件，解析 XML/JSON/TOML 确认全部为 0.6.1-beta.18；新增 docs/releases/v0.6.1-beta.18.md，不改依赖或工作流。
- [x] 1.3 补记功能 change 的真实浏览器截图与首次失败修复事实，保留未完成原生/真实邮箱验收，功能任务 4.4 已在精确最新 head 五项检查全部成功且正常合并后勾选。
- [x] 1.4 运行发布通道定向 Node、全量 Node、OpenSpec 全量 strict 和 git diff --check，记录通过、失败和条件跳过；检查完整差异无用户数据或凭据。

## 2. 远端发布门禁

- [ ] 2.1 创建独立版本 PR，等待精确最新提交全部必需 CI（包括 Windows 全量验证）通过后正常合并；不绕过分支保护、不强推。
- [ ] 2.2 在版本 PR 精确合并提交建立 v0.6.1-beta.18 标签，核验远端 Windows Release、便携 ZIP、NSIS EXE、两份 SHA-256 与发布包运行时/安装冒烟；确认 Pre-release、非 latest 后交付实际下载地址。

## 准备状态

临时起点 `6cceb8e` 已重定位至 PR #154 真实合并提交 `3a51619e563d2b9ee9e40b078f0628e19925fa6c`，两者代码树相同。只保留本次版本元数据与文档差异，不本地打包；版本 PR、其必需 CI 与发布仍待完成。

## 本地验证记录

- 13 个自身版本文件解析 XML/JSON/TOML 并定位 Cargo 自身包名，均为 `0.6.1-beta.18`；每文件恰好一处原版本替换，依赖未动。
- `node --test self-analyst-app/src/test/js/resolve-release-channel.test.mjs`：23 项均因非 Windows 条件跳过，不计为通过；未运行 PowerShell 发布通道验证。
- `node --test self-analyst-app/src/test/js/*.test.mjs`：503 项，471 通过、32 条件跳过，0 失败。本次版本元数据变更未重跑 Maven，功能全量结果单独保留于功能 verification.md；最终版本 PR 的 Windows 全量验证仍需等待。
- OpenSpec 1.11.0 `validate --all --strict`：50/50 通过。`git diff --check` 通过；完整差异仅 13 个自身版本、发行说明和本次规划/验证记录，不含业务实现、用户配置、凭据或构建资产。
- 功能 PR #154 已合并，版本分支准备本地提交；尚未推送，版本 PR CI 与远端发布仍未完成。
