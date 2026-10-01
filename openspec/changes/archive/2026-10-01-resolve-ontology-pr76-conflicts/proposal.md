## Why

PR #76 的本体预发布分支与最新 main 在版本标识及桌面服务接线处发生冲突，无法合并。需要同步 main 并保留双方已实现、已有规格约束的能力。

## What Changes

- 将最新 main 合入 PR 分支，保留本体功能与 main 的仪表盘、语言重启及其他修复。
- Maven、Tauri、Cargo 与 package 版本继续使用 PR 的 0.6.0-beta.1，保留双方其他配置。
- 合并 DesktopServer 的本体资源关闭与仪表盘摘要增强停止逻辑，验证 API、模型热切换和 UI 回归。
- 这是已有实现的分支同步，不新增或修改行为契约；设置 skip_specs: true。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。personal-ontology 及 main 既有主规格继续适用。

## Impact

影响 PR #76 的合并提交、版本配置和 DesktopServer 接线；不发布 tag/release，不合并 PR。保留原工作区未提交的用户手册改动。在隔离工作区运行针对性测试、全量 Maven 测试、版本与 OpenSpec 严格检查后更新原 PR 分支。
