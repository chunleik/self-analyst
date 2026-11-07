## 1. 准备

- [x] 1.1 从最新 `main` 创建 `codex/improve-knowledge-tab-ux` 分支，并通过 `git status` 确认工作区中无关的修改保持不变
- [x] 1.2 在 `DesktopOntologyController`/服务中核对关系项 `other` 字段是否包含 `type`；若不包含，先用 `openspec-update-change` 调整范围再继续，并在本任务中记录核对结论。核对结果：`DesktopOntologyController` 直接返回 `OntologyService.detail()`；`Ontology.Relation.other` 为 `Entity`，其 record 明确定义 `type`，可直接按对端类型分组，无需修改后端

## 2. 术语与文案

- [x] 2.1 在 `locales/zh.json` 与 `locales/en.json` 中新增断言状态短标签、句子化关系名称（`ontology.relationLabel.*`）、分组标题、“新建”与“更多”菜单、时间预设、“待整理”、“重新自动匹配”及其确认说明、时间过滤提示、删除确认对话框等文案，并移除不再使用的键；运行 `node --test self-analyst-app/src/test/js/ontology.test.mjs`，确认双语键测试通过

## 3. 页头、过滤与状态

- [x] 3.1 在 `ontology.js` 中把页头改为“新建 ▾”（项目/主题）与“更多 ⋯”（重新自动匹配）两个可访问菜单，重建前通过应用内对话框确认；补充 Node 测试，覆盖菜单的 ARIA 属性、Esc 关闭以及取消时不发请求
- [x] 3.2 实现防抖即时搜索（回车立即触发）并移除搜索按钮；新增“待整理”快捷筛选与时间预设（全部/近 7 天/近 30 天/自定义），并补充测试：防抖后只发最终请求、旧结果不覆盖新结果、预设生成正确的 `start`/`end`
- [x] 3.3 覆盖信息仅在 `unavailable`/`truncated` 时以警告显示，周期说明改为单一的按需提示；补充测试，验证来源均正常时不渲染覆盖文字

## 4. 详情页

- [x] 4.1 重组详情标题区：类型徽标、名称、只读标记、描述、别名标签；编辑与添加关系作为主要操作，合并与删除放入“更多”菜单，删除改用应用内确认对话框；更新相关测试并保留“恶意名称按文本渲染”测试
- [x] 4.2 关系按对端类型分组并显示数量，使用句子化关系名称和状态图标加短标签（已确认/自动推测/自动记录/待确认），确认操作只出现在未确认关系上；补充测试，覆盖推断关系、已确认关系、候选关系三种场景
- [x] 4.3 列表时间过滤生效时，详情显示过滤范围提示和清除入口，清除后按全部时间重新加载；补充对应测试
- [x] 4.4 关系确认或否定后恢复列表与详情的滚动位置；更新“relation confirmation sends explicit endpoints and refreshes”测试，断言滚动位置被保留

## 5. 目标选择器与样式

- [x] 5.1 用即时搜索组合框（`role="listbox"`、`aria-activedescendant`、方向键加回车选择、滚动加载更多）替换 `select size=5` 选择器，并测试合并与添加关系两种表单都能用键盘完成选择和提交
- [x] 5.2 在 `ontology.css` 中为菜单、组合框、分组、状态标记、各类型徽标和警告条添加样式，只使用已定义的设计令牌；运行 `node --test self-analyst-app/src/test/js/ontology-style.test.mjs` 并确认通过（包括“每个 knowledge 类都有样式规则”）

## 6. 文档与验证

- [x] 6.1 更新 `docs/personal-ontology.md` 中的界面用语（“待整理”“重新自动匹配”等）；用 `grep` 确认 README.md / README.zh-CN.md 没有引用旧文案，或同步更新这两个文件
- [x] 6.2 运行 `mvn -pl self-analyst-app -am '-Dtest=DesktopOntologyHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 与 `mvn test`，确认全部通过
- [ ] 6.3 用 `pnpm tauri dev` 启动应用，在浅色/深色和窄窗口下手动检查知识页，截图附到 PR（跳过：当前云端缺少 Cargo，Tauri 无法启动；Chromium 创建 socket 也受环境限制。用户已同意不修改 workflow、提交不带截图的 PR；保留可复现的 `scripts/check-knowledge-ui.py`，未声称浏览器验收通过）
- [x] 6.4 运行 `openspec validate improve-knowledge-tab-ux --strict`，确认通过


## 验证记录

- 本地针对性 Node 检查：28 项全部通过。完整 Maven：1275 项 Java 测试，失败/错误 0，按平台跳过 7；360 项 Node 测试，失败 0，按平台跳过 32。
- 本地精简 JDK 缺少 `--release 21` 支持；上述 Maven 结果使用现有 JDK 21 `-source 21 -target 21` 适配器和隔离依赖目录。`DesktopOntologyHttpTest` 2 项通过，原生 Windows 全量检查仍需在 PR 最新提交上验证。
- `openspec validate improve-knowledge-tab-ux --strict` 通过；`openspec validate --all --strict` 40 项通过。已使用同步流程更新主规格，未归档。
- 未修改后端、依赖、README 或 GitHub workflow；原有英文/中文 README 未引用已替换的界面文案。
- 共享主题目前只有浅色令牌定义；浏览器脚本会忠实记录深色系统偏好下仍保持浅色的现状，不注入自造深色主题。

## 合并、发布与归档核对（2026-11-07）

- 实现 PR [#148](https://github.com/chunleik/self-analyst/pull/148) 已于 2026-11-07 合并，合并提交为 55762027bf1cd10122da67e6a891711b7abdb305；最新 head 6880415 的五项 CI 全部通过，包含 Windows 全量验证。
- v0.6.1-beta.14 已发布；[该 tag 的独立 Windows Release](https://github.com/chunleik/self-analyst/actions/runs/42374710702) 全部通过：OpenSpec 41 项，Java 1,275 项（0 失败、0 错误、7 跳过），Node 360 项全部通过，Tauri Rust 49 项及桌面端/边车 Clippy 通过，UIA、JAR 与 NSIS 安装/重装/启动/卸载验证通过。四个公开资产已独立核对 SHA-256、大小与包内版本，关键前端资源与源码一致。
- 主规格 personal-ontology 中 SPEC-ONTO-009 与 SPEC-ONTO-013 已逐项核对，与本 change 增量完全一致；归档不重复改写主规格。
- 用户已在发布后明确要求归档、push 并合并。任务 6.3 继续保留为已获同意跳过的人工验收，不将其标成通过；归档不会补造截图或改变已披露限制。
- docs/personal-ontology.md 保留为现行用户指南；docs/releases/v0.6.1-beta.14.md 保留为版本准备时的历史发行说明，发布页补充实际验证结果。本次不移动或删除这些文档，不归档其他 change，不再次发版。
