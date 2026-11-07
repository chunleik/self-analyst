# 实施与验证记录

Change：`exclude-apps-capture-and-queries`。沿用原功能分支和规划，不归档。

## 实施范围

- 三个隐私键统一经配置提交回调热应用，采集谓词与模型策略共享原子快照。
- 窗口/内容采集在源头排除应用，清空连续性和待重试快照，AFK 与事件服务 heartbeat API 不变。
- Wiki、摘要、模型建议、修复、查询、本体来源及事件投影使用模型隐私策略；本机统计和已有摘要不追溯修改。
- 隐私 API 和中英文页签支持候选/手工输入、两种模式、网站条目、保存反馈及草稿保护。
- 原 `tasks.md` 实际有 16 个可勾选项。没有增加独立 change。

## 逐项状态

- 1.1、1.2、1.3：完成，配置键、快照与统一热应用测试通过。
- 2.1、2.2、2.3：完成，采集排除、重试边界、应用装配与指定回归通过。
- 3.1、3.2、3.4：完成，模型路径、嵌套/展平过滤与投影元数据回归通过。
- 3.3：普通查询与真实 AQL 响应过滤通过；`tag` 覆盖隐私字段的补充仍待确认，保持未完成。
- 4.1、4.2、4.3：完成，API、双语 UI、键盘交互、保存错误/草稿保护及浏览器检查通过。
- 5.1、5.2、5.3：完成，双语 README 与隐私说明、全套测试、截图和五份主规格同步已执行。
  最新提交的必需 CI 全部通过之前，不报告整个任务完成。

## 针对性验证

以下 Maven 命令均在 JDK 21 云端环境执行。环境的裁剪 JDK 缺少 `--release 21` 所需元数据，直接 Maven 首次编译报
`release version 21 not supported`；本机测试使用环境内 Maven 包装器，将编译选项等价设为 `-source 21 -target 21`。
没有修改仓库构建设置或打包；GitHub Windows 检查仍执行原生 JDK 21 的标准 `mvn --batch-mode test`。

