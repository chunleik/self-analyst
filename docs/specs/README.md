# SelfAnalyst Legacy 规格与 OpenSpec 迁移约定

SelfAnalyst 的现行行为契约已经迁移到仓库根目录 `openspec/specs/`。本目录保留迁移前的 SDD 文档，
用于历史背景、旧 ID 与源码追溯；不得再作为与 OpenSpec 并行维护的第二套权威规格。

> 当前 capability 索引见 [`docs/README.md`](../README.md)，OpenSpec 写作约束见
> [`openspec/config.yaml`](../../openspec/config.yaml)。

---

## 1. 核心原则

- **覆盖范围**：每个当前能力都必须有一份 `openspec/specs/<capability>/spec.md` 主规格。
- **spec 是契约层**：描述「**要满足什么**」（行为、接口、约束、数据契约），不描述「**怎么实现**」（具体类的私有逻辑、版本号、方法名清单、构建接线）。一次性实施步骤保留在 Issue、提交或短期工作记录中，不作为长期项目文档维护。
- **可追溯**：每条 Requirement 名称保留稳定编号 ID，贯穿 OpenSpec → 源码 → commit。

## 2. spec 分类

| 类型 | 放置 | 对象 |
|------|------|------|
| **OpenSpec Main Spec** | `openspec/specs/<capability>/spec.md` | 当前已实现能力的行为权威 |
| **OpenSpec Change** | `openspec/changes/<change>/` | 尚未归档的行为变更 delta 与实施记录 |
| **Legacy Spec** | `docs/specs/*.md` | 迁移来源、旧 ID 与历史追溯 |
| **Archive** | `docs/archive/` | 已取代设计、移除功能与历史背景 |

新增 capability 后必须在 [`docs/README.md`](../README.md) 的 OpenSpec 主规格表登记。

## 3. 编号方案

- 每个 spec 有唯一 **Key Prefix**：`SPEC-<域>-*`（如 `SPEC-CFG-*`、`SPEC-AU-*`、`SPEC-FILE-*`）。
- 需求 ID 形如 `SPEC-FILE-010`；同一需求的细分子项用小写后缀 `SPEC-FILE-010a`、`010b`。
- ID **只增不改、不复用**：已发布的 ID 含义固定；废弃就标注「已废弃」而非删号重用。
- ID 同时出现在：组件正文、末尾追溯矩阵、相关 commit message。

## 4. OpenSpec 主规格结构

主规格使用 OpenSpec 可解析结构：

```
# <能力名称>规格

## Purpose
<能力目的>

## Requirements

### Requirement: SPEC-<域>-001 <需求名称>
系统 SHALL ...

#### Scenario: <场景名称>
- **WHEN** ...
- **THEN** ...
```

## 5. Legacy 追溯矩阵

- 旧文档末尾追溯矩阵继续保留，但不复制到 OpenSpec 主规格。
- 形式：`| 规格 ID | 文件 |`，可用 `SPEC-X-001..003` 表示一段。
- 追溯矩阵至少包含 **ID → 文件/组件**；如验证方式不便从测试规格直接判断，可增加「验证方式」列。

## 6. 规格与实现记录的边界

| 进 spec（契约） | 留在代码、Issue、提交或测试脚本 |
|------|------|
| 行为、接口签名、数据契约（表列、字段类型） | 私有实现逻辑、算法步骤 |
| 不变量与约束（并发、降级、退避） | 依赖**版本号**、`Config` 方法名清单 |
| 配置项及其语义 | 根 `pom.xml` 接线、模块注册顺序 |
| 单测级测试规格 | 端到端 / 手动验证步骤 |
| 设计**决策**（选了什么、为何不选另一个） | 决策的论证细节、备选库调研 |

## 7. Commit 约定

- spec 相关提交在 message 中引用对应 spec ID。
- message 用中文或英文，聚焦「为什么」。

---

## 新增或修改能力的清单

1. 新能力在 `openspec/specs/<capability>/spec.md` 建立主规格；行为变更优先通过 OpenSpec change delta。
2. 每条 Requirement 使用稳定 ID，并至少提供一个 `#### Scenario` 与 WHEN/THEN。
3. 运行 `openspec validate --specs --strict` 或对应 change 的 strict validation。
4. 在 `docs/README.md` 的 OpenSpec 主规格表登记 capability。
5. 实现和提交引用相关 spec ID；用户向或架构文档按需同步更新。
