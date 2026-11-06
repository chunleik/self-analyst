## Why

用户已要求提交、推送、打包并发版，本次为 ChatGPT 登录与 Responses 模型连接准备 v0.6.1-beta.10。现有最新预发布为 beta.9，继续使用预发布通道，不替代 v0.6.0 稳定版。

## What Changes

- 将根与六个模块 POM、desktop package.json、Tauri 配置、两个 Cargo 包及锁文件共 13 处自身版本从 0.6.1-beta.9 同步为 0.6.1-beta.10，不改变第三方依赖。
- 新增中文发行说明，介绍 ChatGPT OAuth/Responses、独立 API Key 与 Embedding、回环 PKCE、多账户、权限保护但未加密的凭据存储、流式工具与加密 reasoning、额度暂停和模型配置入口。
- 明确订阅不保证预览资格或直接模型权限，列出真实账户、Windows 权限、原生界面与安装包尚未验证的边界，不将本地协议夹具视为真实账户端到端验证。
- 本轮不新增数据库迁移；保留既有 Wiki schema 5 的完整备份与不可无备份降级警告。
- 记录本地测试采用 Java 21 source/target 临时 wrapper 的限制；精确新提交的 Windows CI、标准 JDK 构建及 tag 发布门禁需独立通过，不沿用历史结果。

## Capabilities

### New Capabilities

无。ChatGPT 行为由独立的 add-chatgpt-subscription-provider change 管理。

### Modified Capabilities

无。本 change 只准备版本元数据与发行说明，设置 skip_specs: true；现行 windows-release 与 runtime-storage 规格不变。

## Impact

13 个版本文件和 docs/releases/v0.6.1-beta.10.md；不修改业务代码、工作流、权限、第三方依赖或采集范围。不创建或覆盖 tag、Release 与发布资产。版本准备完成不等于发布完成；后续提交、PR、精确 head CI、合并及 tag 发布由发布任务继续执行。功能 change 保持活动，不能以本次版本准备代替未完成验收。
