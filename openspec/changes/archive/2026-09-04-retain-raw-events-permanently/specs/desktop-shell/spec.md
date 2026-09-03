## MODIFIED Requirements

### Requirement: SPEC-DSK-WEB-001 WebView 与浏览器认证
WebView SHALL 从实际后端端口加载桌面 UI。初始化脚本 SHALL 只为同源且路径以 `/desktop/` 开头的 fetch 注入 `X-SelfAnalyst-Token`，MUST NOT 向外部 origin 发送 token。系统浏览器入口 SHALL 通过 `/desktop/session?token=...` 交换设置为 `HttpOnly; SameSite=Strict; Path=/desktop` 的会话 cookie，并重定向到桌面 UI；无效 token SHALL 返回 403。服务 SHALL 校验 Host 和 Origin 的回环边界，业务接口 MUST NOT 接受 query 参数 token。

#### Scenario: 同源桌面请求
- **WHEN** WebView fetch 的目标与当前页面同源且路径位于 `/desktop/`
- **THEN** 初始化脚本注入本次启动 token header

#### Scenario: 外部请求
- **WHEN** WebView fetch 的目标不是当前页面 origin
- **THEN** 初始化脚本不注入 desktop token

#### Scenario: 浏览器会话交换
- **WHEN** 浏览器以有效 token 请求 `/desktop/session`
- **THEN** 后端设置仅作用于 `/desktop` 的 HttpOnly、SameSite=Strict cookie 并重定向到 `/desktop-ui/`

#### Scenario: 无效浏览器 token
- **WHEN** `/desktop/session` 收到错误 token
- **THEN** 后端返回 403，且不建立会话 cookie

#### Scenario: 业务接口拒绝 query token
- **WHEN** 请求只在普通 `/desktop/*` 业务 URL 的 query 中提供 token
- **THEN** 后端不把该参数视为有效认证凭据

## ADDED Requirements

### Requirement: SPEC-DSK-RAW-001 原始事件接口强制受管桌面会话
原始事件桌面接口 MUST 使用本次 Tauri 启动生成的 header token 或由 `/desktop/session` 交换的有效 cookie。后端没有配置本次启动 token 时，该接口 MUST fail-closed，且不得沿用一般桌面接口的无 token 开发降级行为。

#### Scenario: WebView 查询原始事件
- **WHEN** 受管 WebView 同源请求原始事件接口
- **THEN** 初始化脚本注入 header token，后端验证成功后才执行查询

#### Scenario: 直接启动 Java 后端
- **WHEN** Java 后端未配置 desktop token 且收到原始事件接口请求
- **THEN** 接口返回能力不可用，不执行查询也不返回 bucket 存在性
