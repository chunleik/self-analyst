# SelfAnalyst 规格约定（SDD Conventions）

SelfAnalyst 采用规格驱动开发（Specification-Driven Development）。本文件集中说明编写、评审和维护正式规格的约定。

> SDD 索引（有哪些 spec、Key Prefix）见 [`docs/README.md`](../README.md)；本文件只讲**怎么写、写什么**。

---

## 1. 核心原则

- **覆盖范围**：每个模块（`self-analyst-*` 子模块）和每个重大特性都必须有一份正式 spec。
- **spec 是契约层**：描述「**要满足什么**」（行为、接口、约束、数据契约），不描述「**怎么实现**」（具体类的私有逻辑、版本号、方法名清单、构建接线）。一次性实施步骤保留在 Issue、提交或短期工作记录中，不作为长期项目文档维护。
- **可追溯**：每条需求有稳定编号 ID，贯穿 spec → 源码 → commit。

## 2. spec 分类

| 类型 | 放置 | 对象 | 示例 |
|------|------|------|------|
| **Module Spec** | `docs/specs/<module>.md` | 一个 `self-analyst-*` 子模块 | `core.md`、`content.md`、`file.md` |
| **Feature Spec** | `docs/specs/<feature>.md` | 跨模块的特性 | `desktop-chat-tab.md`、`llm-wiki.md` |
| **Archive** | `docs/archive/design-proposals/` | 被正式 spec 取代的旧设计提案 | — |

新增 spec 后必须在 [`docs/README.md`](../README.md) 的 SDD 索引表登记（文档链接、用途、Key Prefix）。

## 3. 编号方案

- 每个 spec 有唯一 **Key Prefix**：`SPEC-<域>-*`（如 `SPEC-CFG-*`、`SPEC-AU-*`、`SPEC-FILE-*`）。
- 需求 ID 形如 `SPEC-FILE-010`；同一需求的细分子项用小写后缀 `SPEC-FILE-010a`、`010b`。
- ID **只增不改、不复用**：已发布的 ID 含义固定；废弃就标注「已废弃」而非删号重用。
- ID 同时出现在：组件正文、末尾追溯矩阵、相关 commit message。

## 4. 标准结构（Module Spec 骨架）

以 `content.md` / `file.md` 为范式：

```
# <模块名> SDD 规格说明书
> 一句话定位 + 状态（设计中/已实现）

1. 模块标识      模块名 / 版本 / 类型 / 默认开关
2. 架构契约      依赖、数据流、职责边界（SPEC-*-00x）
3. 组件规格      每个类/组件一节，按 SPEC-*-0NN 编号，子项 a/b/c
4. 配置          配置键清单 + 读取入口
5. 测试规格      单测级「测试 → 预期」表
── 追溯矩阵      ID → 源文件
```

Feature Spec 以 `desktop-chat-tab.md` 为规范模板（结构更丰富，含交互/UI 契约）。

## 5. 追溯矩阵

- 位于 spec **末尾**，把 spec ID 段映射到承载它的源文件。
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

## 新增一份 spec 的清单

1. 在 `docs/specs/` 新建 `<name>.md`，套用第 4 节骨架，分配 Key Prefix。
2. 为每条需求编号，写好末尾追溯矩阵。
3. 在 `docs/README.md` 的 SDD 索引表登记。
4. 实现时 commit message 引用 spec ID；源文件与矩阵保持一致。
