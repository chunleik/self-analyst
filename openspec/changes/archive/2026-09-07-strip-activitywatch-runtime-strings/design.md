## Context

见 `proposal.md`。前两批已改标识与代码坐标。本次只替换本产品源码与运行时字符串中的完整单词 `ActivityWatch`。

## Goals / Non-Goals

**Goals:**

- 本产品 `src/main`（不含 vendored webui）不再出现 `ActivityWatch` / `activitywatch`。
- Agent 提示、config.toml 模板说明、日志与异常改用事件服务用语。

**Non-Goals:**

- 不改 `aw.*` 配置键、Java 访问器 `awPort()` / `awDataDir`、导入常量 `aw-watcher-*`。
- 不改 `self-analyst-events/src/main/resources/webui/`。
- 不改 `docs/`、`openspec/specs/` 中未列入本 change delta 的协议说明用语（归档时只同步三条 delta）。
- 不改测试里用于导入兼容的 `aw-watcher-*` 夹具。

## Decisions

### 1. 只清完整品牌词，保留 `aw` 缩写标识

`aw.port` 等键受 `SPEC-TOML-NON-001` 约束。字段名 `awDataDir` 与键对齐，不是展示文案。导入仍必须匹配外部 `aw-watcher-*`。

备选：连 `aw.*` 一起改，会再开一轮配置 breaking，超出本次范围。

### 2. 提示词替换表

| 现用 | 改为 |
|---|---|
| ActivityWatch 数据 / ActivityWatch data | 事件数据 / event data |
| ActivityWatch 工具 / ActivityWatch tools | 事件查询工具 / event query tools |
| ActivityWatch 存储的时间戳 | 事件服务存储的时间戳 |
| ActivityWatch / Wiki tools | 事件查询 / Wiki 工具 |

### 3. 内嵌 webui 原样保留

它是兼容 HTTP 的第三方界面，标题无法在不重做 UI 的情况下单独中性化。用户打开 `http://localhost:<aw.port>/` 仍会看到 ActivityWatch，这是明确接受的例外。

## Risks / Trade-offs

- [测试仍断言旧提示词] → 同步 `AgentPromptsTest` 与配置模板测试。
- [漏改一处 Java 字符串] → 在 `src/main` 排除 `webui/` 后搜索 `ActivityWatch`。
- [Agent 仍把工具想成 ActivityWatch] → 提示词与 `@Tool` 描述已在前一批改为事件桶用语，本次清系统提示即可对齐。

## Migration Plan

无数据迁移。已有用户 `config.toml` 里的旧注释不会自动改写，仅空文件模板与新生成模板使用新说明。
