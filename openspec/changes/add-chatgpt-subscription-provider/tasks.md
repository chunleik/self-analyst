## 1. 协议与规划

- [x] 1.1 阅读 AGENTS.md、openspec/config.yaml 及 llm-settings/user-configuration/agent-runtime 主规格，通过 CLI 检查现有 change 并创建本 change。
- [x] 1.2 核对官方 Sign in with ChatGPT 开源客户端预览协议，将动态注册、账户 scope、身份验证、独立配置及失败边界记录在 proposal.md、design.md 和增量规格中。

## 2. 后端认证与模型

- [x] 2.1 在 self-analyst-app 配置解析、白名单、定向 TOML 编辑和热更新策略中加入 provider、chatgptAccount/chatgptModel，保持旧 API Key 默认与 Embedding 隔离；补充相关配置回归测试。
- [x] 2.2 实现官方动态注册、系统浏览器授权地址、本机回调、S256 PKCE/state/nonce、签名身份验证、超时和取消，补充错误身份、重复回调、过期/取消及多账户授权测试。
- [x] 2.3 实现独立受保护凭据存储、文件锁、串行轮换刷新、缺少 direct scope 状态与断开清理；测试权限失败、刷新轮换、并发与断开后的迟到结果。
- [x] 2.4 实现 Responses 文本/图片/工具输入、流式输出、usage、单次尝试与取消，并接入既有模型工厂和运行租约；测试分段工具参数、终止事件、失败流、用量及无输出上限。真实账户复验已修复 HTTP 200 缺失 Content-Type 时拒绝有效 SSE 的兼容问题，保留严格终止事件校验；定向 Java 23/23、Node 334 通过/1 跳过，并通过真实账户最小生成验证，详见 verification.md。
- [x] 2.5 在桌面认证边界内提供账户状态、启动/取消/断开、模型目录和最小测试路由；测试未认证访问、受信任目标、无权限、重定向、响应大小与错误脱敏。
- [x] 2.6 验证待验证客户端注册不激活账户、Responses 加密 reasoning 续轮及受限错误分类、按账户暂停和显式测试恢复；运行对应 Java 回归测试。
- [ ] 2.7 修复 Windows 提升权限创建对象时的默认所有者差异，仅初始化本次独占创建的空对象，保持已有对象的严格所有者/ACL 校验；补充 Windows 所有者和 ACL 篡改回归，并由新提交 CI 验证。

## 3. 桌面设置与文档

- [x] 3.1 更新 desktop-ui 的模型设置、API 封装与正式语言资源，加入认证方式、多账户、独立模型、登录/取消/断开及订阅用量提示，保持 API Key 草稿和原有行为。复验已补齐 ChatGPT 模型、账户和连接方式校验的前后端中英文错误资源，保存失败保留草稿；相关 Node 测试 44/44 通过，详见 verification.md。
- [ ] 3.2 复用或添加受限系统浏览器入口，只允许官方授权目标；补充 Node/原生相关验证，覆盖浏览器失败、重复点击、切换账户、关闭/重开、取消与过期响应。
- [x] 3.3 更新 docs/llm-settings.md、docs/architecture.md、PRIVACY.md 和需要的双语 README，说明预览/账户权限前提、受保护凭据、独立 API Key/Embedding 与真实账户验证限制。
- [x] 3.4 在 scripts/build-portable.ps1 的 jlink 模块列表中加入 jdk.httpserver，并运行 chatgpt-runtime-packaging.test.mjs 回归验证配置存在；真实 Windows 打包归入 4.2，不能由此断言通过。

## 4. 验证与规格收尾

- [x] 4.1 运行新增和受影响 Java/Node 针对性测试，记录命令、通过数量及失败或未运行原因。
- [ ] 4.2 在完整 JDK 21 下按原样 --release 配置运行跨模块 mvn test，并完成受影响原生及 Windows 发布检查；若环境阻止完整检查，记录准确阶段和限制，不能以临时 wrapper 或针对性通过代替标准发布验证。
- [x] 4.3 核对实现与五项增量规格，运行 openspec validate add-chatgpt-subscription-provider --strict，记录本地协议测试与真实账户端到端验证的区别。
- [x] 4.4 按仓库行为契约同步要求，将五项增量同步至 openspec/specs 主规格；验证 change strict 及所有主规格 strict 均通过，现有未修改需求保持不变。
- [ ] 4.5 完成原生/浏览器/Windows 及真实账户验收，确认任务清单与验证限制得到处理后再使用 CLI 归档；当前保留活动 change，不得因 Java/Node 通过提前归档。
- [ ] 4.6 在用户自行授权的真实账户环境完成浏览器 OAuth、模型发现、生成、刷新及断开验收，记录套餐与服务端权限边界，不能以本地协议夹具代替。

## 5. 评审修复

- [x] 5.1 刷新网络请求移出存储锁：新增独立刷新锁，写回前比对 refresh token，终止错误先比对再清除，注销先清本地后锁外撤销；在 ChatGptSessionServiceTest 补充“刷新中状态查询不阻塞”“其它进程已轮换”“注销时迟到轮换被撤销”回归测试。
- [x] 5.2 轮换结果先持久化再校验身份：ID token 待校验期间不交出新 access token，校验暂时不可用返回可重试错误且不重复刷新，明确失败清除 token；ChatGptIdTokenVerifier 区分 `chatgpt_identity_unavailable`；补充校验器与会话服务回归测试。
- [x] 5.3 ChatGPT plain/summary 模型使用 5 分钟内部上限并以调用方超时为准，对话模型保持 45 秒；补充 ChatGptResponsesModelTest 回归测试。
- [x] 5.4 运行 ChatGPT 相关 Java 定向测试与 llm-settings Node 测试，运行 `openspec validate add-chatgpt-subscription-provider --strict` 及主规格 strict 校验，并在 verification.md 记录结果。证据：Java 定向 77/77、Node 37/37，详见 verification.md“评审修复验证”。

## 6. PR #137 追加评审修复

- [x] 6.1 成功重新登录同一账户时清除旧 pendingIdToken，补充待校验刷新后重新登录及迟到校验失败不清除新凭据的回归测试。
- [x] 6.2 为进程内及跨进程刷新锁共享一个 90 秒等待期限，超时保留凭据并返回可重试错误，支持中断；补充独立 JVM 持锁、状态读取及释放后恢复回归测试。
- [x] 6.3 运行 ChatGPT Java 定向测试、相关 Node 测试和 OpenSpec strict 校验，记录本地环境限制。证据：Java 85 项（82 通过、3 Windows-only 跳过）、Node 定向 37/37、全部 OpenSpec 39/39；标准编译与 Windows 验证仍以最新提交 CI 为准。

## 当前验证状态

Java 定向 98/98 通过；全量 Maven 的 Surefire XML 合计 1173 项，1169 通过、4 跳过、0 失败或错误。Maven 内 Node 为 332 项，300 通过、32 跳过、0 失败。由于环境缺少 ct.sym，Java 使用临时 wrapper 将 --release 21 转为 -source 21 -target 21，并非原样 --release 编译；仓库构建配置未为此改变。用户文档已验收，五项主规格已同步且全部 35 项主规格 strict 验证通过。Rust、浏览器可视、真实账户及 Windows 打包/必需检查仍未完成，因此 3.2、4.2、4.5、4.6 保持未完成且 change 不归档。详细证据与限制见 verification.md。
