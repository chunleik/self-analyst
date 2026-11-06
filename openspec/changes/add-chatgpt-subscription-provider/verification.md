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

## PR #137 追加评审修复验证（任务 6.1–6.3）

- 成功重新登录清除旧 pendingIdToken，并保护新凭据不被迟到的旧身份校验失败清除。两项新增会话回归先在未修复实现上分别复现 `chatgpt_refresh_failed` 与 `chatgpt_sign_in_required`，修复后通过。
- 进程内刷新锁、准备阶段短时存储锁及跨进程文件锁共享单调时钟计算的 90 秒等待期限；文件锁改为非阻塞尝试，超时不执行刷新、不修改凭据，中断保留标记。新增独立 JVM 持锁下的超时、状态读取不阻塞、中断及释放后恢复测试；Windows 对象保护测试增加 `.refresh-lock` 所有者和 ACL 断言。
- Java 定向：`mvn -pl self-analyst-app -am '-Dtest=ChatGpt*Test,LlmRuntimeManagerTest,LlmSettingsServiceTest,PlainTransportFailureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，85 项，82 通过、3 项 Windows-only 跳过、0 失败或错误。此云环境有 Java 21 运行时及编译模块，但缺少 `ct.sym`，故使用仓库外临时编译 wrapper 将 `--release 21` 转为 `-source 21 -target 21`；没有修改项目 POM，也不将此结果当作标准编译或 Windows 验证。
- Maven 内全套 Node：332 项，300 通过、32 跳过、0 失败；ChatGPT/settings/packaging 三个定向文件另跑 37/37 通过。
- `openspec validate --all --strict`：39/39 通过；`git diff --check` 通过。
- 最新提交的标准 Java 21 编译、Windows 全量验证与其它必需 GitHub Actions 检查需在合并前另行确认。真实账户端到端验收仍未完成，不归档该 change。
