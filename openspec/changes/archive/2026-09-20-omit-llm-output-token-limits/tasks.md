## 1. 实施

- [x] 1.1 移除 `Config`、`LlmSettings`、支持键、环境映射、热更新及 ConfigTools 中的输出上限，旧键加入退役集合；验证旧值不生效、不阻止启动/保存、不重建资源。
- [x] 1.2 移除 `SelfAnalystAgent` 的 GenerateOptions 输出限制及 `LlmConnectionProbe` 固定 16；使用本地 HTTP 回归断言对话、plain/summary、探测请求无任何输出上限字段。
- [x] 1.3 移除模型设置 API/表单和高级有效配置中的输出上限，更新中英文语言资源；测试旧 API 字段拒绝、表单不再渲染该选项、其它草稿与凭据行为保持。

## 2. 验证与交付

- [x] 2.1 更新 Java/Node 回归，先运行配置、模型设置、探测与热更新针对性测试，再执行 `mvn test`；保留测试超时、响应大小和重复提交验证。
- [x] 2.2 同步 README.md、README.zh-CN.md、`docs/llm-settings.md` 与四份主规格，严格校验 change 和主规格，核对英文 README 无中文混入。
- [x] 2.3 使用实际 UI 资源检查移除后的模型页，重新打包并重启当前应用；记录验证边界后归档 change。
