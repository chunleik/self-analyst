## Why

AFK 覆盖只要不完整，任务置信度就会被压低。真实数据里几乎每小时都是 partial，所以置信度几乎全是 low，不能区分证据强弱。

## What Changes

- 覆盖完整性只保留在 sourceCoverage 和内部统计中。
- 任务置信度只反映证据类型：只有标题观察为 low，推断最高 medium。覆盖缺口不再把 high 降成 medium。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `llm-wiki`：新增置信度与覆盖分离。

## Impact

- Wiki 摘要解析和提示词中的置信度上限说明。
