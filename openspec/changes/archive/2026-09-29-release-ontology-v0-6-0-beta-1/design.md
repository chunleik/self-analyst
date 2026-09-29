## Context

main 仍需用于 0.5.0。现有 windows-release 工作流支持 beta 标签并强制匹配项目版本，只有标签触发才创建 Release。

## Goals / Non-Goals

**Goals:** 从已同步 main 的 0.6 功能分支发布 0.6.0-beta.1，并保持稳定发布入口及 main 发布线。

**Non-Goals:** 不合并 PR #76，不向 main 提交 0.6 版本，不覆盖已有标签，不降低发布门禁。

## Decisions

统一修改全部项目版本及 Cargo 包锁版本，沿用现有版本校验与 tag 发布工作流。发布标签指向包含版本和规划归档的功能分支提交。版本准备通过本地针对性验证后提交并推送；标签工作流运行完整质量门禁并构建安装包、便携包及校验和，成功才创建 Pre-release。发布完成核对 tag SHA、prerelease=true、附件和 Latest 稳定版。

## Risks / Trade-offs

- main 继续前进 → 发布前拉取并同步一次最新 main，检查标签提交包含该基线。
- 版本清单不一致 → resolve-release-channel 和现有 Node 版本测试验证。
- 预发布取代稳定版 → 使用已有 prerelease 与 latest=false 策略，完成后回读 Release 属性。
- 构建失败 → 查看失败步骤并修复；不移动已发布标签或绕过检查。
