## Why

英文 `README.md` 被追加了中文功能说明，现有 `AGENTS.md` 将所有 README 都归为中文文档，容易再次造成语言混用。需要明确双语 README 的语言边界。

## What Changes

- 修改 `AGENTS.md` 的文档语言规范：`README.md` 使用英文，`README.zh-CN.md` 使用简体中文，其余说明性文档默认使用简体中文。
- 强调 README 新增或修改的标题、正文、列表和示例说明必须符合对应文件的语言，并同步维护双语版本的信息。
- 要求交付前检查英文 README 是否混入中文功能段落，禁止直接粘贴中文说明。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。本次仅调整仓库协作规则，不修改产品行为，设置 `skip_specs: true`。

## Impact

仅修改 `AGENTS.md` 并记录本次 OpenSpec 变更；不涉及运行时代码、依赖或 API。本次不改写 README 正文。
