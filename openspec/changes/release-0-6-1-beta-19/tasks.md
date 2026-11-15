## 1. 版本准备

- [x] 1.1 核对功能 PR #160 五项必需检查通过、合并 SHA `6538be12f1a2d3c26ac6ca44d1e25267779ffe79`，并用 `git ls-remote --tags origin refs/tags/v0.6.1-beta.19` 确认标签尚不存在。
- [x] 1.2 更新 13 份版本文件并通过 XML、JSON、TOML 解析检查自身版本一致；新增中文发行说明，明确邮箱、输入框变化及真实验收边界。
- [x] 1.3 运行发布通道 Node 测试、全量 Node 测试、`openspec validate --all --strict` 和 `git diff --check`，如实记录通过、跳过和未运行项目。

## 2. 远端发布门禁

- [ ] 2.1 创建独立版本 PR；最新提交的全部必需检查（含 Windows 全量验证）通过后正常合并，记录精确合并 SHA。
- [ ] 2.2 在版本 PR 合并提交创建 `v0.6.1-beta.19` 标签，由远端 Windows 工作流打包；验证 Pre-release 非 latest、安装包、ZIP 和 SHA256，再提供下载链接。

## 本地验证记录

- 13 份项目版本文件经 XML/JSON/TOML 解析均为 `0.6.1-beta.19`。
- `node --test self-analyst-app/src/test/js/resolve-release-channel.test.mjs`：23 项均因平台条件跳过，不能视为发布通道逻辑已在本地通过。
- `node --test self-analyst-app/src/test/js/*.test.mjs`：493 项，461 通过、32 跳过、0 失败。
- `openspec validate --all --strict`：51 项通过、0 失败；`git diff --check` 通过。
- 本次仅版本元数据变更，未在此工作树重跑 Maven、Windows/Tauri 或真实邮箱验收，未本地打包。最新版本 PR 的远端必需检查仍须全部通过。
