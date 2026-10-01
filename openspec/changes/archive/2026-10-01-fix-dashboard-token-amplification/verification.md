# 验证记录

## 实现与契约

- 当前窗生成移入独立后台批次；并发刷新共用批次，本地统计和历史 Wiki 立即返回。
- 标题、统计日和语言变化都不绕过至少 5 分钟准入；失败停止剩余调用，按 5/10/20/40/60 分钟退避，重启保持冷却。
- 每个模型请求可等待 60 秒，后台自行获取和关闭模型租约。关闭时取消尚未启动的批次并等待运行任务释放租约。
- 文案保留原始引用投影，不能将旧语言/旧统计日的结果覆盖新范围。
- 用量区分真实 usage、成功估算、未知失败预留和旧记录；原累计和保守预算保持兼容。
- 看板隐藏暂停摘要轮询，初始加载、自动刷新和恢复可见共用请求。
- 主规格 desktop-summary 与 llm-budget 已同步；双语 README 一致更新。

## 本地验证

- `mvn test`：Java 984 项，0 失败、0 错误、6 项既有可选跳过；Node 209 项全部通过。
- 后台摘要/计量/预算/模型热切换针对性测试通过；真实 HTTP 模型调用超过 5 秒时，看板仍立即响应且生成成功，真实 usage 仅记账一次。
- 原实现的持续标题变化、隐藏页面轮询和重叠请求回归已先复现失败，再验证修复。
- 跨月迁移夹具使用写入回执的 UTC 接收月份，10 项迁移测试通过。
- `cargo check --manifest-path self-analyst-desktop/src-tauri/Cargo.toml --features titlebar-review --bin titlebar-review` 通过；可选验收工具保留显式 target 并移出打包器自动扫描目录。
- 当前 change 严格校验通过，30 个主规格严格校验通过。已有未完成 add-python-release-assistant 不属于本次修复，保持原样。
- 打包 JAR 的真实进程冒烟通过；测试根隔离，采集器关闭。
- 中英文用量面板已用示例数据验收；截图保存在 docs/images/dashboard-usage-zh.jpg 和 dashboard-usage-en.jpg。

## 交付

- PR：https://github.com/chunleik/self-analyst/pull/81。
- 实现提交 `2c434c2cbfc01e0549f05a6c96b4a94a89f19eeb` 的 Windows 全量验证及三平台数据守卫全部成功： https://github.com/chunleik/self-analyst/actions/runs/36834117971 。归档后的最新提交仍需重新等待必需检查，不沿用本轮结果。
- 安装包：artifacts/SelfAnalyst_0.5.0_x64-setup.exe。
- SHA-256：`1157e4b3cf128631c178574274786f2d2d4b611dc37867f052fc7a2d37c24f9d`。
- 已核对正式安装包包含主程序、后端 JAR 和安装布局标记，不包含可选 titlebar-review 验收程序。
- 生产历史用量保持原值；旧构建保留在 .tmp/previous-backend-builds。docs/ 下保留双语用户文档和示例截图，作为现行用户说明与验收证据。