- 配置组：48 个 Java 测试通过，覆盖默认键、Agent 不可写、规则并集及热保存。
- 采集组：7 个 events、16 个 content 测试通过，含切回、新规则、待重试事件和采集并发。
- 任务 2.3 指定命令：`mvn -pl self-analyst-app -am '-Dtest=ConfigTest,PrivacyRuntimeTest,ContentWatcherTest,WindowWatcherTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，47 个 Java 测试通过。
- 原模型路径回归：68 个 Java 测试通过；建议/修复扩展回归：66 个 Java 测试通过。
- 事件 JSON 过滤与投影：覆盖嵌套事件、无痕标记、上下文标题、网站、非事件对象、过滤先于预算、导出计数与配置切换。
- 隐私 API 组：27 个 Java 测试通过，包含配置服务、HTTP 路由与控制器校验。
- 隐私 UI 与 i18n：12 个 targeted Node 测试通过；完整 Node 套件 313 passed、32 skipped、0 failed（平台相关跳过）。

## 截图方法

`scripts/check-privacy-ui.py` 在仅环回 HTTP fixture 中加载生产 `desktop-ui` 资源，使用合成应用名和网站，不调用模型，
浏览器测试拦截任何非本地网络请求。覆盖中英文、键盘候选选择、模式切换、网站 chip、放弃确认、保存和
1280×1000、800×600、390×700 三种尺寸。截图不是实际用户数据或桌面后端端到端证据。

云端工程机的 Chromium 无法创建进程单例 socket；获准的提升权限重试仍失败。独立云端浏览器与工程机 localhost
不互通，因此转为 GitHub Actions 独立 Linux UI 作业运行。该作业保留所有原 CI 检查，产物为
`privacy-ui-screenshots`，包含截图及 `validation.json`。最终截图与远端检查结果在 PR 中记录。CI #393 的隐私界面检查已通过，真实 EN/ZH 截图位于 `docs/images/privacy-settings-{en,zh}.png`，来源和哈希记录在同目录的 `privacy-settings-provenance.json`。

## 审查与修复记录

- 用户确认后，在现有规划中补充模型建议证据过滤及单次修复固定策略；实际现行建议通常为通用标签，过滤是对含敏感证据的防御边界，不改本机展示。
- JSON 空响应最初被 Jackson 解析为 MissingNode，已补 fail-closed 回归并修复；无法解析的成功响应不再原样回显。
- 全量测试首轮发现修复并发测试仍覆盖旧单参数事实构造方法，新快照重载导致测试没有进入预期竞争点；修正测试替身，保持竞争断言与超时不变。
- AQL 审查使用实际解释器，发现嵌入式响应为展平行而非 AW 的 `data` 包装；加入真实事件服务 HTTP 集成测试，覆盖普通和命名对象返回，避免仅用错误形状的桩。
- 另外发现 AQL `tag` 可改写隐私识别字段。此项补充确认与最终验证状态以 PR 为准；不能仅凭普通查询测试通过宣称完整隐私边界已验证。

## 与初始设计的差异

- 用户确认的建议模型证据和修复单次快照扩展已先写入原规划，再实施和回归。
- 初始过滤设计只描述 `data` 包装；实际嵌入式 AQL 返回展平的 `timestamp`/`duration` 行。实现补齐 AQL 专用行过滤，
  普通 queryEvents 的非事件对象语义不变；这是为满足原定 AQL 隐私目标所需的响应形状修正。
- 没有新增产品运行时依赖。CI 为真实截图新增固定版本 Playwright 测试依赖和独立 Ubuntu 作业，没有改变原有检查要求。
- 排除切换还需拦截待重试事件并防止旧异步快照恢复，故增加采集连续性/代次防护；通用 heartbeat 协议和 AFK 不变。
- `tag` 的字段覆盖限制及标准 JSON 请求序列化尚未实施。标准序列化需保证验证的查询与服务端实际解码执行的查询一致，
  避免字面量 Unicode 转义在传输中变成被禁止的字段名。该补充待确认后按原 change 更新流程完成。

## 最终检查

- 云端完整 `mvn test`（上述 JDK 编译适配）：1240 个 Java 测试，0 failures、0 errors、7 skipped；Node 313 passed、32 skipped、0 failed；全部 Maven 模块 BUILD SUCCESS。
- `openspec validate exclude-apps-capture-and-queries --strict`：通过。
- `openspec validate --all --strict`：同步后 40 项通过；`openspec validate --specs --strict`：35 份主规格通过。
- GitHub CI #393 的隐私界面验证通过，中英文、键盘、草稿取消、保存和三种尺寸检查完成。
- 后续 CSS 调整后完整 `node --test self-analyst-app/src/test/js/*.test.mjs`：309 passed、32 skipped、0 failed。
  Maven 的目录式 Node 入口另包含 4 个辅助模块加载项，所以该入口报告 313 passed。
- 已检查完整 README 差异，英文版新增内容没有中文说明，中文版对应更新齐全。
- 已同步 `title-capture`、`event-query-tools`、`agent-document-generation`、`llm-wiki`、`user-configuration`。
  保留原主规格的既有场景；配置总述由四页签协调为包含隐私的五页签。未归档。
- AQL tag 字段覆盖限制等待确认，任务 3.3 重新打开；最新提交的 Windows/跨平台最终 CI 仍待完成。

第二轮全量测试发现配置分组数量断言仍为 19；新增采集排除键后应为 20。已修正数量并增加新键及空默认值的明确断言，
9 项配置解析回归与上述最终完整套件通过。第一次远端截图作业未进入浏览器：`ubuntu-latest` 已是 Ubuntu 26.04，
固定 Playwright 1.58.0 不支持该系统。仅将新增作业固定 Ubuntu 24.04，既有 CI 不变。
第二次截图作业的全部交互与尺寸断言通过，最终网络检查识别既有 deep-chat 的可选 Google Fonts 请求，已在发送前拦截。
fixture 现在仅对该精确字体 CSS URL 本地返回空样式，其他外部请求仍拦截并使检查失败，结果记录本地替代。
视觉复查后缩减隐私页纵向留白，并让小尺寸截图先回顶、另附底部视图；第三次截图作业通过。
