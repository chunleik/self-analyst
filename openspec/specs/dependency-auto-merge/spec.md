# 依赖升级自动合并规格

## Purpose

为 Dependabot 的补丁版本升级提供受 Windows CI 约束的自动合并流程，减少重复操作。

## Requirements

### Requirement: 仅自动处理 Dependabot 补丁升级

自动化 SHALL 仅为本仓库 Dependabot 创建、目标为 `main`、非草稿且元数据验证通过的 patch 升级启用 squash 自动合并。自动化 SHALL NOT 为 minor、major 或普通贡献者 PR 自动启用合并。

#### Scenario: 补丁升级符合条件

- **WHEN** Dependabot PR 的更新类型为 `version-update:semver-patch`
- **THEN** 工作流为该 PR 当前提交启用 squash 自动合并

#### Scenario: 非补丁升级

- **WHEN** PR 的更新类型为 minor、major 或无法识别
- **THEN** 工作流不自动启用合并，保留人工处理

### Requirement: 合并必须通过 Windows 验证

仓库 SHALL 开启自动合并，并在 `main` 分支保护中将 GitHub Actions 的 `Windows 全量验证` 设置为必需检查，同时要求分支与最新 `main` 保持同步。

#### Scenario: 检查尚未通过

- **WHEN** 必需 Windows 检查未完成、失败或分支落后于 `main`
- **THEN** 自动合并等待条件满足，不绕过分支保护

### Requirement: 元数据工作流不执行 PR 代码

自动合并工作流 SHALL 使用固定提交的元数据 Action，且 SHALL NOT 检出或执行 PR 分支代码；写权限 SHALL 限定在自动合并作业中。

#### Scenario: 普通贡献者提交 PR

- **WHEN** PR 作者不是 Dependabot 或来源不是本仓库
- **THEN** 自动合并作业跳过
