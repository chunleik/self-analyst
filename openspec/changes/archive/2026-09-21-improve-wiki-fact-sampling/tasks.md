## 1. 事实与模型输入

- [x] 1.1 实现结构化标题采样器并接入 WikiFactBuilder，测试验证去重、分离区间、来源引用、AFK 关联和时长不变。
- [x] 1.2 实现四层时间采样、独立预算及省略信息，测试验证顺序无关、多时段覆盖、预算与 Unicode 边界。
- [x] 1.3 更新 WikiSummarizer 提示词及版本，测试验证只调用一次模型、白名单输入、本地指标保持权威及旧构造器兼容。

## 2. 验证与文档

- [x] 2.1 运行 Wiki 模块及依赖测试，记录测试结果并检查差异。
- [x] 2.2 同步 llm-wiki 主规格与 docs/architecture.md，运行 OpenSpec 严格校验并归档 change。

验证记录：`mvn -pl self-analyst-wiki -am test` 通过，事件模块 145 项（1 项既有 benchmark 跳过）；最终 Wiki 修改以 `mvn -pl self-analyst-wiki -am '-Dtest=Wiki*Test,OpenAiCompatibleEmbeddingClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 复验，66 项全部通过。测试使用本地模拟模型，不向远程模型发送活动数据；尚未进行真实模型摘要质量对比。
