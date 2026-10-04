## ADDED Requirements

### Requirement: SPEC-DEPMERGE-001 补丁升级按更新项分组

Dependabot 配置 SHALL 为每个更新项将同一批次的 patch 级版本升级合并为一个 PR，以减少同批补丁升级 PR 因彼此合并而落后于 `main` 的情况。minor 与 major 升级 SHALL NOT 进入该分组，仍各自独立开 PR。

#### Scenario: 同一更新项有多个补丁升级

- **WHEN** Dependabot 在一次运行中发现同一更新项下有多个依赖存在 patch 级升级
- **THEN** 这些升级出现在同一个 PR 中

#### Scenario: 存在 minor 或 major 升级

- **WHEN** 某个依赖的可用升级为 minor 或 major
- **THEN** 该升级不并入补丁分组，单独开 PR

## MODIFIED Requirements

### Requirement: 仅自动处理 Dependabot 补丁升级

自动化 SHALL 仅为本仓库 Dependabot 创建、目标为 `main`、非草稿且元数据验证通过的 patch 升级启用 squash 自动合并。包含多个依赖的分组 PR SHALL 以其中最高的更新级别判定。自动化 SHALL NOT 为 minor、major 或普通贡献者 PR 自动启用合并。

#### Scenario: 补丁升级符合条件

- **WHEN** Dependabot PR 的更新类型为 `version-update:semver-patch`
- **THEN** 工作流为该 PR 当前提交启用 squash 自动合并

#### Scenario: 分组 PR 全部为补丁升级

- **WHEN** Dependabot 分组 PR 中所有依赖的升级均为 patch 级
- **THEN** 工作流为该 PR 当前提交启用 squash 自动合并

#### Scenario: 非补丁升级

- **WHEN** PR 的更新类型为 minor、major 或无法识别
- **THEN** 工作流不自动启用合并，保留人工处理
