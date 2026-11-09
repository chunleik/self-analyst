# 实施验证记录

日期：2026-11-09。分支：`codex/add-mcp-mail-integrations-plan`。本记录首先记录本地实施验收阶段，此阶段未提交、推送、创建 PR、合并、打包或发布。随后用户明确授权 PR、合并及发版；远端结果另行追加，不以本地测试替代。

## 环境与范围

- Linux x86_64，标准 Eclipse Temurin JDK 21.0.13+9、Maven 3.9.11、Node 24。
- JDK 从 Eclipse Adoptium 官方发行取得并核对 SHA-256；使用真实 javac 的 `--release 21`，没有把 release 改为 source/target 的替代编译。
- Maven 按标准生命周期执行；仅为当前执行网络配置代理及既有系统证书信任，不关闭 TLS 验证。
- 所有邮箱、MCP、TLS、模型重定向及秘密均为合成夹具。没有真实 QQ/163 登录，也未使用用户电脑。

## 已执行

1. 定向 Java 验证：265 项，0 失败、0 错误、4 项条件性跳过，46 个命中测试类。覆盖新增 MCP、mail、integration、credential 类及现有运行存储、会话、记忆、摘要、模型热切换回归。
2. 第一轮完整 `mvn test`：1,366 项 Java 测试，0 失败、0 错误、8 项条件性跳过；Node 384 项，352 通过、32 项既有 opt-in/平台跳过。
3. 最终完整 `mvn test`：233 个 Java 测试类，1,368 项测试，0 失败、0 错误、8 项条件性跳过；Node 385 项，353 通过、32 项既有 opt-in/平台跳过。包含后续元数据保存不联网、运行时 IMAPS 服务加载和凭据清理提示回归，整个 reactor 为 BUILD SUCCESS。
4. MCP 使用真实 SDK 配合合成 HTTP JSON/SSE 交换，测试初始化、发现、批准、启用、精确参数调用、描述变化拒绝、撤权和无自动重试。
5. IMAP 合成服务器验证 EXAMINE、BODY.PEEK、163 每条物理认证连接的 ID；断言没有 STORE、APPEND、COPY、MOVE、EXPUNGE、SELECT、CLOSE、SMTP 或附件流读取。连接测试不 FETCH。合成 TLS 验证证书和主机名失败、累计输入预算、取消与截止时间。
6. 测试持久来源、重启、删除、来源表版本、默认记忆排除、队列重检、原生 embedding/写工具控制；实际模型租约的 chat/plain/compaction/summary/repair 目标检查及 307/308 不转发回归。
7. 单独验证运行时类路径通过 Jakarta Mail 服务发现找到 Angus IMAPS provider；不连接网络，不生成发布包。
8. Node 夹具覆盖双语资源、草稿与版本冲突、授权码清空、迟到结果、确认卡绑定/拒绝/过期/取消、嵌套参数及不可信 HTML。未将 DOM/CSS 断言描述为真实窗口截图验收。
9. 安全复核提出的问题均已修复：模型重定向、原始 SDK 日志、可变确认参数、目标切换确认与取消、目录重建。整段 `io.modelcontextprotocol` 日志关闭；应用只显示安全分类状态。保险库清理失败显示待清理提示，避免宣称已经擦除。
10. `git diff --check` 通过；双语 README 已人工核对，英文新增段落无中文说明。

## OpenSpec

- 仓库 CI 固定使用的 OpenSpec 1.11.0：`openspec validate --all --strict` 全量 43/43 通过。以下 7 项基线失败来自规划环境较新的 1.16.1，不能误报为仓库 CI 失败。

- `openspec validate add-mcp-and-readonly-mail --strict`：通过。
- 六项主规格已经同步；新 `mcp-integrations`、`mail-integration` 及修改的 `long-term-memory`、`web-search` 单独严格验证通过。
- 全库 `openspec validate --specs --strict`：30 通过、7 失败。将 HEAD 原始规格复制到隔离目录只读验证得到 28 通过、相同 7 失败，证明新增两项均通过，没有新增失败集合。
- 既有失败：`agent-runtime`、`chatgpt-auth`、`desktop-help`、`desktop-shell`、`event-query-tools`、`llm-wiki`、`user-configuration`。严格模式把既有长文本等警告视为失败；不修改无关规格来消除基线问题。

## 本地实施阶段未执行和交付边界（后续远端结果见末节）

