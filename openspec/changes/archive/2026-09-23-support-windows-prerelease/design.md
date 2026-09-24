## Context

见 `proposal.md` 的 Why。当前 `.github/workflows/windows-release.yml` 对所有 `refs/tags/v*` 执行同一条 `gh release create`，没有 `--prerelease` 或 `--latest=false`。创建 Release 的步骤位于质量门禁和打包之后。项目版本分散在根 POM、五个模块 POM 的父版本、`self-analyst-desktop/package.json`、`self-analyst-desktop/src-tauri/tauri.conf.json`，以及 `self-analyst-desktop` 与 `self-analyst-axsidecar` 的 `Cargo.toml`。应用内更新检查读取 GitHub latest，并拒绝预发布标记和带预发布后缀的语义版本；本设计不改这条路径。

## Goals / Non-Goals

**Goals:**

- 在创建 Release 前区分稳定通道和 beta/rc 通道，并向 GitHub CLI 传递对应参数。
- 让预发布重复运行后仍保持 Pre-release 且不是最新发布。
- 在昂贵的测试和打包之前拒绝无法识别的标签和版本不一致的标签。
- 用可重复执行的脚本测试覆盖通道判断和版本比对。

**Non-Goals:**

- 不把当前 `0.4.0` 改成某个 beta、rc 或下一正式版。
- 不从标签写回 POM、`package.json`、Cargo 或 Tauri 版本。
- 不改变应用内检查更新，不让 Beta/RC 出现在更新提示中。
- 不改变 `workflow_dispatch` 在分支上只上传 artifact 的行为。

## Decisions

1. **通道判断放在可测试脚本中，工作流只消费结果。** 新增 `scripts/resolve-release-channel.ps1`，输入标签名，输出 `stable`、`prerelease` 或失败。稳定标签匹配 `vX.Y.Z`；预发布只匹配 `vX.Y.Z-beta.N` 与 `vX.Y.Z-rc.N`。数值段不允许前导零，预发布序号从 1 开始。其他 `v*` 以非零退出码失败。这样 YAML 不复制正则，测试可以固定标签样本。
   - 备选：只在工作流内联 PowerShell。拒绝，因为发布参数没有现成单测入口。

2. **版本比对使用同一脚本的显式字段读取，不扫描全文。** 比对去掉 `v` 后的标签版本与这些字段：根 POM 的项目版本、五个模块 POM 的父版本、桌面 `package.json` 的 `version`、`tauri.conf.json` 的 `version`、两个 Cargo 包自身的 `version`，以及两个 `Cargo.lock` 中同名包的 `version`。不比较依赖版本，避免把碰巧同号的第三方版本当成项目版本。不一致时脚本失败。
   - 备选：只比较 Tauri 版本。拒绝，因为 Maven 或 Cargo 漏改时安装包和关于页会显示不同版本。

3. **标签触发时，先分类并比对版本，再进入现有质量门禁。** `workflow_dispatch` 且引用不是 `refs/tags/v*` 时跳过这两步。无法识别的标签在打包前失败，因此不会执行后面的 `gh release create`。
   - 备选：保持检查放在创建 Release 步骤里。拒绝，因为版本错误仍会先跑完整测试和安装包构建。

4. **创建参数按通道分开；已存在的 Release 先修正标记再上传。** 稳定通道沿用现有 `gh release create`（不添加 `--prerelease`）。预发布通道添加 `--prerelease --latest=false`。Release 已存在时仍用 `gh release upload --clobber`；预发布通道在上传前执行 `gh release edit <tag> --prerelease --latest=false`，避免第一次被误建成正式版后，重跑只上传文件却留下 latest。稳定通道的重跑不把正式 Release 改成 Pre-release。
   - 备选：预发布也先手工 `gh release create`，工作流只上传。拒绝，因为标签推送和工作流创建之间会竞态，且与「推送标签即发布」的现有正式版流程不一致。

5. **测试沿用现有 PowerShell 脚本的 Node 测试方式。** 在 `self-analyst-app/src/test/js/` 增加 `*.test.mjs`，用 `node:test` 调用 `pwsh` 覆盖稳定、beta、rc、前导零、`beta.0`、alpha、缺少版本段，以及版本字段不一致。工作流文件只保留调用点和 `gh` 参数。

## Risks / Trade-offs

- [本变更合并前推送 beta/rc 标签] → 旧工作流会把它建成正式 latest。缓解：先合并本变更，再在后续发布变更中同步版本并打标签。
- [Maven、npm、Cargo、Tauri 对 `0.5.0-beta.1` 这类版本的打包细节要到真正升版时才经过发布工作流] → 本变更的脚本测试只验证字符串一致和通道分类；升版变更须单独跑打包。语义版本预发布后缀是这些工具的常规版本形式。
- [`--latest=false` 依赖运行器上的 GitHub CLI] → `windows-latest` 提供当前 CLI；参数写在预发布分支，稳定通道不依赖它。
- [已有正式标签重跑] → 稳定通道不调用会把 Release 降为 Pre-release 的 edit。`v0.4.0` 保持正式版。

## Migration Plan

1. 合并本变更后，`main` 的版本保持 `0.4.0`，不打新标签。
2. 需要试用包时，对目标分支手动运行 Windows Release，下载 artifact。
3. 准备 beta 或 rc 时，另起发布变更，把项目版本统一改为目标预发布号，合并后再推送对应标签。
4. 回滚本变更时还原工作流和脚本；已发布的正式 Release 不需要迁移。

## Open Questions

无。
