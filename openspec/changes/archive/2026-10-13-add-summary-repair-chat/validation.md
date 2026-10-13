# 验证记录

## 范围与测试约束

实现 `add-summary-repair-chat`，基于 `main` 的 `9edb7ba7e68847e5e6e262fa549f49dd0aeedfe8`。所有迁移、修复、聊天与截图数据均为临时合成夹具；未访问用户运行数据库，未调用真实收费模型。

## 本地验证（2026-10-13）

- JDK 21、Maven 3.9.11：`mvn --batch-mode test` 全 reactor 成功，Java 1,109 项（1,105 通过、4 条环境相关跳过、0 失败）；桌面 Node 283 项（252 通过、31 条平台相关跳过、0 失败）。
- 提交前仓库根 `node --test`：292 项（260 通过、32 跳过、0 失败），新增 Windows CI Edge 用例在本地按平台跳过。
- `openspec validate --all --strict`：36/36；`openspec validate --specs --strict`：33/33。
- `git diff --check` 通过，英文/中文 README 对照审阅通过。
- 针对性验证先于全量测试执行：迁移与旧格式准入、schema 4 一致性备份/损坏和过期备份拒绝、事务故障回滚、发布与后台 CAS、幂等重复提交、持久恢复、确认/校验版本推进、精确陈述绑定及脱敏重确认、跨目标/伪造证据拒绝、小时至半天至全天来源传递、语义旧内容失效、父依赖恢复和预算保持。
- 真实 `DesktopServer` HTTP 路由验证无模型的编辑→确认→校验→保存，涵盖 JSON 时间戳序列化、服务端目标/会话权威、版本冲突与重复提交；假模型验证 canonical turn、取消、重复付费请求防护、持久恢复和过期目标在模型准入前拒绝。
- 独立只读审查已复核存储/证据/会话与 UI 边界，无剩余硬阻断；独立执行修复 Node 回归 14/14。

## Windows CI 与合成视觉检查方案

现有 `CI / Windows 全量验证` 的 Node 测试入口在 Windows GitHub CI 运行 `checkSummaryRepairUi()`，使用 runner 自带 Microsoft Edge、独立临时 profile、合成本地 HTTP 夹具，不下载浏览器、不访问用户数据、不调用模型、不修改 workflow 或权限。输出截图与 `verification.json` 到忽略的 `artifacts/summary-repair-ui/`；既有 Windows Release 的非 tag 手动构建可通过原 `artifacts/*` 上传步骤保存这些合成截图。

覆盖增强/fallback、中英文、680px 窄窗口、正常摘要/质量失败/额度不足/空草稿本地完整修订/保存冲突与最新状态，另核对看板隐藏固定统计诊断但保留真实 AFK 技术主题与失败原因。

本地环境未完成浏览器视觉检查；远端 Windows 结果和截图验收见下文。自动化 Edge 检查不替代用户真实 Windows WebView2 人工验收。

## 首轮 Windows 检查发现（2026-10-13）

