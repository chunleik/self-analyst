## Context

动机和范围见 proposal.md。现有模型设置服务只管理一个 OpenAI-compatible 连接，模型运行管理器在工作开始时固定模型版本，配置保存经过统一校验、候选准备、持久化与发布。新增 OAuth 涉及浏览器、本机监听、凭据与网络目标边界，不能把 bearer token 填入现有 `llm.api-key` 来复用旧流程。

官方 Sign in with ChatGPT 开源客户端预览文档已描述动态注册、直接使用订阅额度的 scope 和 Responses 路径。预览可用性、账户套餐及授予权限仍由服务端决定，不能宣称所有订阅都支持。本地模拟服务可验证协议实现，但不替代真实账号验收。

## Goals / Non-Goals

**Goals:**
- 在既有单连接架构中区分 API Key 与 ChatGPT OAuth，保留现有热更新和凭据语义。
- 把 OAuth 状态机、受保护存储及 Responses 协议适配隔离于 UI 和通用配置。
- 对登录中断、重复发起、凭据轮换、注销、过期回调及网络失败提供可重现测试。

**Non-Goals:**
- 不复用 Codex/ChatGPT 官方客户端 ID、Cookie、登录文件或未公开接口来绕过服务端能力限制。
- 不创建任意 OAuth 服务通用代理，不让用户提供任意目标来接收既有令牌。
- 不承诺订阅配额、模型清单、免费额度或后台自动摘要可用性；不新增 Embedding OAuth。

## Decisions

### 1. 认证方式与普通配置分离

使用 `llm.provider`（默认 `api-key`，另有 `chatgpt`）、`llm.chatgpt-account` 和 `llm.chatgpt-model` 作为非敏感配置，OAuth 凭据不进入 TOML。API Key 的地址、模型、密钥和温度仍使用原字段。ChatGPT 账户注册表保留多个账户，活动连接只选择其中一个，模型未选择时不猜测固定默认模型。模型设置查询返回认证方式和受限登录状态。切换连接不清除另一种认证方式的凭据，断开 OAuth 仅清除其凭据。相比把令牌当作 API Key，此方式能隔离刷新、日志脱敏、目录和模型路由，避免 Embedding 继承 OAuth 令牌。

### 2. 使用官方动态客户端协议和签名身份

授权端点固定为 `https://auth.openai.com/api/accounts/authorize`，首次使用官方文档规定的 `dynamic_agent_client`、本应用真实 `agent_name_hint=SelfAnalyst` 及稳定 `ext_agent_host_id`。动态注册由该授权流程完成，不向假设的 RFC 7591 registration endpoint 发请求，也不复用 Codex/其它应用 client ID。回调拿到本应用获发 client ID 后在 `https://auth.openai.com/api/accounts/oauth/token` 交换令牌，scope 包含 `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`，resource 为 `https://api.openai.com/v1`。

每次授权使用 S256 PKCE、随机 state 和 nonce。通过受信任 JWKS 验证身份签名及 iss、aud、exp、nonce，返回账户还须匹配 sub；不能仅解码 JWT 信任其内容。以已验证账户 sub 与获发 client ID 维护注册关系。令牌有效但缺少 direct scope 时保留注册，推理禁止并返回 `chatgpt_plan_permission_required`，后续重新授权复用获发身份并请求 consent。令牌及注册相关请求禁用重定向并限制超时、大小和目标。

完成受保护回调校验后可先保存服务端获发 client ID 为待验证注册，使令牌交换、JWKS 或网络失败后重试及重启不重复注册。待验证注册不进入可选账户清单，不包含可使用的模型身份；只有签名身份通过验证后才能激活账户并保存 token。

### 3. 授权尝试具有独立代次和有效期

回调服务器仅监听回环地址，并限制路径和单次有效消费；授权尝试保存 state、verifier、回调信息和有限期限。取消、新登录、超时或关闭使旧代次失效，迟到回调及刷新都必须检查代次。前端只接收授权地址和状态，不接收授权码或令牌；浏览器打开失败时保留可重试提示，不把失败伪报为登录成功。

### 4. 凭据存储独立且默认拒绝不安全降级

在所配置 memory 根目录下的独立 `chatgpt-auth` 目录保存受保护凭据；POSIX 目录/文件权限为 0700/0600，Windows 使用仅当前用户 ACL 并在无法设置时失败。任何不支持保护的环境明确报错或使用内存态。串行刷新、文件锁、原子替换与限制权限一起保障刷新落盘；注销尝试发现并调用受信任 revocation endpoint，无论远端结果如何清除本地 token，失败时返回未确认标志；保留稳定 host 与已注册客户端信息供重新授权。刷新保留 earliest_refresh_at，临时错误保留 token，服务端明确终止凭据时清除 token 而保留注册。错误不得打印服务端原文、令牌或 PKCE verifier。凭据是否能被同一账户的恶意进程读取属于本地威胁边界，权限保护不能描述成硬件隔离或跨用户保险库。

Windows 的提升权限进程可能把新对象的默认所有者设为 Administrators，而不是当前用户。只允许对本次独占创建、类型与仅当前用户 ACL 已校验且尚未写入凭据的空目录、锁文件和唯一临时文件初始化当前用户所有者，随后执行完整严格校验。已有对象仍必须直接通过所有者和 ACL 校验，不接管外部所有者、不修补未知权限，也不因 CI 环境而跳过安全测试。

