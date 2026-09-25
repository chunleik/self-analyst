## Purpose

规定 Windows 发布工作流如何把稳定标签和 beta/rc 标签发布到 GitHub Releases，以及哪些引用只保留构建产物、不创建 Release。

## ADDED Requirements

### Requirement: SPEC-REL-001 稳定标签发布正式版
由 `vX.Y.Z` 标签触发且发布前校验通过时，工作流 SHALL 创建或更新同名 GitHub Release。该 Release MUST 是正式发布，可以成为仓库的最新发布，并附上便携 ZIP、NSIS 安装包及对应校验和。`X`、`Y`、`Z` SHALL 为不含前导零的非负整数；`0` 本身允许。

#### Scenario: 发布稳定版本
- **WHEN** 推送符合规则的稳定标签且该标签尚无 GitHub Release
- **THEN** 工作流创建同名正式 Release，附上便携 ZIP、NSIS 安装包及校验和，且该 Release 可以作为最新发布

#### Scenario: 重新上传稳定版本产物
- **WHEN** 同一稳定标签的工作流再次成功，且同名正式 Release 已存在
- **THEN** 工作流更新该 Release 的分发文件，并保持它是正式发布

### Requirement: SPEC-REL-002 beta 与 rc 发布预发布版
由 `vX.Y.Z-beta.N` 或 `vX.Y.Z-rc.N` 标签触发且发布前校验通过时，工作流 SHALL 创建或更新同名 GitHub Pre-release。该 Release MUST NOT 成为仓库的最新发布，并 SHALL 附上与正式版相同种类的便携 ZIP、NSIS 安装包及校验和。`N` SHALL 为从 1 开始、不含前导零的正整数。

#### Scenario: 发布 beta
- **WHEN** 推送 `vX.Y.Z-beta.N` 且该标签尚无 GitHub Release
- **THEN** 发布页出现标记为 Pre-release 的同名 Release，包含安装包、便携 ZIP 和校验和，且最新发布仍是此前的正式版

#### Scenario: 发布 rc
- **WHEN** 推送 `vX.Y.Z-rc.N` 且该标签尚无 GitHub Release
- **THEN** 发布页出现标记为 Pre-release 的同名 Release，且它不取代当前最新正式版

#### Scenario: 重新上传预发布产物
- **WHEN** 同一 beta 或 rc 标签的工作流再次成功，且同名 Release 已存在
- **THEN** 工作流更新分发文件，该 Release 仍是 Pre-release，且仍不是最新发布

### Requirement: SPEC-REL-003 无法识别的标签不得发布
除 SPEC-REL-001 和 SPEC-REL-002 规定的标签外，其他 `v*` 标签 MUST NOT 创建或更新 GitHub Release。工作流 SHALL 以失败结束，不得把这类标签当成正式版或预发布版。

#### Scenario: 标签格式不符合通道
- **WHEN** 推送的标签带有 `v` 前缀，但不是 `vX.Y.Z`、`vX.Y.Z-beta.N` 或 `vX.Y.Z-rc.N`
- **THEN** 工作流失败，且不创建、不更新对应 GitHub Release

### Requirement: SPEC-REL-004 手动构建不创建 Release
手动运行发布工作流且所选引用不是受支持的发布标签时，工作流 SHALL 构建并上传可下载的 Windows 分发 artifact，MUST NOT 创建 GitHub Release。

#### Scenario: 在分支上手动运行
- **WHEN** 对分支手动运行 Windows 发布工作流且构建成功
- **THEN** 该次运行提供 Windows 分发 artifact，仓库 Releases 不因此新增条目

### Requirement: SPEC-REL-005 标签版本必须与项目版本一致
受支持的发布标签在创建或更新 Release 前，其去掉 `v` 前缀后的版本 SHALL 与仓库中的项目版本一致。项目版本包括根 POM 与各模块父版本、桌面 `package.json`、Tauri 配置、桌面壳与 accessibility sidecar 的 Cargo 包版本。任一不一致时，工作流 MUST 失败且 MUST NOT 创建或更新 Release。工作流 MUST NOT 根据标签改写这些版本。

#### Scenario: 版本一致后才发布
- **WHEN** 受支持标签对应的版本与上述项目版本全部相同，且其余发布校验通过
- **THEN** 工作流按该标签的通道创建或更新 Release

#### Scenario: 版本不一致时拒绝发布
- **WHEN** 受支持标签对应的版本与任一项目版本不同
- **THEN** 工作流失败，不创建、不更新 Release，也不改写仓库中的版本号
