## 修订状态

2026-11-14：用户批准补充目录发现/选择，并明确不考虑旧邮箱兼容。此前 PR #154 的 11 项实施及测试记录保留在 verification.md 作为历史证据；不能证明本修订完成。用户随后授权实施、PR 与发版；以下按修订后的合同重新验收，任务完成即更新，最终提交的远端检查单独核对。

## 1. 基线与统一配置

- [x] 1.1 获得实施授权后整合最新 main 与 PR #154，核对 AGENTS.md、openspec/config.yaml、两份相关主规格及本 change；保留已发布连接删除、工具弹窗和开发者观测能力，记录最终基线提交。
- [x] 1.2 修改 IntegrationConfig、IntegrationStore、MailAccount 和运行时映射，要求显式范围，新建默认 all，selected 支持 1–256 项；不做旧邮箱迁移。以配置/导入/非法输入/无关 MCP 回归测试证明行为，不静默改写损坏配置。

## 2. 管理端发现与安全边界

- [x] 2.1 在 DesktopIntegrationController、McpManager 和邮箱服务/传输层增加受管桌面目录发现，返回完整名称及层级信息，绑定账户/revision；补充未启用与 selected 范围外可发现、Agent 不可越权、跨账户秘密拒绝的 HTTP/服务测试。
- [x] 2.2 补充临时授权码发现与持久凭据复用，禁止发现写入凭据/启用/读取正文；合成 IMAP 测试检查实际命令、163 ID、TLS/超时/取消/256 项/名称预算及资源清理。
- [x] 2.3 保留强制系统安全持久化和确认后启用事务，移除旧邮箱 session 迁移路径，不改变外部 MCP；验证写入/回读/提交失败、结果不确定恢复、范围变化停用及返回前撤权复核。

## 3. 目录选择器

- [x] 3.1 更新 integrations.js/css 及 locales/zh.json、en.json，完成 all/selected、搜索层级复选列表、当前全选/清空、已选数量与不可用项保留；Node 测试覆盖默认、切换、动态新目录、256 项及安全文本渲染。
- [x] 3.2 配置后与打开已配置账户时自动加载一次，支持临时凭据“连接并加载”和主动刷新；明确保存/启用成功但发现失败的状态。Node/HTTP 测试覆盖缺凭据、失败重试、重复点击、过期 revision、关闭/切换/删除/断开及迟到响应。
- [x] 3.3 去掉启用/持久化和原处理授权勾选项，保留正文许可及完整确认；测试确认取消不持久化/启用，不把目录发现视作邮件/模型授权，保存失败保留输入。
- [ ] 3.4 增加键盘/ARIA、浅色/深色、窄窗口视觉验收和截图；沿用未保存离开确认。真实 Windows/Tauri 或邮箱验收未完成时明确记录，不用 DOM 测试替代。

## 4. 文档与验证

- [x] 4.1 更新 docs/mail-integration.md、PRIVACY.md 和 README.md/README.zh-CN.md，说明动态全部与当前全选、自动发现与非后台同步、系统安全存储和限制；检查英文 README 不混中文。
- [x] 4.2 运行 `node --test self-analyst-app/src/test/js/integrations.test.mjs self-analyst-app/src/test/js/i18n-catalog.test.mjs` 及新增目录界面测试；运行邮箱/配置/受管接口定向 Java 测试，记录实际命令和结果。
- [x] 4.3 同步 mail-integration 与 user-configuration 主规格，运行 `mvn test`、`openspec validate default-all-mail-folders --strict`、`openspec validate --all --strict`、`git diff --check`，记录最终结果及未验收项，不本地打包。
- [ ] 4.4 完整差异审查，核对无凭据或真实用户数据；后续获准提交 PR 时等待精确最新提交全部必需 CI，包括 Windows 全量验证；不得沿用旧 PR 检查，不绕过分支保护。只在本次明确授权范围内提交、推送、合并和远端发版，不自动归档。