刷新与存储使用两把锁：存储锁只保护短时读取、比对和原子写入；独立的刷新锁（进程内锁加 `.refresh-lock` 文件锁，新建时同样只初始化本次独占创建的空对象所有者）在同一凭据目录内串行化刷新网络请求，避免状态查询、连接判断和其它账户读取等待远端响应。刷新网络请求在存储锁外执行，写回前比对存储中的 refresh token 仍是本次所用值，因此注销、新登录或其它进程的较新轮换都会使迟到结果被丢弃；被丢弃的轮换 refresh token 尽力远端撤销。收到终止错误时也先比对：存储已被其它进程更新则不清除，改用最新凭据。

令牌端点返回 200 后，轮换结果先持久化，返回的 ID token 记为待校验；待校验期间不交出新 access token。身份校验分为“暂时不可用”（discovery/JWKS 网络或响应失败）与“明确无效”：前者保留凭据并返回可重试错误，下次调用只重试校验、不重复刷新；后者或 sub 不匹配时清除 token 要求重新登录。注销先在存储锁内清除本地凭据，再在锁外撤销远端 refresh token。

### 5. Responses 适配复用业务层，不复用 Chat Completions 线协议

模型工厂依据认证方式选择适配器。新适配器处理 Responses 消息、图片、函数工具、工具结果、SSE 完成事件及 usage，向上保留既有模型接口。对话模型维持 45 秒、单次生成尝试；plain/summary 模型与 API Key 路径一致，以调用方超时为准，内部上限 5 分钟（等同 AgentScope `ExecutionConfig.MODEL_DEFAULTS`），避免 60 秒或 120 秒的摘要调用被固定 45 秒截断。无输出上限、用量至多一次和取消释放维持现有契约；刷新只能在发出业务生成前完成，避免在一个预算准入内隐式重试生成。ChatGPT 不支持的 temperature 等参数省略，UI 明确说明参数限制。

无服务端会话存储的请求设置 `store=false`，请求 `reasoning.encrypted_content` 并将经过形状与大小校验的不透明 reasoning 项保留在消息元数据中，以便工具续轮按协议原样回传。它们不转换为可见的思考文本或日志，不重复注入完成事件中的文本。只有明确白名单中的服务端机器错误码映射为权限、认证、额度或能力错误，未知错误保守地归为协议失败。

### 6. 登录状态与配置状态不混为一谈

登录完成只表示获得当前凭据，不代表某个模型可用或草稿已保存。新发起的登录成功后可将刚授权账户选入草稿，模型及其它草稿字段保持不变。保存仍走统一配置提交；远端生成成功只由显式最小测试或实际调用确认。模型发现不存在时保留手动 ID，不把固定清单描述成账户授权结果。UI 使用请求代次忽略旧响应，关闭、切换、取消及重复点击不能恢复旧授权界面或覆盖新草稿。

### 7. 额度暂停与运行时打包边界

确认的 ChatGPT 额度/限流错误通过进程内按账户的 gate 阻止后续业务模型请求，避免后台摘要重复消耗请求；其它账户和 API Key 不受影响。显式最小生成测试绕过暂停，只有成功时恢复该账户；进程重启重置暂停，但不会增加服务端真实额度。UI 展示暂停状态并保留显式测试入口，不将目录成功、配置保存或页面刷新视为恢复证据。

OAuth 使用 Java 回环 HTTP server，因此 `scripts/build-portable.ps1` 的 jlink 模块列表必须包含 `jdk.httpserver`。Node 源码断言只能证明模块配置存在，不能替代 Windows 真实打包与启动回调验证。

## Risks / Trade-offs

- [预览未向当前账户开放或缺少直接访问 scope] → 明确返回不支持，保留 API Key；文档列出前提，不借用官方客户端身份。
- [浏览器回调被其它进程或旧页面触发] → 回环监听、随机 state、S256 PKCE、单次消费、超时和代次检查。
- [并发刷新或注销后迟到结果恢复凭据] → 同一连接串行刷新，并在写回前验证当前代次。
- [OAuth 令牌泄漏到用户自定义 Base URL 或 Embedding] → 固定信任目标、认证方式隔离、禁止重定向和负向测试。
- [Responses 与 Chat Completions 参数/工具语义不同] → 独立适配及协议夹具测试，覆盖分段工具参数、SSE 完成、失败与用量。
- [当前平台不能安全持久化] → 明确显示限制，不静默降级为 TOML 明文；用户可继续使用原有 API Key。

## Migration Plan

1. 既有配置缺少认证方式时继续使用 API Key，不迁移或复制密钥。
2. 添加配置和协议测试后接入桌面 UI，再运行针对性与聚合检查。
3. 用户显式选择并授权 ChatGPT 后才可使用对应连接；失败时保留现有配置与草稿。
4. 回退版本前切换至 API Key；OAuth 凭据独立于普通配置和本地会话，不修改活动数据格式。
5. 只有实现验证完成后同步主规格并归档，真实账户验收未完成时记录限制，不声称已验证可使用订阅。

## References

- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery
- https://developers.openai.com/siwc/token-sharing-open-source/token-reference

这些资料用于确认协议和预览边界，不能代替使用真实账户完成端到端验证。
