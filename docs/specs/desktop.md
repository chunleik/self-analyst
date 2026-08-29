# self-analyst-desktop SDD 规格说明书

> Tauri 桌面壳负责单实例、Java 后端生命周期、端口握手、桌面认证、WebView 和系统托盘。

## 1. 系统标识

| 属性 | 值 |
|------|-----|
| 模块 | `self-analyst-desktop` |
| 版本 | 1.0.0 |
| 壳框架 | Tauri 2.x / Rust |
| WebView | Windows WebView2（系统 Evergreen Runtime） |
| 后端 | `self-analyst-app.jar` / Java 21 |

## 2. 架构契约

### SPEC-DSK-ARCH-001：进程与通信边界

```text
SelfAnalyst.exe
  ├─ 生成随机 desktop token
  ├─ 创建唯一端口握手文件路径
  ├─ 启动 java -jar self-analyst-app.jar
  │    ├─ 监听 127.0.0.1:<aw.port>
  │    ├─ 注册 /desktop/lifecycle/*
  │    └─ 原子发布实际端口
  ├─ token 探活 /desktop/lifecycle/health
  └─ 创建 WebView、托盘和浏览器会话链接
```

- **SPEC-DSK-ARCH-001a**：业务交互只通过回环 HTTP；启动阶段允许通过环境变量传递 token 和握手文件路径，并通过唯一临时文件返回实际端口。
- **SPEC-DSK-ARCH-001b**：Java JAR 与桌面可执行文件位于同一发布目录；受管子进程的工作目录为该目录。
- **SPEC-DSK-ARCH-001c**：WebView 加载 `http://localhost:<actualPort>/desktop-ui/`，不得硬编码 5700 覆盖 Java 配置。
- **SPEC-DSK-ARCH-001d**：桌面模式为每次启动生成随机 token；除 `/desktop/session` 外的
  `/desktop/*` 请求必须通过 header 或会话 cookie 认证。

## 3. 模块结构

```text
self-analyst-desktop/
├── package.json
├── pnpm-lock.yaml
└── src-tauri/
    ├── Cargo.toml
    ├── tauri.conf.json
    ├── capabilities/default.json
    └── src/
        ├── main.rs
        └── lib.rs
```

构建要求：Rust stable、Node.js 20+、pnpm、Java 21；Windows 发布还需要 MSVC、Windows SDK 和
系统 WebView2 Runtime。

## 4. 行为规格

### SPEC-DSK-TRAY-001：系统托盘

| 菜单 | 行为 |
|------|------|
| 显示窗口 | 显示并聚焦主窗口 |
| Web版桌面 | 打开带本次启动临时 `token` 的 `/desktop/session`，由后端设置 HttpOnly cookie 后重定向到桌面页 |
| 关于 | 展示产品、桌面壳和当前后端端口信息 |
| 退出 | 调用认证 shutdown，保存状态并退出全部受管进程 |

- 左键单击托盘图标显示主窗口。
- 关闭窗口只隐藏到托盘；“退出”是正常终止入口。
- 不提供会指向缺失 aw-webui 的“Web 仪表盘”菜单。

### SPEC-DSK-WIN-001：窗口与单实例

- 主窗口默认 1200×800，最小 800×600，启动后居中。
- Windows 使用命名 mutex 保证单实例；重复启动提示已运行并立即退出。
- 便携版依赖系统 WebView2 Runtime，不在包内重复捆绑固定版本。

### SPEC-DSK-BACKEND-001：Java 后端管理

- **SPEC-DSK-BACKEND-001a**：启动命令为 `java -jar self-analyst-app.jar`，注入
  `SELF_ANALYST_DESKTOP_TOKEN` 和 `SELF_ANALYST_DESKTOP_PORT_FILE`。
- **SPEC-DSK-BACKEND-001b**：最多等待 30 秒取得合法端口，再最多等待 30 秒使用 token 调用
  `/desktop/lifecycle/health`；任一阶段失败都退出，不回退到硬编码端口。
- **SPEC-DSK-BACKEND-001c**：端口文件内容必须为 `1..65535` 十进制整数；消费后清理。
- **SPEC-DSK-BACKEND-001d**：正常退出调用 `/desktop/lifecycle/shutdown`；异常退出依靠受管子进程机制回收 Java，不能遗留孤儿后端。
- **SPEC-DSK-BACKEND-001e**：后端启动期间提前退出时，桌面壳立即失败并提示查看后端日志。

### SPEC-DSK-WEB-001：WebView 与认证

- WebView 使用 Java 发布的实际端口加载 `/desktop-ui/`。
- 初始化脚本只为同源 `/desktop/*` fetch 注入 `X-SelfAnalyst-Token`，不得把 token 发给外部源。
- 系统浏览器入口通过 `/desktop/session?token=...` 交换 HttpOnly、SameSite=Strict cookie；失败 token 返回 403。
- 应用服务只绑定 `127.0.0.1`，并校验 Host 与 Origin 的回环边界。

## 5. 构建规格

### SPEC-DSK-BLD-001：构建产物

`scripts/build-dist.ps1` 负责编译 Java、Rust accessibility sidecar 和 Tauri 桌面壳，并组装：

```text
dist/
├── SelfAnalyst.exe
├── self-analyst-app.jar
└── tools/
```

`scripts/build-portable.ps1` 另行组装包含精简 Java 运行时的 `dist-portable/`，并在 `artifacts/`
生成 minimal/full 免安装 ZIP：

```text
dist-portable/
├── SelfAnalyst.exe
├── self-analyst-app.jar
├── runtime/
└── tools/
```

### SPEC-DSK-BLD-002：开发模式

```powershell
cd self-analyst-desktop
pnpm install
pnpm tauri dev
```

## 6. 测试规格

- `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml`
- `scripts/check-desktop-tray.ps1`
- 使用非默认 `aw.port` 验证端口握手、健康检查、WebView、浏览器入口和退出均使用同一端口。
- 验证非法/缺失端口文件、错误 token、后端提前退出和重复启动都 fail closed。
- 验证 WebView 只向同源 `/desktop/*` 注入 token，外部请求不携带认证信息。

## 7. 追溯矩阵

| 规格 ID | 文件/组件 |
|---------|-----------|
| SPEC-DSK-ARCH-001 | `src-tauri/src/lib.rs`、`App.java`、`AppSession.java` |
| SPEC-DSK-TRAY-001 | `src-tauri/src/lib.rs#create_tray` |
| SPEC-DSK-WIN-001 | `src-tauri/src/lib.rs#run`、单实例 mutex |
| SPEC-DSK-BACKEND-001 | `src-tauri/src/lib.rs#start_java`、`App#publishPort`、`AppSession#registerDesktopLifecycle` |
| SPEC-DSK-WEB-001 | `src-tauri/src/lib.rs#create_main_window`、`AwServer`、`LocalRequestGuard` |
| SPEC-DSK-BLD-001..002 | `scripts/build-dist.ps1`、`scripts/build-portable.ps1`、`tauri.conf.json` |
