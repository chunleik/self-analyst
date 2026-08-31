# 安全策略

感谢你帮助改进 SelfAnalyst 的安全性。本文说明漏洞报告渠道、支持范围和当前安全边界。

## 报告漏洞

请不要通过公开 Issue 报告尚未修复的安全漏洞。请在仓库的 **Security → Report a vulnerability**
中创建私密 GitHub Security Advisory，并尽量提供受影响提交、复现步骤、影响评估和建议修复方向。

响应目标为首次确认 7 天、初步评估 14 天；修复时间根据严重程度协商。这是尽力而为的维护目标，
不构成服务等级承诺。

## 支持范围

项目目前只为最新 `main` 分支提供安全修复。请先在最新代码上确认问题仍可复现。

## 本地服务安全边界

- 服务只监听 `127.0.0.1`，并拒绝非回环 `Host` 或非回环浏览器 `Origin`。
- Tauri 桌面壳启动后端时，通过 `SELF_ANALYST_DESKTOP_TOKEN` 注入每次启动生成的随机 token。除
  `/desktop/session` 外的 `/desktop/*` 路由要求 `X-SelfAnalyst-Token` 或 HttpOnly、
  SameSite=Strict 会话 cookie；生命周期健康检查和退出接口同样要求 token。
- 直接执行 `java -jar` 且未设置该 token 时，桌面 API 为兼容浏览器访问不会启用 token 验证。
  回环绑定不是进程级隔离：同一操作系统用户下的恶意进程仍可能访问本地接口。
- `/api/0` 和 `/0` ActivityWatch 兼容接口不使用桌面 token；它们受回环绑定、Host 和 Origin
  校验保护。同机进程仍处于信任边界内。
- `aw.port` 由 Java 从用户级 `config.toml` 解析；桌面壳通过唯一临时握手文件取得实际端口，
  不使用另一份硬编码端口配置。

## 密钥与敏感配置

- API key 可以来自环境变量或 `./data/config/config.toml`。推荐优先使用环境变量；
  用户配置和本地日志都不应提交到仓库。
- raw 配置编辑接口必须返回真实密钥才能避免保存脱敏占位符；因此它属于受保护的本地敏感接口。
- UI、日志、聊天正文、AgentState 和上下文快照不得记录配置密钥。
- 文件监控、聊天、Embedding 和搜索的外发边界见 [PRIVACY.md](PRIVACY.md)。OCR 与声音采集当前已移除。

## 重点关注的问题

- 绕过回环绑定、Host/Origin 校验或桌面 token 的本地 API 访问
- DNS rebinding、CORS 绕过、CSRF、路径穿越、SSRF 和任意文件读写
- 配置、错误响应、日志、聊天或备份中的 API key 和个人数据泄露
- TOML 编辑、文件元数据过滤与路径处理、UIA 标题识别、导入导出和外部工具调用中的命令/路径注入
- `chat.db`、AgentState、长期记忆和删除恢复流程中的越权、数据损坏或残留
- Java、Rust、Tauri、WebView、捆绑二进制和模型依赖中的高危漏洞

## 通常不属于漏洞的情形

- 用户主动配置的第三方 LLM、Embedding 或搜索服务自身的数据留存政策。
- 已明确记录的同机进程信任边界本身；但能够突破已声明的 token、Host、Origin 或路径限制仍应报告。
- 用户主动开启文件监控后，文件名、路径和时间等元数据会按配置在本地采集，作为 Agent 工具结果时
  可能进入用户配置的 LLM；普通文件正文不会被读取或发送。未按配置、未告知或越界采集可能是漏洞。

本策略会随实现变化更新。若文档与实际安全边界不一致，请按安全问题处理并私密报告。
