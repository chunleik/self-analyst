## 1. 版本准备

- [x] 1.1 将指定 13 个文件中的自身版本从 0.6.1-beta.9 同步为 0.6.1-beta.10；逐文件核对仅一处自身版本变化、Cargo.lock 无依赖变化，并用 resolve-release-channel.ps1 验证 prerelease。
- [x] 1.2 新增 docs/releases/v0.6.1-beta.10.md；与 ChatGPT 功能说明和验证记录交叉核对，保留预览资格、未加密凭据、独立 Embedding、schema 5 完整备份/回退及未完成验收警告。
- [x] 1.3 执行 OpenSpec 全量严格校验、元数据解析与 git diff --check；准确记录本次检查和已有测试的范围，不把尚未运行的精确 head Windows CI 或 tag 发布门禁标为通过。

## 范围说明

本 change 使用 skip_specs: true，仅准备版本元数据和发行说明，不新增行为契约，无需同步主规格；没有新增设计决策，按 CLI 条件规则不创建 design.md。功能 change add-chatgpt-subscription-provider 继续保持活动，不以版本准备归档代替真实账户、原生界面、Windows 权限或安装验收。

## 后续发布门禁

版本准备完成不等于发布完成。发布任务负责提交/推送功能分支、创建 PR、等待精确 head 必需检查通过后按授权合并，再确认新标签未占用并执行 tag 发布流程。保持 Pre-release 和非 latest，v0.6.0 稳定版不变；发布前核验分发资产与 SHA-256。所有尚未完成的检查必须继续如实披露。

## 验证记录

- 2026-11-06：指定 13 个文件逐字比较确认各自仅一处 beta.9→beta.10，自身版本外无变更；XML、JSON 与 TOML 解析均通过，两个 Cargo.lock 无依赖改动。
- 原 PowerShell `scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.10` 输出 `prerelease`。默认缓存目录只读导致首次 PowerShell 启动失败后，将临时缓存/配置放入 `/tmp` 重跑成功，未改变仓库脚本。
- `openspec validate --all --strict --no-interactive`：40 项通过、0 失败；工作区和已暂存差异的 `git diff --check` 均通过。
- 版本准备后重跑三组针对性 Node：37 项全部通过；在 `self-analyst-app` 执行 `node --test`：332 项，300 通过、32 跳过、0 失败。并行整理 Rust 格式期间曾遇到单行源码匹配断言失败，相关功能任务改为跨空白匹配后以上两轮实跑通过，未放宽反斜杠字节检查。
- 发行说明中的 Java 1,173 项/1,169 通过/4 跳过引用功能 change 已有记录；本版本准备未重复执行 Java，明确其临时 source/target wrapper 限制，不声称原样 --release、Windows、真实账户或安装验收通过。
- 已核对发行说明包含预览资格、未加密权限保护凭据、独立 API Key/Embedding、schema 5 完整备份与不可无备份降级警告。版本 change 无新行为契约，不需主规格同步；ChatGPT 功能 change 保持活动。
- 仅版本准备 change 已通过 CLI 归档；CLI 使用的归档日期为 2026-11-07，目录为 `openspec/changes/archive/2026-11-07-release-0-6-1-beta-10/`。归档后全量严格校验 39 项通过、0 失败，差异检查通过。`docs/releases/v0.6.1-beta.10.md` 作为用户向发行说明保留，发布任务后续补充本次精确提交与 tag 门禁的实际结果，不覆盖尚未完成的真实账户/原生验收边界。
