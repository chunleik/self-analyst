# SelfAnalyst 过往行为建议/鼓励显示框 SDD 规格说明书

> Specification-Driven Development spec. 本文档定义桌面端“基于过往行为的建议/鼓励显示框”的行为契约。实现必须可追溯至本文档中的规格 ID。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 功能名称 | 过往行为建议/鼓励显示框 |
| 文档状态 | 已实现（当前契约） |
| 日期 | 2026-06-08 |
| 目标入口 | SelfAnalyst 桌面端 `Agent` tab |
| 主要前端入口 | `self-analyst-app/src/main/resources/desktop-ui/index.html` |
| 主要前端逻辑 | `self-analyst-app/src/main/resources/desktop-ui/agent.js`, `ui.js`, `api.js`, `state.js` |
| 主要样式文件 | `self-analyst-app/src/main/resources/desktop-ui/styles.css` |
| 后端入口 | `GET /desktop/summary` |
| 规格前缀 | `SPEC-ADV-*` |

---

## 2. 实施前背景与当前定位

实施本特性前，`Agent` tab 已显示当前状态、时间轴和未来任务，但没有基于过往行为的建议卡片。
本特性增加一个轻量显示框，用于呈现系统生成的一条建议、鼓励或提醒。

该功能只负责展示“系统观察到什么，以及建议用户如何延续或调整”。它不是目标管理页，不负责管理 `Goal`、`KnownPattern` 或 `ImprovementLog`，也不自动创建任务。

---

## 3. 目标

- **SPEC-ADV-GOAL-001**: 桌面端必须展示一个基于过往行为生成的建议/鼓励显示框。
- **SPEC-ADV-GOAL-002**: 显示框必须能表达三类语气：`鼓励`、`建议`、`提醒`。
- **SPEC-ADV-GOAL-003**: 显示内容必须包含可解释证据，不得只输出空泛鼓励。
- **SPEC-ADV-GOAL-004**: 数据不足或 LLM 不可用时必须有清晰降级状态，不得编造行为结论。
- **SPEC-ADV-GOAL-005**: 首版必须是只读显示组件，不自动写入记忆、不自动创建任务、不自动确认模式。

---

## 4. 非目标

- **SPEC-ADV-NON-001**: 首版不新增目标管理功能。
- **SPEC-ADV-NON-002**: 首版不新增模式确认功能。
- **SPEC-ADV-NON-003**: 首版不新增改进记录管理功能。
- **SPEC-ADV-NON-004**: 首版不新增通知、弹窗或系统托盘提醒。
- **SPEC-ADV-NON-005**: 首版不显示多条建议流，只展示一条最重要建议。
- **SPEC-ADV-NON-006**: 首版不允许用户对建议点赞、点踩或训练模型。

---

## 5. 信息架构

### 5.1 入口位置

- **SPEC-ADV-IA-001**: 建议/鼓励显示框必须放在 `Agent` tab 内。
- **SPEC-ADV-IA-002**: 在桌面宽屏布局中，显示框位于当前状态卡片和时间轴上方。
- **SPEC-ADV-IA-003**: 显示框不得新增顶层 tab。
- **SPEC-ADV-IA-004**: 显示框不得替代现有当前状态卡片、时间轴或未来任务面板。

推荐结构：

```html
<section id="behavior-advice-card" class="behavior-advice-card">
  ...
</section>
```

### 5.2 显示信息

显示框必须包含：

| 区域 | 内容 |
|------|------|
| 类型标签 | `鼓励` / `建议` / `提醒` |
| 时间范围 | 如 `基于最近 7 天` |
| 更新时间 | 如 `刚刚更新` 或 `14:30 更新` |
| 主结论 | 一句话说明系统观察到的行为变化或建议 |
| 解释正文 | 简短说明依据和下一步建议 |
| 证据标签 | 2-5 个短标签，如 `晚间视频时长下降` |
| 依据摘要 | 观察范围、变化方向、建议类型、数据完整度 |

