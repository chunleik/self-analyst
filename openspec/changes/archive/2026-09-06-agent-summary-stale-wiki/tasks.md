## 1. 快照存储与当前窗判定

- [x] 1.1 实现摘要快照的原子读写（含 `current`、`timeline`、`behaviorAdvice`、组装时间和当前窗指纹），损坏或缺失视为无快照，并用临时目录测试验证重启后仍能读回、坏文件不使读取抛错。
- [x] 1.2 实现当前窗 / 已结束时段判定（`current`、`today` 未闭合；昨天、前天及 12:00 后的上午已结束），并用固定时钟测试覆盖上午、下午和跨日。
- [x] 1.3 实现当前窗本地事实指纹（排序 topApps、活跃/AFK 时间桶、切换次数桶）与 15 分钟新鲜度常量，并测试相同事实稳定、细微波动落在同一时间桶。

## 2. Wiki 组装与只现算当前窗

- [x] 2.1 按 design 映射从 Wiki store 只读组装已结束条目和未完成跨度（WEEK/BIWEEK/MONTH 优先，否则子级 DAY + 当前窗），PENDING/FAILED/SKIPPED/缺失回退本地事实且不调用 LLM；用夹具 Wiki 数据测试昨天复用 DAY、本周 PENDING 时拼装已完成 DAY。
- [x] 2.2 将 `GET /desktop/summary` 改为「读快照 → Wiki 覆盖已结束条目 → 只查询当前窗事实 → 按指纹/新鲜度决定当前窗 LLM 与建议 → 写回快照」，并测试响应始终包含 `current`、`timeline`、`behaviorAdvice`。
- [x] 2.3 增加回归：已结束时段在重复请求和预算 block 时不再触发 summary LLM；`desktop.summary.maxTimelineLlm=0` 时当前窗不 LLM 但仍返回 Wiki/快照。验证命令：`mvn -pl self-analyst-app -am -Dtest=DesktopAgentController*Summary*,SummaryService*Test,SummaryPrompt*Test -Dsurefire.failIfNoSpecifiedTests=false test`。
- [x] 2.4 确认组装路径不读取无障碍正文或完整配置，快照与响应不含 API key；用现有隐私/内容事件测试或新增断言验证禁止字段未出现。

## 3. 建议复用

- [x] 3.1 让行为建议写入快照，并在当前窗指纹未失效时复用；指纹变化时最多新生成一条。增加测试覆盖「二次请求不生成」和「指纹变化后只生成一条」。
- [x] 3.2 保持建议生成失败不影响 `current`/`timeline`，并运行 `BehaviorAdvice*` 相关测试确认 empty/降级路径仍成功。

## 4. 前端先出快照

- [x] 4.1 调整 `loadAll`：有快照或内存中上次 summary 时立即 `renderBehaviorAdvice` / `renderTimeline`，不得先写回「分析行为数据中…」或「加载中…」；无快照时保留占位。用 `src/test/js` 下 Agent/summary 相关 Node 测试验证。
- [x] 4.2 调整定时刷新：成功前保留已渲染 DOM，切 tab 不重拉全量摘要；增加测试或现有 UI 测试断言刷新失败/进行中不会清空时间轴。

## 5. 规格同步与回归

- [x] 5.1 将本 change 的 delta 同步进 `openspec/specs/` 对应主规格（含新建 `desktop-summary`），并确认 Requirement 名称与 SPEC ID 未改号。
- [x] 5.2 先跑本 change 相关 Java/Node 测试，再按跨模块风险扩大到 `mvn test`；记录命令与结果作为完成证据。
