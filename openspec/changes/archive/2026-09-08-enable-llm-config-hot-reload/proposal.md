## Why

用户在桌面保存大模型配置后，连接测试可以使用新参数，实际聊天和摘要却仍持有启动时创建的模型，首次补填密钥也不能可靠恢复对话。需要让保存后的新一轮工作采用新配置，并准确说明哪些组件已经生效，哪些仍需重启。

## What Changes

- 新增应用内保存触发的 LLM 热更新，覆盖 `llm.api-key`、`llm.base-url`、`llm.model`、`llm.temperature`、`llm.max-tokens`；聊天、plain/summary、Wiki 摘要和上下文压缩的新工作统一使用新模型配置。
- 一轮聊天及其压缩、推理和工具调用固定使用同一配置版本；已开始的输出继续完成，新的工作取得新版本，保留会话、长期记忆、工具注册和用量计数。
- 支持未配置密钥启动后补填配置恢复聊天及已满足其它依赖的 LLM 服务；清空有效密钥后明确报告不可用。
- 统一桌面 raw/结构化保存和 ConfigTools 的校验、保存、差异计算及应用入口；修正遗漏环境变量导致的有效值展示和显式覆盖保存问题。
- 增加脱敏的配置来源、保存版本及运行中组件状态；区分已应用、已有工作仍用旧版本、需重启和不可用，集中声明配置生效策略。
- 定义并发保存、候选模型构建失败、写盘失败及关闭期间的处理，不因切换重置计量或破坏正在进行的工作。
- 已提交的 `fee54e5` 配置优先级调整为现有基线：用户 TOML > 环境变量 > 默认值，保留 `memory.dir` JVM 参数及 `aw.port` 的现有例外。本 change 扩展其一致性，不重复实现或重写历史。
- 本期不增加外部编辑器文件监听；不热更新 `llm.agent.maxIters`、压缩阈值、预算策略、调度周期、联网搜索、端口或存储目录。Embedding 客户端与索引热更新另行规划；若继承的 LLM 密钥改变，必须准确提示 Embedding 仍需重启。

## Capabilities

### New Capabilities

无；扩展现有能力。

### Modified Capabilities

- `user-configuration`：统一配置解析与保存，定义应用结果、配置来源和运行状态，并保留 TOML 编辑与兼容 API 的既有约定。
- `agent-runtime`：定义模型配置切换、整轮一致性、首次配置恢复和运行资源生命周期。
- `llm-budget`：将 `llm.max-tokens` 改为新工作生效，保留其它预算旋钮的重启边界和既有计量。
- `agent-context-compaction`：压缩使用所属聊天轮次的模型配置版本，压缩阈值仍按原有重建／重启规则生效。

## Impact

- 主要影响 `self-analyst-app` 的 Config、UserConfigStore、DesktopConfigController、ConfigTools、AppSession、SelfAnalystAgent、压缩与桌面状态消费方，以及配置编辑器及其中英文提示。
- 配置保存接口保留 `saved`、`restartRequired`、`unknownKeys` 等兼容字段，增加运行时应用信息和只读有效配置查询；现有配置文件路径及键名保持兼容。
- 需要以本地假模型和回环 HTTP 服务验证模型切换、首次恢复、失败、取消、并发、计量和配置优先级，不依赖真实供应商或真实密钥。
- 实施完成后同步四项相关主规格、README 和必要的架构说明；本次提案阶段只创建 change 规划产物。
