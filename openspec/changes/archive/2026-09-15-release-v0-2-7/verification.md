# 发布准备验证

## 基线与范围

- 基线：origin/main `20f96258bc7883458946b3781b421252d8589916`，包含 PR #47 和 #48。
- 准备开始时最新正式版本为 v0.2.6，远端不存在 v0.2.7 标签；功能分支为 `codex/release-v0.2.7`。
- 六个 Maven 项目版本、Node/Tauri JSON 版本、两个 Rust 工程及其锁文件项目条目均为 0.2.7。完整版本差异已核对，未改变第三方依赖版本。
- 使用独立工作区，保留原工作区中的其他 change 和分发目录。

## 本地验证

- JDK 21，`mvn -s C:/Users/10478/.m2/settings-aliyun.xml --batch-mode test`：BUILD SUCCESS；Java 共 747 项，失败 0、错误 0、条件跳过 6；桌面 Node 144 项全部通过。
- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --locked`：31 项通过。
- `cargo test --manifest-path self-analyst-axsidecar/Cargo.toml --locked`：成功；该二进制当前没有单元测试（0 项）。
- `openspec validate release-v0-2-7 --strict`：通过。
- 归档前 `openspec validate --all --strict`：30 项通过。
- `git diff --check`：通过。

完整运行日志位于本发布工作区的忽略目录 `target/release-maven-test.log`、两个 Rust 工程的 `target/release-tests.log`，不提交日志。

## 文档与规格

发布说明对照活动统计与帮助主规格、用户文档及两个功能 change 的验收记录编写，包含 Wiki schema 4 迁移和回退限制。本 change 没有新增行为契约，设置 skip_specs，无需主规格同步。

`docs/releases/v0.2.7.md` 作为本版本说明长期保留；`docs/architecture.md` 继续维护当前分发包示例。既有功能说明、历史文档和截图保持原用途，本次不新增历史文档迁移任务。

## 外部交付

本文件记录发布准备验证。PR 最新提交的必需检查、合并、标签、Windows Release 和附件校验须在外部交付阶段完成，成功与否以 GitHub 状态及最终交付报告为准。
