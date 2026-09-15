# 验证记录

## 实施结果

共享统计日历以本地04:00切日；区间相交查询在同一只读事务内读取window/AFK，聚合按实际交集扣AFK并集。
桌面、本地行为比较与Wiki使用共享算法，未识别活动独立展示。Wiki派生库schema 4保留旧版条目并隔离查询，
迁移失败回滚且关闭连接；历史发现后台执行并持久化进度。快照版本、时区和统计日起点均参与兼容或刷新判断。

## 自动验证

- JDK 21，`mvn test -q`：成功。全量Java共745项，失败0、错误0、条件跳过6；桌面Node 127项全部通过。
- 全量完成后新增04:00切换时指标相同的文案失效回归：`mvn -pl self-analyst-app -am '-Dtest=DesktopSummaryAssemblerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test -q` 成功，附带Node 127项通过。
- 核心回归包含跨04:00、月首/周一/闰日/夏令时，完全/部分AFK及重复区间、未知与缺失覆盖、小数秒累计、桌面与Wiki同源一致性、凌晨娱乐日归属。
- 查询夹具超过50000条；统计无截断，SQL计划使用 `idx_events_bucket_timestamp`。该测试（含造数及断言）约0.53秒；候选流式扫描，相交结果和聚合扫描线按相交记录数占用内存，未执行长期生产数据性能基准。
- Wiki夹具超过2000条；精确指标在父级聚合时保留小数，不因单条取整或子级top10截断丢失耗时。
- 迁移覆盖旧版字段/正文保留、同小时不同版本并存、备份schema 3、失败回滚、连接释放、默认查询/语义检索旧版隔离、后台发现重启续算及事件记录不变。
- `openspec validate fix-activity-statistics-day-boundary --strict` 成功；`openspec validate --specs --strict`：26项通过。
- `git diff --check` 通过；README中英文完整差异已检查，新增英文段落未混入中文。

本地完整日志位于被忽略的 `target/activity-full-test-final.log`、`target/activity-targeted-final.log`、
`target/activity-boundary-refresh-test.log`，不提交运行日志或用户数据。

## 本机只读复核

使用SQLite `mode=ro` 和只读事务，对04:00至次日04:00的固定历史区间独立重算：未知窗口先裁剪，
再扣非活跃交集；未识别有效活动约16分34秒。此结果与之前午夜口径的16分37秒不同，不能复用旧口径期望值。
未修改原始事件、用户数据库、当前运行应用或运行快照；不提交主机名、窗口标题或事件样本。

## 界面与文档

使用生产 `agent.js`、`styles.css` 和浏览器语言目录渲染匿名固定夹具，并在浏览器检查中英文详情。
确认未知活动、小于一分钟的时长、AFK覆盖不足说明及追问按钮可见，无横向溢出。

- [中文详情截图](../../../../docs/screenshots/activity-statistics-zh.png)
- [英文详情截图](../../../../docs/screenshots/activity-statistics-en.png)

截图用于展示匿名测试场景，不代表用户真实统计结果。`docs/activity-statistics.md` 保留为用户说明，
包含04:00口径、迁移/备份/恢复和历史补算设置；没有新增需要迁移的历史文档。

## 交付状态

功能分支：`codex/fix-activity-statistics-day-boundary`。本轮完成本地实施、测试、主规格同步与change归档。
尚未提交、推送、创建PR或部署；后续交付需按仓库规则验证PR最新提交的必需检查，包括Windows全量验证。
