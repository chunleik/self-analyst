## 1. 实现

- [x] 1.1 在 `self-analyst-desktop/src-tauri/src/documents.rs` 中以逐字节格式化生成 SHA-256 小写十六进制摘要，替换 `format!("{:x}", hash.finalize())`；以 `Windows 全量验证` 中 `cargo test` 与 `cargo clippy -- -D warnings` 通过为证
- [x] 1.2 补充回归测试，断言已知输入 `abc` 的摘要为标准小写 64 位十六进制串；以 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` 通过为证

## 2. 验证

- [x] 2.1 运行 `openspec validate adapt-sha2-digest-hex --strict`，通过
- [x] 2.2 PR 的 `Windows 全量验证` 通过（本机无 Rust 工具链且该文件仅面向 Windows，无法本地编译）
