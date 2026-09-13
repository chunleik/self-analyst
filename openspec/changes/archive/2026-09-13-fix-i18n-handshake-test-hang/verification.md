# 验证记录

日期：2026-09-13。

## 根因与回归

旧测试的请求在连接建立前失败时，服务端无限 accept，主线程无限 join。调查期间用临时诊断程序将请求预算缩短到 1 纳秒，客户端约 63 微秒返回错误而服务端仍等待连接；7 秒 watchdog 终止程序。CI 日志不足以确认当时连接延迟的具体来源。

新增无客户端连接场景直接验证服务端按期限退出。认证握手及响应超时分开验证；后者读取完整认证请求后通过有界通道保持连接，客户端返回后才释放连接，不把连接关闭或连接失败作为超时成功。

## 命令与结果

- `cargo test --offline --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --lib i18n::tests -- --nocapture`：5 项通过。
- 使用当前构建的测试二进制重复运行全部 5 项语言测试 5 轮，每轮有外部 20 秒 watchdog：25 次测试通过，无 watchdog 超时。
- `cargo test --offline --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`：18 项单元测试通过；二进制与文档测试通过。
- `cargo clippy --offline --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --all-targets -- -D warnings`：通过。
- `cargo fmt --manifest-path self-analyst-desktop/src-tauri/Cargo.toml -- --check`：通过。
- `openspec validate fix-i18n-handshake-test-hang --strict`：通过。

## 范围与交付

仅改变测试，不改变 SPEC-I18N-NATIVE-001 的生产行为，主规格无需同步；无用户文档或历史资料迁移。保留工作区中其它文档探索变更。本次仅完成本地修复与验证，未提交、推送或合并；远端 Windows 全量验证仍需在推送后的最新提交上成功。
