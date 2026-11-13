# 实施核对与验证

## 能力核对与已确认调整

- 基线 `773536b`，分支 `codex/improve-chat-composer-ux`。
- 已核对 vendored Deep Chat 的 `inputAreaStyle`：隐藏内部输入区，保留消息渲染。增强/备用消息面共用外置 textarea；显式接入现有流式执行、原轮次取消和 canonical 回读。
- 已核对锁定的 Tauri 2.12.2 官方源码：`WebviewWindowBuilder::disable_drag_drop_handler()` 存在，文档明确 Windows HTML5 拖放需要禁用原生处理。仅主窗口调用；未增加文件读取命令或权限。该项 API 核对不等于 Windows 实机验收。
- 上述共享输入区和主窗口拖放调整已获用户“继续”确认，先更新 design/specs 再实施。无后端接口、图片限额、通用文件上传或 legacy drawer 范围扩张。

## 已通过

```sh
node --test self-analyst-app/src/test/js/chat-composer.test.mjs self-analyst-app/src/test/js/chat-images.test.mjs self-analyst-app/src/test/js/chat-style.test.mjs self-analyst-app/src/test/js/deep-chat-adapter.test.mjs self-analyst-app/src/test/js/chat-session-routing.test.mjs
```

90 项通过，0 失败、0 跳过。覆盖共享草稿、布局边界、IME 守卫、状态/停止/重试、图片输入校验、解码失效、切换会话以及准备失败不污染另一会话草稿。

```sh
node --test self-analyst-app/src/test/js/*.test.mjs
```

411 项：379 通过、32 跳过、0 失败。跳过项为现有测试的环境条件分支，不能算作已验证。

```sh
openspec validate improve-chat-composer-ux --strict
openspec validate desktop-chat --type spec --strict
openspec validate chat-image-input --type spec --strict
git diff --check
```

全部通过。使用 OpenSpec CLI 2.0.0；两个主规格已按 sync-specs 流程同步，未归档。中英文 locale 键集合一致，README.md 新增内容无中文字符。新增脚本语法检查通过。

本地静态审查 fixture `chat-composer-review-server.mjs` 使用真实资源和合成会话；首页、初始化脚本、composer 脚本、样式 HTTP 读取均为 200。该检查不等于视觉验收。

## Maven 全量验证

`mvn test` 最终为 **BUILD SUCCESS**，7 个 reactor 模块全部成功，耗时 2 分 53 秒。Java Surefire 总计 1372 项，失败 0、错误 0、跳过 8；Maven 集成 Node 运行 416 项，384 通过、32 跳过、0 失败（包含 review-server 文件发现，故多于显式 `*.test.mjs` 命令）。

实际调用使用临时官方 Maven 3.9.9、Temurin JDK 21、隔离依赖缓存及当前环境代理；JDK 使用机器已有 Java 信任库，没有关闭 TLS 校验、没有改系统安全设置。首次尝试缺少编译器，后续依赖下载遇到代理证书错误；补齐 JDK 并使用现有信任库后完整重跑成功。

```sh
JAVA_HOME=<JDK21> PATH=<JDK21>/bin:$PATH MAVEN_OPTS='-Djavax.net.ssl.trustStore=<system-java-cacerts>' mvn -B -ntp -U -s <environment-proxy-settings> -Dmaven.repo.local=<temporary-cache> test
```

## 尚未完成的实机验收

当前为 Linux 云环境，没有 Windows Tauri/WebView 或真实中文输入法，未运行 Windows `pnpm tauri dev`；Rust 编译链也不可用。浏览器截图尝试被 Chromium 沙箱启动错误 `Operation not permitted` 阻断，无 UI 截图可交付。因此任务 4.2 保持未勾选。

需在 Windows 补验：增强和备用消息面、中英文、最小支持窗口、长文本/四图、真实 IME 候选确认、系统 PNG/JPEG 拖放、连续发送、停止和切会话，保存截图。现有应用未发现完整深色主题切换实现；本次仅沿用共享设计令牌，不能宣称深色已验收，也未另增全局主题功能。

## 交付边界

初次实施报告时未提交或推送；用户后续已授权创建 PR、通过检查后合并并发版。代码完成不等于所有验收完成；后续必需 CI 和 Windows 验收不能以 Node 替身或 OpenSpec 校验替代。
