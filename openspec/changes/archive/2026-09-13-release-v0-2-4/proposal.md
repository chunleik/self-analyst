## Why

将已合入 main 的 Windows 安装启动路径修复交付为 v0.2.4，使用户能够通过正式分发包解决 v0.2.3 的启动失败。

## What Changes

- 统一 Maven、桌面应用及两个 Rust 工程版本为 0.2.4。
- 编写中文发布说明，更新架构文档安装包示例，并纠正发布工作流说明中的免安装数据目录描述。
- 经功能分支 PR 和必需检查交付，合并后推送 v0.2.4 标签，等待正式发布工作流成功并核对资产。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。只更新版本及发布文档，行为修复及主规格已由 fix-installed-java-path 交付，设置 skip_specs: true。

## Impact

涉及项目版本声明、Cargo 锁文件中的项目版本、发布说明、架构示例和工作流的发布说明文本；不修改依赖或业务行为。
