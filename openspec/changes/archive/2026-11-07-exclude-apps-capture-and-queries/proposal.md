## Why

用户希望屏蔽 PotPlayer 等个别应用，但现有 `wiki.privacy.excludeApps` 只拦截 Wiki 摘要和知识图谱：前台窗口与标题仍被完整记录，会话 Agent 通过 `queryEvents`、`executeAQL` 或 `export_data` 仍能读到这些应用的应用名和窗口标题，并发送给模型服务端。该配置也只能手改 `config.toml` 并重启后端。用户需要在界面中为每个应用选择“不采集”或“仅对模型隐藏”，保存后立即生效。

## What Changes

- 新增配置键 `events.collection.excludeApps`（可执行文件名列表，不区分大小写，完整匹配，默认空）。前台为这些应用时，窗口采集器和标题采集器 MUST NOT 发送 heartbeat，本地事件库不产生对应窗口或内容事件；AFK 采集不受影响。
- 会话 Agent 的 `queryEvents`、`executeAQL` 和 `export_data` 的合并事件来源，在把结果交给模型或写入导出文件前，按与 Wiki 相同的隐私策略移除命中排除应用、排除网站或无痕标记的事件。
- Agent AQL 响应过滤覆盖嵌入式服务的展平事件行；执行前拒绝 `tag` 覆盖 `app`、`title`、`context_title`、`url_host` 或 `private_browsing`，避免改写过滤依据。请求采用标准 JSON 序列化，保证检查内容与服务端实际解码内容一致；其他标签及本机 AQL 行为保持不变。
- 对模型隐藏的应用集合定义为 `wiki.privacy.excludeApps` 与 `events.collection.excludeApps` 的并集，Wiki 摘要、知识图谱、看板摘要和 Agent 查询统一使用；行为建议仅过滤发给模型的证据，摘要修复单次请求固定同一份策略。
- 配置弹窗新增“隐私”页签：一张应用清单，通过可搜索下拉添加应用（候选为近 30 天使用过的应用，按时长排序，也可手动输入），每个应用通过模式下拉选择“不采集”或“仅对模型隐藏”，并可移除；另有排除网站的手动输入。新增对应的桌面后端读取与保存接口。
- **行为修改**：`events.collection.excludeApps`、`wiki.privacy.excludeApps`、`wiki.privacy.excludeSites` 改为热生效。无论从隐私页签、高级配置还是结构化接口保存，提交成功后采集与模型可见过滤立即采用新值，不再报告需重启。
- 键名保持不变；`wiki.privacy.*` 的配置说明从“不进入摘要”调整为“对模型隐藏”。三个键仍不加入 Agent 配置工具的可写范围。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `title-capture`: 新增采集层排除应用要求，并规定配置保存后在下一个采集周期内生效。
- `event-query-tools`: 新增事件查询结果的隐私过滤要求，每次调用使用最新保存的策略，并禁止 Agent AQL 覆盖隐私识别字段。
- `agent-document-generation`: 合并事件直接导出按隐私策略过滤，并在元数据中记录过滤条数。
- `llm-wiki`: 摘要输入的排除应用集合扩展为两个配置列表的并集，并对之后生成的摘要热生效；行为建议的模型证据和摘要修复使用同一隐私边界，不改变本机展示或已有摘要。
- `user-configuration`: 新增 `events.collection.excludeApps` 键、三个隐私键的热生效语义，以及隐私页签的交互契约。

## Impact

- 后端：`self-analyst-events` 的 `WindowWatcher`/`WatcherManager`，`self-analyst-content` 的 `ContentWatcher`，`self-analyst-wiki` 的 `WikiPrivacyPolicy`，`self-analyst-app` 的 `Config`、`SupportedKeys`、`ConfigPolicy`、`ConfigApplicationService`、`AppSession`、`DesktopServer`、`EventQueryTools`、`SelfAnalystAgent`、`DocumentDataSources`，以及新增的隐私设置控制器。
- 前端：`desktop-ui` 的 `index.html`、`ui.js`、新增隐私页签脚本与样式、`locales/zh.json`、`locales/en.json`，以及 Node UI 测试。
- 行为：采集排除期间该应用时长不计入本地活动统计；隐藏排除不改变本地统计和看板对用户的展示。AQL 的服务端聚合数值可能包含被隐藏事件的时长，但不暴露应用名或标题。热切换不追溯：已生成的摘要、会话中模型已看到的工具结果和排除期间未采集的数据都不会改变或补回。
- 文档：`README.md`、`README.zh-CN.md`、`PRIVACY.md`。
- 无新增产品运行时依赖，无数据迁移；CI 截图使用独立的 Playwright 测试依赖。
