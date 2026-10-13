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

## Windows CI 与合成视觉检查（待完成）

现有 `CI / Windows 全量验证` 的 Node 测试入口在 Windows GitHub CI 运行 `checkSummaryRepairUi()`，使用 runner 自带 Microsoft Edge、独立临时 profile、合成本地 HTTP 夹具，不下载浏览器、不访问用户数据、不调用模型、不修改 workflow 或权限。输出截图与 `verification.json` 到忽略的 `artifacts/summary-repair-ui/`；既有 Windows Release 的非 tag 手动构建可通过原 `artifacts/*` 上传步骤保存这些合成截图。

覆盖增强/fallback、中英文、680px 窄窗口、正常摘要/质量失败/额度不足/空草稿本地完整修订/保存冲突与最新状态，另核对看板隐藏固定统计诊断但保留真实 AFK 技术主题与失败原因。

本地环境未完成浏览器视觉检查；需远端 Windows CI 实际通过并逐张检查截图后，补充运行链接与结果，完成任务 4.4、4.5、5.3 和归档。自动化 Edge 检查不替代用户真实 Windows WebView2 人工验收。

## 首轮 Windows 检查发现（2026-10-13）

[CI 38371781180](https://github.com/chunleik/self-analyst/actions/runs/38371781180) 的 Windows 合成 Edge 用例在增强聊天面等待修订卡超时，macOS/Ubuntu 数据守卫通过；该轮不能作为通过证据。调查发现 Deep Chat 后续响应式渲染会替换消息容器，而初次渲染回调和已缓存签名不能恢复修订槽位。修复改为观察容器身份变化后恢复权威会话；普通输入节点变化不重绘。独立审查同时检查瞬时错误草稿恢复引起的响应式反馈，补充去重回归。修复仍需新版 head Windows 真实 Edge 通过。
