# 验证记录

日期：2026-11-06。以下是当前工作区的实际检查结果，不代表真实账户端到端可用或 Windows 发布验证已通过。

## 后续远端发布检查

- PR [#136](https://github.com/chunleik/self-analyst/pull/136) 的 `9a3b2b2a4aa10ab3f5eee20d0ea2fd64d58b5521` 已通过 Windows、Ubuntu、macOS 三平台数据守卫及 Windows Rust 格式检查。
- 同一提交的 [Windows 完整检查](https://github.com/chunleik/self-analyst/actions/runs/42240610296/job/127969814647) 在 Java 测试中失败：Windows 提升权限创建凭据对象后的所有者与当前用户不一致，触发严格存储校验。App 模块 626 项中 1 failure、20 errors、4 skips；后续 Rust/Clippy、UIA、JAR 及发布包检查未运行。这是待修复的真实平台问题，不以本地通过替代远端门禁。
- 前一个提交仅有 Rust 格式差异，已按 CI 精确差异修复。所有后续结果必须重新核对新提交，不能沿用上述旧 head 作为合并依据。
- 修复限定在本次独占创建、仍为空且已具备仅当前用户 ACL 的对象上初始化所有者，使用 NOFOLLOW_LINKS 的 ACL view；既有对象仍直接严格校验，不自动修补。最终定向命令 `mvn-build -pl self-analyst-app -am '-Dtest=ChatGptCredentialStoreTest,ChatGptSessionServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 得到 26 项 Java 通过、3 项 Windows-only 回归待远端执行；同轮 Node 300 通过、32 跳过，未进行本地打包。新增跨平台 owner 校验与 Windows owner/ACL 篡改测试，新提交仍须重新通过远端门禁。

## 已通过

- 已获许可后完成 Java 针对性验证：98 项，98 通过，0 失败、错误或跳过。包含 ChatGPT 认证、受保护存储、签名身份、目录、Responses、额度暂停，以及配置、热更新、桌面路由、API Key 兼容和失败分类测试。
- 已完成跨模块 Maven test：各模块 Surefire XML 合计 1173 项，1169 通过、4 跳过、0 失败、0 错误；全量日志各 Maven reactor 均为 SUCCESS。四项跳过分别为原始事件容量 benchmark、手工 UIA smoke、Linux 不适用的 Windows UIA smoke 和文档 benchmark。
- 上述 Java 结果均使用临时编译器 wrapper，详见下节；不能描述为原样 `--release 21` 编译或 Windows 验证通过。
- 仓库根目录执行 `node --test self-analyst-app/src/test/js/chatgpt-settings.test.mjs self-analyst-app/src/test/js/chatgpt-runtime-packaging.test.mjs self-analyst-app/src/test/js/llm-settings.test.mjs`：36 项通过，0 失败。
- 仓库根目录执行 `node --test self-analyst-app/src/test/js/*.test.mjs`：327 项，295 通过，32 跳过，0 失败。
- 按 Maven 中的 Node 工作目录及命令，在 `self-analyst-app` 执行 `node --test`：332 项，300 通过，32 跳过，0 失败。Node 自动发现范围比显式 `*.test.mjs` 更广，计数不可混用。
- `chatgpt-runtime-packaging.test.mjs` 验证 `scripts/build-portable.ps1` 的 jlink 模块集合包含 `jdk.httpserver`。这是静态配置回归，不是实际便携包构建。
- `openspec validate add-chatgpt-subscription-provider --strict`：本次补入待验证注册、加密 reasoning、额度暂停及打包范围后再次通过。命令使用工作区已存在的 `@fission-ai/openspec` CLI，经 `node` 执行，未安装新工具。
- `git diff --check -- openspec`：通过。
- 已同步 `agent-runtime`、`chatgpt-auth`、`chatgpt-responses`、`llm-settings`、`user-configuration` 五项主规格；`openspec validate --specs --strict --no-interactive`：35 项通过，0 失败。主规格同步只代表契约与已实现行为一致，不表示未运行的验收已完成。
- `docs/llm-settings.md`、`docs/architecture.md`、`PRIVACY.md`、`README.md` 与 `README.zh-CN.md` 已更新并核对实现、权限保护限制、预览资格、API Key/Embedding 隔离和真实账户验证边界；文档 diff 检查通过，README 英文新增内容没有混入中文段落，两版本说明一致。

## Java 编译环境与证据

本机缺少支持原样 `javac --release 21` 所需的 `ct.sym`。本次临时 wrapper 只接受 release 21，将该参数转换为 `-source 21 -target 21`，仍通过已安装 Java 21 的 `jdk.compiler/com.sun.tools.javac.Main` 编译。仓库 Maven 配置没有为绕过该环境问题而修改。该方式验证当前 Java 21 环境的编译及测试，不提供 `--release` 对目标标准 API 集的同等限制，发布前仍需完整 JDK 21 的标准构建。

当前工作区的证据为各模块 `target/surefire-reports/TEST-*.xml`，并对照临时构建目录中的针对性与全量 wrapper 测试日志核对；针对性日志显示 98 项通过，全量日志显示 reactor BUILD SUCCESS，XML 汇总确认 1173 项及 4 项跳过。运行日志与 wrapper 是本次环境产物，不提交到仓库。

实际成功执行的命令如下，工作目录均为仓库根目录：

```sh
/tmp/selfanalyst-build/mvn-build -pl self-analyst-app -am '-Dtest=ChatGpt*Test,ConfigTest,LlmSettingsServiceTest,LlmRuntimeManagerTest,DesktopLlmSettingsControllerTest,SelfAnalystAgentOpenAiCompatibilityTest,PlainTransportFailureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test > /tmp/selfanalyst-build/targeted-wrapper-tests.log 2>&1
/tmp/selfanalyst-build/mvn-build test > /tmp/selfanalyst-build/full-wrapper-tests.log 2>&1
```

`mvn-build` 调用临时安装的 Maven 3.9.11，附带 `-U -B -ntp`、临时本地仓库、`-Dmaven.compiler.fork=true` 和 `-Dmaven.compiler.executable=/tmp/selfanalyst-build/javac21-wrapper`。Maven settings 文件在每次运行时按进程 ID 生成，不能把临时路径当作通用项目配置。上面的 wrapper 入口是当次真实命令，不要求其他环境复制临时构建路径；正常完整 JDK 21 环境仍应运行仓库标准命令。

## 已检查但不能标为运行验证通过

- 原生系统浏览器入口已增加官方授权目标、PKCE 及回环 callback 校验。发现并修正了反斜杠字符字面量转义问题，补充 Node 源码字节检查；仍未执行 Rust 编译或原生测试。

## 未完成及原因

- 完整 JDK 21 的原样 `--release 21` 编译：本机缺少 ct.sym，未通过该原样编译路径；临时 wrapper 的 source/target 编译与测试已通过，但不可混称。
- Rust/Cargo 验证：当前环境无 Cargo、rustc 或 rustfmt，未运行。
- 浏览器可视与原生授权交互：Chromium 在加载页面前因 `socket() Operation not permitted` 退出；未获得页面布局截图，DOM 测试不替代视觉验收。
- 真实 ChatGPT 账户 OAuth、套餐权限、模型目录和 Responses 端到端调用：未运行。协议夹具不能证明真实账户已获授权或订阅可用。
- Windows 便携包构建与运行、远端 Windows 必需检查：未运行。
- 归档：虽然五项主规格已同步，原生、可视、Windows 与真实账户验收尚未完成，change 继续保持活动，不归档。

## 评审修复验证（任务 5.1–5.4）

修复 PR #136 评审前三项：刷新/撤销网络请求持有存储锁、轮换 refresh token 在身份校验失败时丢失、plain/summary 被 45 秒固定期限截断。

- Java 定向（WSL，OpenJDK 21.0.12，原样 `--release 21` 编译，未使用 wrapper）：
  `mvn -B -s ~/.m2/settings-aliyun.xml -pl self-analyst-app -am '-Dtest=ChatGpt*Test,LlmRuntimeManagerTest,LlmSettingsServiceTest,PlainTransportFailureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
  结果 77/77 通过，BUILD SUCCESS。其中 ChatGptSessionServiceTest 20 项（新增刷新中状态不阻塞、其它进程已轮换、轮换先落盘后校验、身份明确失败与 sub 不匹配清除 token，并改写注销与刷新交错用例为两枚 refresh token 均撤销），ChatGptIdTokenVerifierTest 7 项（新增元数据不可达返回 `chatgpt_identity_unavailable`），ChatGptResponsesModelTest 14 项（新增可配置流期限），新增 ChatGptRuntimeTimeoutTest 1 项。
- 合入 main `0b3d91e`（含 Windows 新建对象所有者初始化）后重跑同一命令：83 项，80 通过、3 项 Windows-only 跳过、0 失败；`.refresh-lock` 新建时同样执行新对象所有者初始化。
- Node：`node --test self-analyst-app/src/test/js/chatgpt-settings.test.mjs self-analyst-app/src/test/js/llm-settings.test.mjs self-analyst-app/src/test/js/chatgpt-runtime-packaging.test.mjs` 37/37 通过（Node 24.18.0）。
- OpenSpec：change strict 与全部主规格 strict 校验通过。
- 未运行：跨模块完整 `mvn test`、Windows 必需检查和真实账户验收，仍按 4.2、4.5、4.6 处理。本机可原样 `--release 21` 编译仅说明此次环境，不替代 4.2 的完整 Windows 验证。

## ChatGPT 生成测试协议错误回归（2026-11-07，任务 2.4）

- 用户截图中的模型配置已保存，最小生成测试返回 `protocol`。定位当前安装版本后，使用其自身 `ChatGptSessionService` 与原请求构造逻辑发送固定 `Reply OK.` 提示；仅输出 HTTP 状态、事件类型、数量和本地解析结果，不输出或保存令牌、账户信息或生成正文。
- 两次诊断均观察到 HTTP 200、缺少 Content-Type、有效文本事件及 `response.completed`，完成对象包含一项输出且无错误。把相同响应正文交给原有严格解析器可正常完成，确认失败发生在解析前的响应头校验。没有把所有 `protocol` 错误归因于模型权限或模型 ID。
- 修复仅允许缺失 Content-Type 的响应进入原有严格 SSE 解析器；明确的非 SSE 媒体类型仍拒绝。普通 JSON、HTML、空响应、仅文本增量、失败事件和不完整事件仍不能报告成功，保留超时、响应大小及单次请求限制。实现保持 SPEC-CGRESP-002/003/004 的既有契约，无需改变主规格。
- 新增两项 `ChatGptResponsesModelTest` 回归，模拟缺失头成功及六种无效正文、明确错误媒体类型。旧安装类与修复前 Maven 测试均复现 `ChatGPT request failed: PROTOCOL`（响应头校验位置）；修复后通过。
- JDK 21 原样编译及定向验证：`mvn -pl self-analyst-app -am '-Dtest=ChatGptResponsesModelTest,ChatGptInferenceGateTest,ChatGptRuntimeTimeoutTest,ChatGptModelCatalogTest,DesktopLlmSettingsControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，Java 23/23 通过，附带 Node 335 项（334 通过、1 跳过），BUILD SUCCESS。初次 Maven 编译与编辑同时发生导致缓存旧类，更新源文件时间戳后重新编译验证通过，未修改构建配置。
- 使用 `javac --release 21` 编译的修复类覆盖诊断进程的类路径，复用已安装应用依赖和自身授权组件，以 `gpt-6.1-sol` 进行一次最小生成验证：有效文本、完成状态和 usage 均存在。此验证没有替换安装文件或重启用户应用；只证明本次模型生成链路，不代替登录、刷新、断开、工具续轮或全部账户套餐验收。
- `openspec validate add-chatgpt-subscription-provider --strict` 与 `git diff --check` 通过。其它未完成任务保持原状态，change 不归档。
- 协议核对参考：[模型与推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[Responses 流式事件](https://developers.openai.com/api/reference/resources/responses/streaming-events)。缺失 Content-Type 是本次实测结果，并非宣称官方文档保证该响应头缺省。

## 模型设置错误文案回归（2026-11-07，任务 3.1）

- 截图中的 ChatGPT 模型 ID 为空，`LlmSettingsService` 按既有规格拒绝保存；前后端正式语言资源均缺少 `error.llmSettings.chatgptModel`，导致界面显示内部错误键。同类 `provider` 和 `chatgptAccount` 校验资源也缺失。
- 补齐桌面 UI 与服务端 `messages` 的中英文资源，模型提示明确指引填写 ID 或加载列表后选择。保持非空校验、显式发现模型及不自动保存的契约，无需修改主规格。
- 新增 `i18n.test.mjs` 回归，通过实际 API 错误处理与模型设置状态机验证两种语言的三个错误码、前后端文案一致及失败后草稿保留。修复前复现 `操作失败：error.llmSettings.chatgptModel`，修复后通过。
- `node --test self-analyst-app/src/test/js/i18n.test.mjs self-analyst-app/src/test/js/i18n-catalog.test.mjs self-analyst-app/src/test/js/llm-settings.test.mjs`：18/18 通过；`node --test self-analyst-app/src/test/js/chatgpt-settings.test.mjs`：26/26 通过。
- `openspec validate add-chatgpt-subscription-provider --strict` 与 `git diff --check` 通过。本次仅语言资源和测试修复，未重新打包运行中的桌面应用，也未执行真实账户生成验证；原有未完成验收任务保持未完成，change 不归档。

## PR #137 追加评审修复验证（任务 6.1–6.3）

- 成功重新登录清除旧 pendingIdToken，并保护新凭据不被迟到的旧身份校验失败清除。两项新增会话回归先在未修复实现上分别复现 `chatgpt_refresh_failed` 与 `chatgpt_sign_in_required`，修复后通过。
- 进程内刷新锁、准备阶段短时存储锁及跨进程文件锁共享单调时钟计算的 90 秒等待期限；文件锁改为非阻塞尝试，超时不执行刷新、不修改凭据，中断保留标记。新增独立 JVM 持锁下的超时、状态读取不阻塞、中断及释放后恢复测试；Windows 对象保护测试增加 `.refresh-lock` 所有者和 ACL 断言。
- Java 定向：`mvn -pl self-analyst-app -am '-Dtest=ChatGpt*Test,LlmRuntimeManagerTest,LlmSettingsServiceTest,PlainTransportFailureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，85 项，82 通过、3 项 Windows-only 跳过、0 失败或错误。此云环境有 Java 21 运行时及编译模块，但缺少 `ct.sym`，故使用仓库外临时编译 wrapper 将 `--release 21` 转为 `-source 21 -target 21`；没有修改项目 POM，也不将此结果当作标准编译或 Windows 验证。
- Maven 内全套 Node：332 项，300 通过、32 跳过、0 失败；ChatGPT/settings/packaging 三个定向文件另跑 37/37 通过。
- `openspec validate --all --strict`：39/39 通过；`git diff --check` 通过。
- 最新提交的标准 Java 21 编译、Windows 全量验证与其它必需 GitHub Actions 检查需在合并前另行确认。真实账户端到端验收仍未完成，不归档该 change。

## 2026-11-07 Windows 真实账户 HTTP 400 修复（任务 7.1–7.3）

本节补充并更新上述历史验证限制：本次在 Windows 11、Oracle JDK 21.0.9、Maven 3.9.11 下使用原样 `--release 21` 编译，无 wrapper。用户明确授权测试当前 SelfAnalyst 的 ChatGPT 连接；仅使用该应用自身的已保存账户，固定短提示，不发送真实会话、活动记录或文件信息，不输出或保存令牌。

### 真实服务定位

- 当前模型 `gpt-6.1-sol`：单轮用户输入 HTTP 200，收到 `response.completed`，文本 `OK`。
- 添加 `role=assistant`、`content.type=input_text` 的合成历史：稳定返回 HTTP 400，`code=invalid_value`，`param=input[1].content[0]`；诊断说明该位置仅支持 `output_text` 和 `refusal`。
- 只将助手文本类型改成 `output_text`：HTTP 200、`response.completed`、文本 `OK`。系统/开发者、用户与工具结果文本仍使用 `input_text`。
- 实际 Java 适配器进一步复现 `PROTOCOL`：官方 Responses 端点返回 HTTP 200 和完整 SSE，但缺少 Content-Type。修复允许缺省头的正文进入既有有界 SSE 校验，显式错误类型仍拒绝；有效完成事件仍是成功前提。
- 官方历史消息转换示例也按角色区分内容类型：https://developers.openai.com/api/docs/assistants/migration 。

### 回归与真实复测

- 先运行旧实现：两个新增历史编码测试失败，均为 expected output_text / actual input_text；新增无 Content-Type 的完整流测试在兼容修复前以 PROTOCOL 失败。
- 定向测试命令（本机使用 Maven 镜像）：`mvn -pl self-analyst-app -am '-Dtest=ChatGpt*Test,ChatImageModelTest,LlmHotReloadIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- Java 共 91 项，88 通过、3 按平台条件跳过、0 失败或错误。ChatGptResponsesModelTest 18/18 通过，覆盖真实 AgentScope 聚合后的第二轮、混合文本/工具顺序、缺省响应头、显式错误类型、普通 JSON、HTML、失败事件和截断流。Maven 内 Node 共 334 项，333 通过、1 跳过、0 失败。
- 同步主分支 `7595410` 后保留 #141 已有的 SSE 修复及回归，用同一命令复验。Java 仍为 91 项（88 通过、3 跳过，ChatGptResponsesModelTest 18/18），Node 345 项（344 通过、1 跳过），两者均无失败。
- 修复后的 Java 适配器使用真实账户执行连续两轮固定提示，两轮均完整结束并返回 `OK`；使用最终本机修复 JAR 再次执行同一测试，两轮同样成功。
- `openspec validate add-chatgpt-subscription-provider --strict` 通过；`openspec validate --specs --strict` 35/35 通过。主规格同步新增两个协议场景，用户指南增加连接测试与续聊故障说明。

### 本机应用验证与交付边界

- 保留已安装版本其余内容，只更新 `com/selfanalyst/chatgpt/ChatGptResponsesModel.class`；安装前比较类接口并逐项核对 JAR，唯一变化是该 class。
- 原 JAR 备份为 `%LOCALAPPDATA%/SelfAnalyst/self-analyst-app.jar.before-chatgpt-400-20261107`。停止并重新启动专属应用进程后，后端和桌面正常启动；未修改用户配置、OAuth 凭据或聊天数据。
- 在重启后的真实桌面模型设置点击“测试生成连接”，显示“验证成功 (3083 ms)”，配置保持无未保存更改。
- 上述真实账户和桌面测试使用本机修复包。完整浏览器 OAuth、账户切换、刷新及断开验收仍属于任务 4.2、4.5、4.6 的验证范围，现有 change 保持活动。
