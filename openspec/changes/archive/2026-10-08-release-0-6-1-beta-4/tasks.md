## 1. 发布基线

- [x] 1.1 核对 PR #111 已合并，main 基线为 88760ce2f65f082d6718369d7c6ff76b7e48c44a；最新正式版为 v0.6.0、最新预发布为 v0.6.1-beta.3，新标签 v0.6.1-beta.4 与同名 Release 不存在。

## 2. 发布准备

- [x] 2.1 同步 13 处项目版本字段为 0.6.1-beta.4；运行 scripts/resolve-release-channel.ps1 -Tag v0.6.1-beta.4 确认输出为 prerelease，审阅完整差异确认没有依赖变化。
- [x] 2.2 新增 docs/releases/v0.6.1-beta.4.md；对照 beta.3 之后的唯一合并记录 88760ce 与 desktop-summary 主规格，核对完整概览、已保存主题明细、空证据隐藏、隐私边界和升级说明。

## 3. 验证与归档

- [x] 3.1 运行 Node 测试、发布通道验证、Java 全量测试、OpenSpec 当前 change 与全量严格校验及 git diff --check；分别记录通过、跳过和环境限制。
- [x] 3.2 确认无主规格行为变化或增量规格需要同步；用 OpenSpec CLI 归档，归档后重新全量严格校验与差异检查。纯版本和说明变更按项目规则不需要设计文档。

## 4. 归档后的交付

提交发布分支并创建草稿 PR；核对最新提交所有必需检查（包括 Windows 全量验证）成功后，按正常保护流程转为可合并并合入 main。新标签 v0.6.1-beta.4 仅指向已验证、已合并的提交，不绕过检查或强制改标签。随后监测现有 Windows Release 工作流至结束，核对标签 SHA、Pre-release 标记、最新正式版仍为 v0.6.0，以及便携包、安装包和两份校验和的实际下载、大小与 SHA-256；检查便携 JAR 中项目模块版本和本次看板展示代码。发布页使用本次中文发行说明。远端交付证据保留在 PR、Actions 与 Release。本准备变更不创建标签、不合并 PR、不创建 GitHub Release。

## 验证记录

- 13 处项目版本解析均为 0.6.1-beta.4；PowerShell 7.6.6 发布通道检查返回 prerelease，逐文件差异仅调整项目自身版本，没有依赖变化。
- 根目录 `node --test`：254 项，223 通过、31 个 Windows 专用用例跳过、0 失败。另用临时副本只解除平台 skip 执行原有发布通道断言，23/23 通过；临时文件已移除，该结果不代替远端 Windows 全量验证。
- 完整 Maven `test`：7/7 模块 BUILD SUCCESS；Java 1033 项、1029 通过、4 跳过、0 失败/错误。Maven 内应用 Node 为 218 通过、31 平台跳过。使用 Ubuntu OpenJDK 21.0.10、Maven 3.9.9 与 Node.js 22.14.0 完成。
- 当前 change 严格校验通过，归档前全量 OpenSpec 严格校验 34/34 通过（含仍在进行的 show-dashboard-overview）；`git diff --check` 通过。
- 无主规格行为变化或增量规格需要同步，纯版本和说明变更省略 design.md；使用 OpenSpec CLI 归档后再次全量严格校验 33/33 通过，`git diff --check` 通过。发布准备不修改已有 UI 实现；原 PR #111 的浏览器布局验证限制仍保留在该 change 记录中。
- 保留 `docs/releases/` 历史发行说明，新增本版本发行说明。活动统计指南只说明统计日口径，没有与本次展示冲突的旧入口，因此未改该指南。远端合并、CI、发布与产物核验结果以对应 PR、Actions 和 Release 为准。
- 未在 Linux 上执行 Windows 全量验证、UIAutomation sidecar 构建、合成 UIA 冒烟，以及 31 个按平台跳过的 Node 用例。本次也不创建标签、不合并、不创建 GitHub Release。
