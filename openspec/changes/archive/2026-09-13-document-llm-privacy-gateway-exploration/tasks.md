## 1. 探索文档

- [x] 1.1 新增 `docs/explorations/2026-09-13-llm-privacy-gateway.md`，对照讨论核查候选方案、国际化、实体目录、本体边界、项目接入点、风险、验证计划与来源，明确尚未实施及实测。
- [x] 1.2 更新 `docs/README.md` 的探索文档入口，检查相对链接目标存在且说明不属于现行行为契约。

## 2. 文档验证

- [x] 2.1 检查新增文档及索引中的本地链接、文档范围和格式，运行 `git diff --check` 与 `openspec validate document-llm-privacy-gateway-exploration --strict`，记录结果。

## 流程说明

本 change 仅保存探索成果。`skip_specs: true` 跳过增量规格；文档保存本身不涉及架构、依赖、数据模型或运行行为变化，因此根据 design 产物的条件省略 `design.md`。无需主规格同步或运行应用测试。验证完成后按仓库流程归档，探索文档继续保留在 `docs/explorations/`。

## 验证结果

- 已对照讨论检查候选架构、国际化维度、中文默认配置风险、实体目录、本体边界、项目接入点及待决策问题。
- 两份文档共检查 61 个本地链接，目标全部存在；代码围栏闭合，未发现行尾空白或 Unicode 替换字符。
- `git diff --check` 通过；新增未跟踪文档另行完成逐行空白检查。
- `openspec validate document-llm-privacy-gateway-exploration --strict` 通过。
- 应用源码、运行配置、依赖和主规格未修改；未安装服务或模型，未运行应用测试或模型基准。
