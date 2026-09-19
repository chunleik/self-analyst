# 验证记录

## 行为验证

- 对话（流式）、plain/summary（非流式）和连接探测的本地 HTTP fixture 均断言实际请求不含 `max_tokens`、`max_completion_tokens`、`max_output_tokens`。
- 热更新集成夹具仍含旧 `max-tokens=64`，新旧模型调用均未发送限制，验证没有被旧配置恢复。
- 旧键正值、零、负值、字符串及旧环境变量均不参与有效配置；合法 raw 原文完整保留，修改旧键不重建 LLM 资源，不产生该键重启提示。
- 模型设置 API 不返回 `maxTokens`，显式更新或重置该字段被拒绝；表单及语言资源已移除对应控制项。
- 生成测试保留短提示、超时、响应大小、认证及重定向保护和有效正文校验。每日预算、压缩及迭代次数未改变。

## 自动化结果

- 针对性 Java：58 项通过，`target/output-limit-targeted.log`。
- `mvn test`：所有模块 BUILD SUCCESS；Java 合计 772 项，失败 0、错误 0、按既有条件跳过 6；Node 167 项通过。日志 `target/output-limit-full-tests.log`。
- `mvn package -DskipTests`：成功，`target/output-limit-package.log`。
- OpenSpec change 严格校验通过，28 份主规格严格校验通过；四份增量已同步并保留原场景。
- `git diff --check` 通过。README 两个版本的完整差异已核对，英文无新增中文说明。

## 界面与运行

- `scripts/llm-settings-preview.mjs` 使用实际产品资源及内存假数据进行浏览器检查，生成参数区不再有最大输出选项，显示服务端决定输出限制的说明。截图为 `target/output-limit-ui.png`。
- 自动化测试使用本地 HTTP 服务，未替用户向真实模型发送付费探测，也未输出或修改用户密钥。
- 最新 JAR 已复制到当前桌面开发目录，使用既有正常应用标识和用户数据目录重启。用户旧 TOML 无须删除 max-tokens 行。

## 文档与边界

更新 README.md、README.zh-CN.md 和 `docs/llm-settings.md`；已有历史 mockup 与归档文档保留。省略客户端输出上限不等于供应商无限输出，也不保证解决所有协议错误；正文非空判定保持原有契约。