[CI 38371781180](https://github.com/chunleik/self-analyst/actions/runs/38371781180) 的 Windows 合成 Edge 用例在增强聊天面等待修订卡超时，macOS/Ubuntu 数据守卫通过；该轮不能作为通过证据。调查发现 Deep Chat 后续响应式渲染会替换消息容器，而初次渲染回调和已缓存签名不能恢复修订槽位。修复改为观察容器身份变化后恢复权威会话；普通输入节点变化不重绘。独立审查同时检查瞬时错误草稿恢复引起的响应式反馈，补充去重回归。修复仍需新版 head Windows 真实 Edge 通过。

## 修复后 Windows 必需检查（2026-10-13）

- 提交 `9a2853fabba4cfa4b0e005a1b3e8dc6a4872317a` 的 [CI 38373503017](https://github.com/chunleik/self-analyst/actions/runs/38373503017) 完整成功：Windows 全量验证及 Windows/macOS/Ubuntu 数据守卫全部通过。
- Windows 实际执行 Microsoft Edge 增强和 fallback 冒烟，分别约 10 秒和 22 秒完成合成界面交互；Windows Node 292/292、0 失败、0 跳过。增强场景严格要求 shadow-root 修订卡存在且不能偷偷 fallback；空草稿完整本地路径、校验保存冲突及看板固定诊断过滤均通过。
- 本次修复后的根目录 `node --test`：297 项、265 通过、32 本地平台跳过、0 失败。独立增量审查 33 项相关 Node 回归通过，覆盖响应式回环、旧 observer、fallback、stream 及未发送输入保留。
- Windows 全量同时通过 Java/Node、Rust 测试及 Clippy、合成 UIA 冒烟和打包 JAR 冒烟。本轮未更改 workflow、凭据或权限。
- 截图已在 CI runner 的忽略输出目录生成；现有 CI 没有截图上传步骤，因此尚未下载或逐张检查。不能据此声称完成截图视觉验收；任务 4.4/4.5/5.3 的截图部分继续保留待完成。

## 合成截图验收与发布预演（2026-10-13）

- 在同一提交 `9a2853f` 上运行既有 Windows Release 的非 tag 预演：[38375849500](https://github.com/chunleik/self-analyst/actions/runs/38375849500)，完整成功，包含质量门禁、合成 UIA、便携包/JAR 和 NSIS 安装包校验；没有创建 GitHub Release。
- 已下载并逐张检查原 artifact [SelfAnalyst-Windows-codex-summary-repair-visual](https://github.com/chunleik/self-analyst/actions/runs/38375849500/artifacts/11801629398) 中的 11 张 `summary-repair-ui/*.png`，均为合成活动，未混入用户截图、配置或活动原文。artifact 按仓库既有策略保留 14 天，可由相同测试重现。
- `dashboard-en` / `dashboard-zh-narrow`：固定未识别应用和覆盖不足诊断不显示，真实 AFK detector 主题、质量失败原因、准确失败时段、累计额度与修复入口保留；680px 窄窗没有页面级横向溢出。
- `quality-enhanced-en` / `quality-fallback-zh`：原失败正文缺失提示、本地编辑说明、字段级质量错误、整体/主要主题/任务编辑器清晰，增强面修订卡正常保留。
- `budget-fallback-narrow`：已配置模型但本时段额度耗尽的错误可见，明确仍可本地修复；窄窗会话列表、卡片与输入区未重叠。
- `local-empty` / `local-valid` / `local-saved`：无模型配置时可从空编辑器开始，精确用户陈述确认可见，校验后保存按钮启用，提交后字段只读并显示返回看板入口。
- `save-conflict-narrow` / `conflict-latest`：保留候选输入、禁用过期保存，读取最新状态后显示最新摘要折叠区；冲突错误与读取动作由同轮实际 DOM/接口断言验证，截图核对滚动下部控制区布局。
- `success-enhanced-zh`：中文已保存版本提示、原始目标上下文及只读预览清晰。
- `verification.json` 声明 `syntheticOnly: true` / `modelCalls: 0`，并记录真实 UI 发起的本地 draft→confirm→validate→commit 路由及 conflict/read 流程；所有页面级横向溢出断言通过。
- 用户真实 Windows WebView2 人工验收、真实用户数据升级和真实收费模型均未执行，不能以本次自动化截图代替这些验证。

## 主规格与归档

`desktop-chat`、`desktop-summary`、`llm-wiki`、`summary-repair` 四项主规格已同步，全部 delta Requirement 与 Scenario 存在，旧行为的其他 Scenario 保留。用户指南 `docs/summary-repair.md`、`docs/runtime-storage.md`、双语 README 和 docs 索引继续作为现行用户文档保留；无关历史文档及其他 active change 不动。全部 19 项任务完成后使用 CLI 归档，归档后再次全量严格校验并等待最终提交的远端必需检查。

CLI 已归档为 `2026-10-13-add-summary-repair-chat`；归档后 `openspec validate --all --strict` 为 35/35、`git diff --check` 通过。`--skip-specs` 仅避免再次机械应用已同步并逐项核对的增量，不代表跳过主规格同步。最终提交只包含任务完成、验证记录与归档路径变化，不改变已检查的业务代码和截图脚本。
