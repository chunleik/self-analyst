## 1. 发布基线

- [x] 1.1 核对最新 main 为 f5b7918893ce3243e2bb3f6048f4ea5a13baef59、最新正式 Release 为 v0.6.0，且 v0.6.1-beta.1 标签尚不存在；创建 codex/release-0.6.1-beta.1 分支。

## 2. 发布准备

- [x] 2.1 同步 13 处项目版本字段为 0.6.1-beta.1；通过 scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.1 核对输出为 prerelease，检查无依赖版本变动。
- [x] 2.2 新增 docs/releases/v0.6.1-beta.1.md，依据 v0.6.0..HEAD 的合并记录说明更新内容、预发布通道、备份升级和分发文件；检查不把已存在能力描述成新增功能。

## 3. 验证与归档

- [x] 3.1 运行发布通道 Node 回归测试和桌面 Node 测试、openspec validate release-0-6-1-beta-1 --strict、openspec validate --all --strict 与 git diff --check；分别记录通过与平台跳过项。
- [x] 3.2 确认本发布不改变 windows-release 主规格，无增量规格同步；任务完成后用 OpenSpec CLI 归档，再运行全量严格校验与差异检查。纯版本及说明变更不需要设计文档。

## 4. 归档后的交付

将发布准备提交到功能分支并创建草稿 PR。确认最新提交的所有必需检查，尤其 Windows 全量验证，成功后，将 PR 转为可合并并按正常分支保护流程合入 main；若 main 已推进，先按保护要求同步并重新等待检查。之后将全新 v0.6.1-beta.1 标签指向已合并的 main 提交，通过现有 Windows Release 工作流构建与发布。不得绕过保护、强制标签或手工修改 CI 以取得凭据权限。最终核对标签 SHA、发布工作流成功、Pre-release 标记、latest 仍为 v0.6.0、两个 Windows 分发文件及对应 SHA-256，并使用本次中文发行说明更新发布页。远端交付证据以 GitHub PR、Actions 和 Release 为准。

## 验证记录

- 在 Linux 云环境使用 PowerShell 7.6.6 运行 `scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.1`，输出为 `prerelease`，覆盖全部 13 处版本字段；完整版本差异只有项目自身版本更新，没有依赖变更。
- 原始桌面 Node 测试：221 项，190 通过、31 项因 Windows 平台条件跳过、0 失败。发布通道原始测试在 Linux 按既有条件跳过；另用仅解除平台 skip 的临时副本执行相同 23 项发布通道断言，23/23 通过，临时副本已移除。此结果不代替远端 Windows 全量验证。
- 当前 change 严格校验通过；归档前全量 OpenSpec 严格校验 32/32 通过；`git diff --check` 通过。
- windows-release 主规格保持不变，无增量规格需要同步；纯版本和说明变更按项目条件省略 design.md。通过 OpenSpec CLI 归档并再次全量严格校验，远端交付在后续 PR、Actions 和 Release 记录中核验。
- `docs/releases/` 下既有历史发行说明保留；新增 `docs/releases/v0.6.1-beta.1.md` 作为本次用户说明。
