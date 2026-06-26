# SelfAnalyst 斜杠命令：SDD 命令流

一套贴合本项目规格驱动开发约定的命令，思路对齐 GitHub spec-kit 的
`specify → plan → tasks → implement` 四阶段，但**完全使用本项目的路径、ID 规约和
追溯矩阵**，不引入 spec-kit 的 `specs/` / `memory/constitution.md` 目录。

约定来源：`docs/specs/README.md`、根 `CLAUDE.md` 的 SDD Index。

## 命令

| 命令 | 对应 spec-kit | 作用 | 产物 |
|------|--------------|------|------|
| `/sdd-spec <名称>` | `/specify` | 新建/精修正式 spec，分配 Key Prefix，登记索引 | `docs/specs/<slug>.md` + `CLAUDE.md` 索引 |
| `/sdd-plan <slug>` | `/plan` | 为 spec 写实现/构建计划（Goal/Arch/File Map/Task） | `docs/superpowers/plans/<日期>-<slug>.md` |
| `/sdd-tasks <slug>` | `/tasks` | 把 plan 的 Task 细化为带 SPEC ID 的 `- [ ]` Step | 同一 plan 文件 |
| `/sdd-implement <slug> [Task n]` | `/implement` | 按 Step 实现、跑验证、维护追溯矩阵、按约定提交 | 源码 + 更新后的追溯矩阵 |

## 典型流程

```
/sdd-spec 离线导出能力        # 起草 spec，得到 SPEC-EXPORT-*
/sdd-plan offline-export      # 写实现计划（File Map + Task）
/sdd-tasks offline-export     # 把 Task 细化为可执行 Step，逐条挂 SPEC ID
/sdd-implement offline-export # 实现 + 验证 + 更新追溯矩阵
```

## 与本项目约定的对齐点（与 spec-kit 的有意差异）

- **spec** 落 `docs/specs/`，套 README §4 骨架；**plan** 落 `docs/superpowers/plans/<日期>-<slug>.md`，
  套现有 `2026-06-08-behavior-advice.md` 范本。
- ID 形如 `SPEC-<域>-<子域>-NNN`，只增不改；spec 末尾**三列追溯矩阵**
  `| 规格 ID | 目标文件/组件 | 验证方式 |`——可追溯性强于 spec-kit 原生。
- tasks 不另建文件，并入 plan 的 Task/Step 结构，避免目录碎片。
- 不用 `memory/constitution.md`——本项目的「写规格宪法」就是 `docs/specs/README.md`。
