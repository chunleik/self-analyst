## Why

项目已经在 `docs/specs/file.md` 中维护了文件采集的现行行为契约，但初始化后的 OpenSpec 尚无对应的
主规格。需要先建立一份经过当前代码和测试核对的 OpenSpec 基线，使后续文件采集变更能够使用 delta
规格演进，而不必继续依赖两套结构不同的契约。

## What Changes

- 新增 `file-metadata-collection` capability，将既有 `SPEC-FILE-*` 当前行为转换为 OpenSpec 的
  Requirement/Scenario 结构。
- 迁移范围只包含可观察的元数据边界、路径过滤、采集与删除状态、持久化净化、查询暴露边界以及桌面
  API/UI 契约；不迁移私有实现步骤、依赖版本、源码追溯矩阵或历史方案。
- 保留既有 `SPEC-FILE-*` 稳定编号；通过代码和测试核对行为准确性，并使用严格 OpenSpec 校验检查
  制品结构和一致性。
- 对与当前实现不一致的旧条款，只记录实际可验证的现行行为并列出差异，不把尚未实现的更强保证写入
  基线，也不在本次文档迁移中擅自修复代码。
- 记录并安排修正 `docs/specs/file.md`、`docs/architecture.md`、`README.md`、`PRIVACY.md`、
  `SECURITY.md` 中与当前实现不符的正文提取、派生、缓存或外发表述，使既有文档与 metadata-only
  基线一致。
- 本变更只迁移文档基线，不新增或修改运行时行为、API、配置键、依赖或业务代码。

## Capabilities

### New Capabilities

- `file-metadata-collection`: 用户显式配置目录中的本地文件元数据采集、过滤、状态收敛、隐私边界、
  查询和桌面展示契约。

### Modified Capabilities

无。

## Impact

- 新增 OpenSpec change 制品；同步后将新增
  `openspec/specs/file-metadata-collection/spec.md` 作为该能力的 OpenSpec 基线。
- `docs/specs/file.md` 在迁移和评审期间继续保留，作为既有编号及实现追溯来源，不在本变更中删除。
- 后续迁移任务会修正 `docs/specs/file.md`、`docs/architecture.md`、`README.md`、`PRIVACY.md` 和
  `SECURITY.md` 的过期说明，但不改变产品行为。
- 本 change 不提前修改 `docs/README.md` 或 `docs/specs/README.md` 的规格权威来源；待 delta 完成、
  同步并归档为 OpenSpec 主规格后，再单独更新索引以避免产生指向尚不存在主规格的链接。
- 不影响 Java、JavaScript、Rust、数据库 schema、桌面 API、构建产物或外部依赖。
