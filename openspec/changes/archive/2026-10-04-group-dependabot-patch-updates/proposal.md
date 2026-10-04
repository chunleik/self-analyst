## Why

`main` 的分支保护要求 PR 与最新 `main` 同步。Dependabot 每月同一批会开出多个补丁升级 PR，最先通过检查的那个自动合并后，其余已开启自动合并的 PR 就落后于 `main` 并一直停在等待状态：2026-10-01 这一批里 #87 合并后，#98 与 #92 至今未合并，只能逐个人工评论 `@dependabot rebase`。仓库属于个人账号，无法使用合并队列。

## What Changes

- 在 `.github/dependabot.yml` 中为每个更新项（GitHub Actions、Maven、npm、两个 Cargo 目录）增加仅包含 patch 级版本更新的分组，使同一更新项同一批的补丁升级合并为一个 PR。
- minor 与 major 升级不进入分组，仍各自独立开 PR 并保留人工处理。
- 自动合并的判定口径明确覆盖分组 PR：以 PR 内最高的更新级别为准，全部为 patch 时才启用自动合并。现有工作流读取的元数据输出即为“最高更新级别”，工作流文件无需修改。
- 新增 Node 静态测试，校验每个更新项都配置了仅含 patch 的分组。
- 不改变分支保护、必需检查、合并方式或工作流权限。不同更新项之间仍可能互相造成落后，届时仍需人工触发变基；本变更只把同批 PR 数量降到每个更新项最多一个。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `dependency-auto-merge`：新增“补丁升级按更新项分组”的要求；“仅自动处理 Dependabot 补丁升级”补充分组 PR 的判定场景。

## Impact

- `.github/dependabot.yml`：新增 `groups` 配置。
- `openspec/specs/dependency-auto-merge/spec.md`：同步上述要求。
- `self-analyst-app/src/test/js/`：新增配置静态测试。
- 不涉及应用代码、依赖版本或用户文档。分组效果只能在 Dependabot 下一次按计划运行后观察到。