---

## 6. 数据模型

### SPEC-ADV-MDL-001: BehaviorAdvice

前端和后端通过以下 JSON 结构传递建议显示数据：

```json
{
  "type": "encouragement",
  "scopeLabel": "最近 7 天",
  "generatedAt": "2026-06-08T06:30:00Z",
  "title": "你这几天晚间分心有所减少，继续保持 22:00 后的轻量收尾节奏。",
  "body": "最近 3 天，22:00 后视频类应用时长比上周同期少了约 35 分钟；第二天上午的活跃时长也更稳定。",
  "evidenceTags": ["晚间视频时长下降", "上午活跃更稳定", "窗口切换减少"],
  "basis": {
    "observationRange": "7 天",
    "trend": "改善",
    "adviceKind": "保持策略",
    "dataCompleteness": "中"
  },
  "confidence": "medium",
  "emptyReason": null
}
```

### SPEC-ADV-MDL-002: type 枚举

`type` 合法值：

| 值 | UI 文案 | 使用场景 |
|----|---------|----------|
| `encouragement` | 鼓励 | 近期行为呈现改善趋势 |
| `suggestion` | 建议 | 发现可调整空间但不构成明显偏离 |
| `reminder` | 提醒 | 近期行为偏离目标或历史稳定状态 |
| `empty` | — | 数据不足、LLM 不可用且本地规则也无法生成 |

### SPEC-ADV-MDL-003: 字段约束

- `title` 必须为非空字符串，推荐不超过 80 个中文字符。
- `body` 必须为非空字符串，推荐不超过 240 个中文字符。
- `evidenceTags` 最少 1 个，最多 5 个；`empty` 状态允许为空。
- `confidence` 合法值为 `high`, `medium`, `low`。
- `basis.dataCompleteness` 合法 UI 文案为 `高`, `中`, `低`。
- `emptyReason` 仅在 `type = "empty"` 时出现。

---

## 7. 数据来源和生成规则

### 7.1 数据来源

- **SPEC-ADV-SRC-001**: 建议可使用窗口事件、AFK 事件、任务摘要和 Wiki 摘要作为输入。
- **SPEC-ADV-SRC-002**: 如果内容采集数据可用，可使用经过裁剪和摘要后的 UIA/OCR 内容片段作为辅助输入。
- **SPEC-ADV-SRC-003**: 输入 LLM 的原始文本必须有长度上限，不得无界拼接 OCR/UIA 原文。
- **SPEC-ADV-SRC-004**: 输出证据不得包含完整 OCR/UIA 原文。

### 7.2 生成策略

- **SPEC-ADV-GEN-001**: 首版每次只返回一条最重要建议。
- **SPEC-ADV-GEN-002**: 如果 LLM 可用，后端可用 LLM 将本地事实转成自然语言建议。
- **SPEC-ADV-GEN-003**: 如果 LLM 不可用，后端必须使用本地规则生成朴素建议或返回空状态。
- **SPEC-ADV-GEN-004**: 如果数据不足，必须返回 `type = "empty"`。
- **SPEC-ADV-GEN-005**: 本地规则至少应支持以下场景：
  - 近几天分心类应用时长下降 → `encouragement`
  - 窗口切换次数显著升高 → `suggestion`
  - 连续多天晚间娱乐时长偏高 → `reminder`
- **SPEC-ADV-GEN-006**: LLM 输出不得覆盖本地计算的事实指标，只能负责措辞和归纳。

---

## 8. API 契约

### SPEC-ADV-API-001: 复用 `/desktop/summary`

首版不新增接口。`GET /desktop/summary` 响应必须增加 `behaviorAdvice` 字段：

```json
{
  "behaviorAdvice": {
    "type": "encouragement",
    "scopeLabel": "最近 7 天",
    "generatedAt": "2026-06-08T06:30:00Z",
    "title": "...",
    "body": "...",
    "evidenceTags": [],
    "basis": {},
    "confidence": "medium",
    "emptyReason": null
  },
  "current": {},
  "timeline": []
}
```

