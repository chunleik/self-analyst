## 1. 发布元数据

- [x] 1.1 统一 Maven、桌面及边车项目版本为 0.2.2，通过版本声明核对与 Maven validate 验证。
- [x] 1.2 新增 docs/releases/v0.2.2.md 并更新架构安装包示例，对照 v0.2.1..main 提交和主规格核对内容。

## 2. 验证与交付准备

- [x] 2.1 运行 openspec validate --all --strict 与 git diff --check，确认没有业务契约变化、无需主规格同步。
- [x] 2.2 完成变更归档及发布 PR 准备；后续交付证据记录在 PR 和 GitHub Release：最新提交 Windows 全量验证成功后合并，再创建 v0.2.2 标签，等待 Windows Release 成功并核对四份发布资产。

验证记录：Maven validate 六个模块全部成功；OpenSpec 严格校验 23 项通过；git diff --check 通过。版本差异仅覆盖项目自身声明，Cargo 第三方依赖未改变。design.md 按条件跳过，无新增设计决策；无增量规格，主规格无需同步。发布交付结果以 PR 和 Release 为准。