- Windows Credential Manager 的真实合成读写测试已编写，Linux 上明确跳过；不能把会话凭据和 mock 测试当成 Windows 验收。
- 真实 Windows/Tauri UI、真实 QQ/163 账户及必需 Windows CI 未运行。
- 浏览器无法访问执行环境的 localhost 合成页面，真实桌面端到端与视觉验收未完成。
- 按用户偏好未本地打包，未触发发版，发布包中的服务合并和 Windows 便携运行尚未验证。
- 配置/确认等待都有限时；操作系统拒绝凭据删除或进程崩溃可能留下需手工清理的保险库孤立项。断开不撤销服务商授权码、不删除已有会话或备份、不撤回模型已收数据。
- 因 7.2、7.3 的跨平台/端到端验收缺口仍保留 change，未归档。

## 发布前补充复核

- 修复旧版 `legacy_default` 原生工具兼容回归，保留 MCP/邮箱不可用与 `websearch.*` 修改确认限制；新增真实会话策略回归。
- 新增无网络 `MailRuntimeCheck`，检查 IMAPS provider 与合成 multipart/plain/html MIME activation，并接入现有远端打包冒烟。类路径验证通过，实际 JAR/便携运行仍待远端结果。
- 修正连接文档保存/凭据/测试顺序及 UI 测试状态、认证/TLS/服务商限制分类。
- 最后复核后的标准 `mvn test`：233 类、1,372 项 Java 测试，0 失败、0 错误、8 项条件跳过；Node 386 项、354 通过、32 项既有平台/opt-in 跳过；全部 7 模块 BUILD SUCCESS。定向 Java 9 项和 UI/国际化 26 项均通过。
- 独立只读安全复核未发现新增阻断。真实邮箱和桌面视觉仍未验收，保持 beta 边界与未归档状态。


## 远端交付与归档前核对

归档核对日期：2026-11-09。基线为版本合并提交 `6659af6492438bd38de0475294df5f03394c21d6`。

- [功能 PR #151](https://github.com/chunleik/self-analyst/pull/151) 正常合并为 `f8c09caac0f8f29ef09b0cddf1076c65ed670d78`；精确 head `843fb5298d1c38a777fd6c3033c3423563c737cf` 的 [CI 42606077559](https://github.com/chunleik/self-analyst/actions/runs/42606077559) 五项必需检查全部成功。
- [版本 PR #152](https://github.com/chunleik/self-analyst/pull/152) 的精确 head `2ccacce672af5daca3cc69c4f07e57d5697d29a9` 通过 [CI 42608339239](https://github.com/chunleik/self-analyst/actions/runs/42608339239) 五项必需检查后正常合并，未绕过保护。
- [v0.6.1-beta.15](https://github.com/chunleik/self-analyst/releases/tag/v0.6.1-beta.15) 已于 2026-11-09 11:56:05 UTC 公开为预发布，tag 指向上述版本合并提交；稳定 latest 仍为 v0.6.0。
- [Windows Release 42610237881](https://github.com/chunleik/self-analyst/actions/runs/42610237881) 全部步骤成功：OpenSpec 44/44；Java 1,372 项零失败/错误、8 项条件跳过；Node 386 项全部通过；Rust 测试/Clippy 和合成 UIA 通过。
- Windows Credential Manager 原生合成测试实际完成读写、目标绑定和删除，1 项通过、无跳过。便携包与安装布局均实际执行包内 Java 的 IMAPS/MIME 探针及 JAR 冒烟；NSIS 安装、重装、资源、桌面启动、后端与卸载验证通过。上述检查均由远端 Windows 执行，不是本地打包或用户电脑验收。
- 四个资产已下载独立核对大小、SHA-256 与 GitHub digest；ZIP/JAR CRC、beta.15 版本、新 UI/IMAPS/activation 服务和 Java 21.0.13 runtime 均复核。敏感文件名/目录筛查未发现用户数据、数据库、日志或凭据配置文件；这不是内容秘密扫描。完整大小与哈希见发布页。
- 六项主规格逐条核对，35 条增量需求与对应主规格完全一致，无待同步修改。归档前 change 严格校验及全量严格校验通过。归档不重写主规格。

任务 7.2 依据上述证据补记完成。任务 7.3 保持未完成：合成协议、权限与 Node 夹具分别通过，但新增连接/确认界面的完整端到端和人工视觉验收仍缺；真实 QQ/163 账户互通未验证。用户在明确知晓这些缺口后要求归档，因此带此警告归档，不将未完成项改成通过。
