## Why

Dependabot 的 `sha2` 0.10 → 0.11 升级（PR #95）在 `Windows 全量验证` 的“运行 Rust 测试与 Clippy”步骤编译失败：`self-analyst-desktop/src-tauri/src/documents.rs` 用 `format!("{:x}", hash.finalize())` 生成十六进制摘要，而 0.11 的摘要数组类型不再实现 `LowerHex`。这一处写法把桌面壳锁在了 0.10 上。

## What Changes

- 文档另存时的 SHA-256 校验改为逐字节生成小写十六进制字符串，不再依赖摘要数组类型的 `LowerHex` 实现，使同一份代码在 `sha2` 0.10 与 0.11 下均可编译。
- 为十六进制摘要补充回归测试，固定已知输入的小写 64 位输出。
- 仅调整实现写法，不改变校验语义、错误码、接口或依赖版本；`sha2` 版本升级仍由 PR #95 承担，本变更合并后需让 #95 基于最新 `main` 重跑以确认 0.11 下编译通过。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。`agent-document-generation` 中成果保存的完整性校验行为保持不变，本变更不改变规格级行为，因此在 `.openspec.yaml` 中设置 `skip_specs: true`。

## Impact

- `self-analyst-desktop/src-tauri/src/documents.rs`：摘要十六进制化的实现与单元测试。
- 不涉及 `Cargo.toml`、`Cargo.lock`、后端、桌面 UI 或用户文档。
- 该文件仅在 Windows 目标下编译，验证依赖 GitHub Actions 的 `Windows 全量验证`。
