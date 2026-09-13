## Why

保存关于可选 LiteLLM + Presidio 隐私网关、多语言实体识别和本体适用性的探索结论，便于后续继续评估，避免把讨论建议误认为已实现功能。

## What Changes

- 新增 `docs/explorations/2026-09-13-llm-privacy-gateway.md`，记录候选架构、国际化维度、实体目录与策略分离、中文适配细节、项目接入点和验证计划。
- 在 `docs/README.md` 增加探索文档入口，并明确其非现行行为契约的地位。
- 标注资料核查日期、证据来源、未实测事项及待决策问题。
- 本次仅保存探索文档，不实现或部署网关，不修改应用配置、依赖、主规格及采集边界。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。文档记录候选方案，不改变任何运行行为；使用 `skip_specs: true`。

## Impact

影响探索文档、文档索引和本次 OpenSpec 追溯记录。验证本地链接、文档边界、Markdown 空白和 OpenSpec 严格校验；无需应用测试或主规格同步。完成后归档的是文档保存工作，网关功能仍待后续独立 change 规划和实施。
