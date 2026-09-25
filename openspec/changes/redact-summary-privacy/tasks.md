## 1. 实现

- [x] 1.1 采样前脱敏并排除无痕、配置的应用和网站。验证：`WikiTitleSamplerTest.privacyRedactsSecretsAndDropsPrivateBrowsingAndExcludedSources`。
- [x] 1.2 Windows 前台进程命令行写入 `private_browsing`。验证：窗口采集代码审查，普通测试不依赖真实前台窗口。
- [x] 1.3 同步主规格并运行 `mvn test` 与 `openspec validate redact-summary-privacy --strict`。