- **SPEC-ADV-API-002**: `behaviorAdvice` 字段必须始终存在。
- **SPEC-ADV-API-003**: 生成失败时不得让 `/desktop/summary` 整体失败；必须返回 `type = "empty"` 或本地降级建议。
- **SPEC-ADV-API-004**: 前端必须兼容 `behaviorAdvice = null` 的旧响应，显示空状态。

---

## 9. 前端行为规格

### SPEC-ADV-UI-001: 默认显示

打开 `Agent` tab 后，如果 `behaviorAdvice.type !== "empty"`，必须显示建议卡片。

### SPEC-ADV-UI-002: 空状态

如果 `behaviorAdvice.type = "empty"`，必须在同一位置显示空状态：

```text
还没有足够行为数据生成建议。继续使用一段时间后，这里会出现基于过往行为的提醒或鼓励。
```

空状态不得显示伪造证据标签。

### SPEC-ADV-UI-003: 卡片内容

卡片必须显示：

- 类型标签
- 时间范围
- 主结论
- 解释正文
- 证据标签
- 依据摘要

### SPEC-ADV-UI-004: 类型视觉

| type | 视觉倾向 |
|------|----------|
| `encouragement` | 绿色或蓝绿，表达改善和保持 |
| `suggestion` | 蓝色，表达可操作建议 |
| `reminder` | 暖色，表达克制提醒 |
| `empty` | 中性灰，不显示警告样式 |

### SPEC-ADV-UI-005: 响应式

- 宽屏下，正文和依据摘要可左右排列。
- 窄屏下，依据摘要必须折到正文下方。
- 不允许正文、标签或指标互相重叠。

### SPEC-ADV-UI-006: 无写入动作

首版卡片不得显示以下按钮：

- `转为任务`
- `转为改进记录`
- `确认模式`
- `保存到记忆`

---

## 10. 错误处理和降级

- **SPEC-ADV-ERR-001**: LLM 请求失败时，必须回退到本地规则或空状态。
- **SPEC-ADV-ERR-002**: AW bucket 缺失时，不得让页面报错。
- **SPEC-ADV-ERR-003**: Wiki 摘要缺失时，不得阻塞建议生成。
- **SPEC-ADV-ERR-004**: 前端渲染时必须对文本做 HTML 转义。
- **SPEC-ADV-ERR-005**: 建议生成错误不得影响当前状态卡片和时间轴显示。

---

## 11. 隐私和安全

- **SPEC-ADV-PRV-001**: 卡片不得展示完整 OCR/UIA 原文。
- **SPEC-ADV-PRV-002**: 卡片不得展示 API Key、base URL 或用户配置敏感字段。
- **SPEC-ADV-PRV-003**: 发送给 LLM 的 prompt 必须使用裁剪后的事实摘要。
- **SPEC-ADV-PRV-004**: 日志不得输出完整 prompt 或完整用户屏幕文本。

---

## 12. 实现文件和修改范围

### 12.1 预计修改文件

- `self-analyst-app/src/main/java/com/selfanalyst/desktop/controller/DesktopAgentController.java`
- `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryService.java`
- `self-analyst-app/src/main/java/com/selfanalyst/desktop/service/SummaryPromptService.java`
- `self-analyst-app/src/main/resources/desktop-ui/index.html`
- `self-analyst-app/src/main/resources/desktop-ui/state.js`
- `self-analyst-app/src/main/resources/desktop-ui/agent.js`
- `self-analyst-app/src/main/resources/desktop-ui/styles.css`
- `self-analyst-app/src/test/java/com/selfanalyst/desktop/service/*`
- `self-analyst-app/src/test/java/com/selfanalyst/desktop/controller/*`
- `scripts/check-desktop-behavior-advice.ps1`（可选静态检查）

### 12.2 不应修改的文件

首版不需要修改：

