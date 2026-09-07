## Why

前几批改动已经把 `ActivityWatch` 从 Maven/Java 坐标、采集桶、投影文件名和运行时字符串中去除，但
`2026-09-07-strip-activitywatch-runtime-strings` 当时明确把 `docs/` 与 OpenSpec 主规格排除在外，按
「代码和生成数据不再保留该措辞、文档可保留」的分界收尾。

结果是代码与文档出现措辞分歧：运行时已统一称「事件服务」，而主规格和用户文档仍把本产品自研的
投影层称作 ActivityWatch。读者无法据此判断某处指的是自研实现还是用户接入的第三方实例——而
`THIRD-PARTY-NOTICES.md` 恰恰声明了本产品「不包含 ActivityWatch 源代码」。仓库已转为公开，这种
分歧会直接影响外部读者对项目边界与许可关系的理解，因此本次调整上述分界，把文档与主规格一并统一。

## What Changes

- capability `activitywatch-tools` 重命名为 `event-query-tools`，与已生效的实现类型 `EventQueryTools`
  对齐；七条 SPEC-AW-* requirement 的 ID、正文与场景原样承接。
- 主规格中描述自研投影层的措辞统一为事件服务用语，例如「ActivityWatch 投影」改为「事件投影」、
  「ActivityWatch 时间线」改为「事件时间线」、「嵌入式 ActivityWatch 模式」改为「嵌入式事件服务模式」。
- `docs/architecture.md` 的数据流图与启动顺序、`docs/testing.md` 的集成测试说明中指代自研组件的措辞
  一并统一。
- `docs/README.md` 的「与 ActivityWatch 的关系」整节保留不动，它是有意撰写的第三方关系与许可声明；
  该文件只更新两处因 capability 重命名而失效的 spec 链接与能力表条目。
- 保留三类真实第三方引用：`aw.mode=external` 指代用户自建的 ActivityWatch 实例；`internationalization`
  中的 ActivityWatch 自带 Web UI；`/api/0` 与 `/0` 兼容 endpoint 的协议说明，其中包括 `SECURITY.md`
  中描述兼容接口认证边界的条目。
- 保留元规则条款自身的品牌名。`agent-runtime`、`user-configuration`、`web-search` 中形如
  「MUST NOT 使用 ActivityWatch 品牌名」的约束必须继续引用该词，否则约束无法表达；其守卫测试同样保留。
- 不改标识符：SPEC-AW-* requirement ID、配置键 `aw.*`、HTTP `/api/0`、导入识别前缀 `aw-watcher-*`，
  以及 `THIRD-PARTY-NOTICES.md` 中的许可与兼容性声明。
- 不改归档内容：`docs/archive/` 与 `openspec/changes/archive/` 作为历史记录保持原样。

本 change 不改变任何可观察行为，只统一契约文本的措辞与 capability 归属。

## Capabilities

### New Capabilities

- `event-query-tools`: 承接原 `activitywatch-tools` 的全部行为契约，定义 Agent 通过本地兼容 HTTP API
  列出事件桶、查询投影事件、执行 AQL 和读取服务信息的请求、预算与错误降级行为。

### Modified Capabilities

- `activitywatch-tools`: 整体移除，七条 requirement 迁移至 `event-query-tools`，capability 路径不再存在。
- `content-event-persistence`: 描述标题事实持久化与 Wiki 消费的条款改用事件投影用语。
- `raw-event-retention`: 描述投影提交顺序、查询边界与重建的条款改用事件投影用语；外部模式条款保留
  ActivityWatch，因其指代第三方实例。
- `llm-wiki`: 描述 Wiki 事实来源、投影版本与 summary bucket 权威的条款改用事件投影用语。
- `long-term-memory`: 描述记忆输入边界与禁止自动归纳来源的条款改用事件用语。
- `file-metadata-collection`: 描述文件 heartbeat 投影与工具边界的条款改用事件投影用语。
- `desktop-summary`: 描述时间轴摘要权威的条款改用事件用语。
- `agent-runtime`: SPEC-ARCH-001 的 requirement 名称与场景名改用事件查询用语；品牌名禁令条款本身保留。

## Impact

- `openspec/specs/activitywatch-tools/` 目录移除，新增 `openspec/specs/event-query-tools/`。
- 上述七个既有 capability 的主规格文本，以及 `content-event-persistence` 与 `raw-event-retention`
  的 `## Purpose` 段（delta 不覆盖 Purpose，需直接编辑主规格）。
- `docs/architecture.md`、`docs/testing.md`，以及 `docs/README.md` 中的两处 capability 链接。
- 不涉及 Java、Rust、JavaScript 源码与测试：代码侧品牌名已在前几批清理完毕，仅存的引用位于
  `AgentPromptsTest`、`TomlSupportTest`、`DesktopConfigControllerTest`、`ProjectionRebuildServiceTest`
  中断言「不得包含该品牌名」的守卫断言，必须保留。
- 不涉及数据库、配置文件与 HTTP 接口，用户无需迁移。
