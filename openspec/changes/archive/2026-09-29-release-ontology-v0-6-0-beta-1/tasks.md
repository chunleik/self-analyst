## 1. 发布准备

- [x] 1.1 同步最新 main 到 codex/ontology-v06；以合并提交及 main 祖先检查为证据。
- [x] 1.2 将项目清单与自身 Cargo 包锁版本统一为 0.6.0-beta.1；运行版本检查和现有 release-channel Node 测试。
- [x] 1.3 更新双语 README、架构示例和 beta 发布说明，同步 SPEC-ONTO-012；检查 README 语言与 OpenSpec 严格校验。
- [x] 1.4 构建 beta 可执行 JAR 并执行隔离启动冒烟；确认版本准备可用于标签构建。

## 2. 完成准备后的发布操作

准备任务完成后归档此 change，提交并推送功能分支。在未占用的 v0.6.0-beta.1 标签指向该提交后推送标签，由现有 Windows Release 工作流执行完整门禁和分发构建。最终必须核对工作流成功、Release 的 tag SHA 与 prerelease 标记、安装包/便携包及校验和齐全，且 main 未合并 0.6、稳定 Latest 未变化。
