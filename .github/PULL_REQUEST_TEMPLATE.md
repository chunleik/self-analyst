<!--
提交前请先阅读 CONTRIBUTING.md，特别是"数据边界红线"与"规格驱动流程"两节。
请勿在本 PR 中粘贴个人数据、真实窗口标题、文件路径、聊天记录或未脱敏的日志。
-->

## 行为变化

<!-- 改了什么行为，以及为什么需要这个改动。 -->

## 关联 Issue 或规格

<!-- 例：Closes #12；涉及 openspec/specs/title-capture/spec.md -->

## 规格影响

<!-- 勾选一项 -->

- [ ] 不涉及行为契约变化（纯重构、文档、构建或测试改动）
- [ ] 涉及行为契约变化，已同步更新 `openspec/specs/` 中的主规格
- [ ] 新增、删除或重命名了 capability，已同步更新 `docs/README.md` 索引表

## 数据边界

<!-- 勾选一项；若触及边界，必须逐条说明如何满足 CONTRIBUTING.md 的数据边界红线 -->

- [ ] 不触及采集范围、持久化字段与外发数据
- [ ] 触及，说明如下：

<!-- 新增采集/持久化了哪些字段？是否只保留元数据？是否有新的外发数据？敏感应用与失败回退如何处理？ -->

## 验证方式

<!-- 请填写实际运行过的命令与结果，不要只写"已测试"。 -->

```powershell
# 例：
# mvn test
# openspec validate --all --strict
# cargo clippy --manifest-path self-analyst-axsidecar/Cargo.toml --all-targets -- -D warnings
```

- [ ] `mvn test` 通过
- [ ] `openspec validate --all --strict` 通过
- [ ] 涉及 Rust 改动时，`cargo fmt --check`、`cargo test` 与 `cargo clippy -- -D warnings` 通过
- [ ] 缺陷修复已补充回归测试
- [ ] 未提交密钥、用户配置、数据库、日志或被 `.gitignore` 忽略的输出文件

## 界面截图

<!-- UI 改动必填；截图请勿包含个人数据。无 UI 改动可删除本节。 -->

## 依赖变化

<!-- 新增或升级依赖时填写：用途、许可证，以及是否已更新 THIRD-PARTY-NOTICES.md。无变化可删除本节。 -->