- `self-analyst-app/src/main/java/com/selfanalyst/memory/GrowthProfile.java`
- `self-analyst-app/src/main/java/com/selfanalyst/desktop/store/TaskStore.java`
- `self-analyst-wiki` 数据库 schema
- `self-analyst-desktop/src-tauri/*`

---

## 13. 测试规格

### 13.1 后端测试

- **SPEC-ADV-TST-001**: LLM 可用时，`/desktop/summary` 返回 `behaviorAdvice` 字段。
- **SPEC-ADV-TST-002**: LLM 不可用时，`behaviorAdvice` 返回本地规则建议或空状态。
- **SPEC-ADV-TST-003**: 数据不足时返回 `type = "empty"`。
- **SPEC-ADV-TST-004**: 建议生成异常时，`current` 和 `timeline` 仍正常返回。
- **SPEC-ADV-TST-005**: 输出不包含完整 OCR/UIA 原文。

### 13.2 前端静态检查

必须检查：

- `index.html` 包含 `id="behavior-advice-card"` 或等价容器。
- `agent.js` 包含 `renderBehaviorAdvice`。
- `styles.css` 包含 `.behavior-advice-card`。
- 前端使用 `escHtml()` 渲染建议文本。

### 13.3 浏览器手动测试

打开：

```text
http://localhost:<aw.port>/desktop-ui/
```

验收：

1. `Agent` tab 顶部显示建议/鼓励卡片或空状态。
2. 卡片不会遮挡当前状态卡片。
3. 数据不足时显示空状态，不出现虚假证据。
4. 缩小到 800x600 时，卡片内容可读且不重叠。
5. LLM 不可用时页面仍可加载。

### 13.4 构建测试

运行：

```powershell
mvn test
powershell -ExecutionPolicy Bypass -File scripts/check-desktop-chat-tab.ps1
```

验收：

- Maven exit code 为 0。
- 现有 chat tab 静态检查仍通过。

---

## 14. 验收标准

- **SPEC-ADV-ACC-001**: `Agent` tab 顶部出现一个基于过往行为的建议/鼓励显示框。
- **SPEC-ADV-ACC-002**: 显示框一次只展示一条建议、鼓励或提醒。
- **SPEC-ADV-ACC-003**: 建议必须包含证据标签和依据摘要。
- **SPEC-ADV-ACC-004**: 数据不足时显示空状态，不编造结论。
- **SPEC-ADV-ACC-005**: LLM 不可用时不影响页面加载。
- **SPEC-ADV-ACC-006**: 首版不出现任何写入记忆或创建任务的按钮。
- **SPEC-ADV-ACC-007**: 800x600 到宽屏范围内无关键内容重叠。
- **SPEC-ADV-ACC-008**: `mvn test` 通过。

---

## 15. 规格追溯矩阵

| 规格 ID | 目标文件/组件 | 验证方式 |
|---------|---------------|----------|
| SPEC-ADV-GOAL-* | 本文档、整体功能 | 规格审查 |
| SPEC-ADV-NON-* | UI 和 API 范围 | 代码审查 |
| SPEC-ADV-IA-* | `index.html`, `agent.js` | 浏览器检查 |
| SPEC-ADV-MDL-* | `DesktopAgentController`, 前端 state | 单元测试 |
| SPEC-ADV-SRC-* | `SummaryService`, `SummaryPromptService` | 单元测试 |
| SPEC-ADV-GEN-* | 建议生成服务/方法 | 单元测试 |
| SPEC-ADV-API-* | `/desktop/summary` | Controller 测试 |
| SPEC-ADV-UI-* | `index.html`, `agent.js`, `styles.css` | 静态检查 + 浏览器测试 |
| SPEC-ADV-ERR-* | 后端和前端错误处理 | 单元测试 |
| SPEC-ADV-PRV-* | prompt 构建和输出 | 隐私检查 |
| SPEC-ADV-TST-* | 测试文件和脚本 | `mvn test` |
| SPEC-ADV-ACC-* | 全功能链路 | 验收测试 |
