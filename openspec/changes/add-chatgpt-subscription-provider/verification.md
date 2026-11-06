# 验证记录

日期：2026-11-06。以下是当前工作区的实际检查结果，不代表真实账户端到端可用或 Windows 发布验证已通过。

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
