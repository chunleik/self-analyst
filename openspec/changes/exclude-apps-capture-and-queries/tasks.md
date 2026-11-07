## 1. 配置与运行时隐私快照

- [x] 1.1 在 `SupportedKeys` 新增 `events.collection.excludeApps`（STRING，默认空）及双语说明，并把 `wiki.privacy.excludeApps`/`excludeSites` 说明改为“对模型隐藏”；`ConfigTest` 断言默认值与 `[events.collection]` 表拍平，`ConfigToolsTest` 断言三个键均被 Agent 配置工具拒绝
- [x] 1.2 新增 `PrivacyRuntime`（采集排除谓词 + 模型可见策略 supplier，原子替换），`Config` 增加 `collectionExcludeApps` 字段；新增 `PrivacyRuntimeTest` 覆盖并集、逗号/分号分隔、空白、大小写与替换后谓词立即变化
- [x] 1.3 `ConfigPolicy` 新增 `PRIVACY` 集合并从需重启中排除；`ConfigApplicationService` 新增 `attachPrivacy`，写入成功后回调并更新 `running`；`ConfigApplicationServiceTest`（或现有配置保存测试）覆盖 raw 保存热应用、不报需重启、校验失败时回调不触发

## 2. 采集层排除

- [x] 2.1 `WatcherManager`/`WindowWatcher` 接收排除谓词：命中时 `collect()` 返回 `null` 并重置连续性；新增 `WindowWatcherTest` 覆盖命中不发事件、切回开始新事件、部分名称不匹配、谓词变化后下一轮生效
- [x] 2.2 `ContentWatcher` 新增带排除谓词的构造器：命中时清空快照、不发 heartbeat、不查询无障碍树；`ContentWatcherTest` 覆盖命中、切回与运行中切换
- [x] 2.3 `AppSession` 创建 `PrivacyRuntime`，传给两个采集器并 `attachPrivacy`；运行 `mvn -pl self-analyst-app -am '-Dtest=ConfigTest,PrivacyRuntimeTest,ContentWatcherTest,WindowWatcherTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 通过

## 3. 模型可见路径改用运行时策略

- [x] 3.1 `WikiFactBuilder`/`WikiWorker`、`SummaryService`、`OntologySources` 增加 `Supplier<WikiPrivacyPolicy>` 构造器并在每次处理开始时取快照；`AppSession`、`DesktopServer` 改为注入 `PrivacyRuntime.modelPolicy()`，缓存指纹改用快照；`WikiFactBuilder` 相关测试或 `SummaryServiceTest` 补充“仅采集排除的历史事件不进入模型输入”和“替换策略后下一次处理生效”回归
- [ ] 3.2 新增 `EventPrivacyFilter`，递归移除数组中 `data` 命中策略的事件对象，非 JSON 输入返回结构化错误；新增 `EventPrivacyFilterTest` 覆盖嵌套数组、`context_title`、`url_host`、`private_browsing`、非事件对象保持与空策略原样
- [ ] 3.3 `EventQueryTools` 接收策略 supplier，在 `queryEvents`、`executeAQL` 成功响应上先过滤再截断，工具描述补充聚合数值说明；`SelfAnalystAgent` 注入运行时策略；`EventQueryToolsTest` 用本地 HTTP 桩验证过滤、预算顺序与两次调用间策略切换
- [ ] 3.4 `DocumentDataSources` 接收策略 supplier，`projection` 导出跳过命中事件并写入 `privacyFilteredCount`；`DocumentDataSourceTest` 断言导出行、`recordCount` 与过滤计数

## 4. 隐私设置接口与页签

- [ ] 4.1 新增 `DesktopPrivacyController` 与路由 `GET`/`PUT /desktop/privacy`：读取两键合成清单（冲突显示为 capture）、近 30 天窗口桶候选（前 100、剔除已在清单、空白与 unknown 排除）、校验与去重后经 `ConfigApplicationService.update` 保存；新增 `DesktopPrivacyControllerTest` 覆盖载荷、候选排序与剔除、非法应用名拒绝、保存后运行策略已更新
- [ ] 4.2 新增 `privacy-settings.js`、`privacy-settings.css`，在 `index.html` 增加“隐私”页签并在 `ui.js` 的 `switchConfigView` 挂载；实现可搜索候选下拉（键盘选择、手动输入）、每行模式下拉与移除、网站 chip、保存提示与脏状态放弃确认
- [ ] 4.3 在 `locales/zh.json`、`locales/en.json` 增加全部文案（含“不采集期间不留本地记录、已生成摘要与会话中已看到内容不追溯”说明）；新增 `src/test/js/privacy-settings.test.mjs` 覆盖渲染、添加/切换/移除、保存载荷、候选失败降级，并确保 `i18n-catalog.test.mjs` 通过

## 5. 文档与验证

- [ ] 5.1 同步更新 `README.md`（英文）、`README.zh-CN.md`（中文）的配置与隐私说明以及 `PRIVACY.md`，介绍隐私页签、两种模式、热生效与不追溯边界；检查完整差异确认英文版无中文段落
- [ ] 5.2 运行 `mvn test` 与 `openspec validate exclude-apps-capture-and-queries --strict`，记录结果；按需截取隐私页签截图供 PR 使用
- [ ] 5.3 使用 `openspec-sync-specs` 将增量同步到 `title-capture`、`event-query-tools`、`agent-document-generation`、`llm-wiki`、`user-configuration` 主规格，并再次严格校验
