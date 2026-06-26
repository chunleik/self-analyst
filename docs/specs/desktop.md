# self-analyst-desktop SDD 规格说明书

> Specification-Driven Development — Tauri 桌面壳，封装 desktop-ui + 系统托盘。

---

## 1. 系统标识

| 属性 | 值 |
|------|-----|
| 产品名称 | SelfAnalyst Desktop |
| 版本 | 1.0.0 |
| 壳框架 | Tauri 2.x (Rust + TS) |
| 前端 | desktop-ui 轻量桌面页 |
| 后端 | self-analyst-app.jar (Java 21, 已有) |

---

## 2. 架构契约

### SPEC-DSK-ARCH-001: 进程边界

```
┌─ self-analyst-desktop ────────────────────────┐
│                                                │
│  ┌─ Tauri 壳 (Rust) ────────────────────────┐ │
│  │  窗口管理 + 系统托盘 + 子进程管理          │ │
│  │   └─ WebView2 (系统原生) ────────────────┐ │ │
│  │      渲染 desktop-ui                     │ │ │
│  │       └─ HTTP: http://localhost:5700 ─┐  │ │ │
│  └───────────────────────────────────────┘  │ │ │
│                                            │  │ │
│  ┌─ Java 后端 (子进程) ──────────────────┐ │  │ │
│  │  AwServer + Watchers + Content + Audio│←┘  │ │
│  │  端口: 5700                            │     │ │
│  └────────────────────────────────────────┘     │ │
│                                                 │ │
└─────────────────────────────────────────────────┘ │
                                                  │
```

- **SPEC-DSK-ARCH-001a**: Rust 壳与 Java 后端仅通过 HTTP (localhost:5700) 通信，无其他 IPC。
- **SPEC-DSK-ARCH-001b**: Tauri 启动时自动拉起 Java 后端子进程，退出时终止。
- **SPEC-DSK-ARCH-001c**: WebView 默认加载 `http://localhost:5700/desktop-ui/`。完整 Web 仪表盘（aw-webui，MPL-2.0）不随仓库分发，缺失时 `http://localhost:5700/` 返回 404，桌面端不提供入口。
- **SPEC-DSK-ARCH-001d**: Windows 上使用命名 mutex 保证桌面端单实例运行；重复启动只提示 `SelfAnalyst 已在运行`，不创建窗口、托盘或 Java 后端。

---

## 3. 模块结构

```
self-analyst-desktop/
├── src-tauri/
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   └── src/
│       ├── main.rs              # 入口: 调用库 run()
│       └── lib.rs               # 单实例、Java 子进程、窗口和托盘
├── package.json
└── README.md
```

**前置依赖**：
- Rust (stable)
- Node.js 18+ (pnpm/npm)
- Visual Studio Build Tools (Windows, 用于编译 Rust)
- Java 21 (已安装)

---

## 4. 行为规格

### SPEC-DSK-TRAY-001: 系统托盘

| 菜单项 | 行为 |
|--------|------|
| 显示窗口 | `window.show()` + `window.set_focus()` — 显示并聚焦隐藏窗口 |
| Web版桌面 | `open::that("http://localhost:5700/desktop-ui/")` — 系统默认浏览器 |
| 关于 | 使用原生消息框显示版本、桌面端和后端服务信息 |
| 退出 | 终止 Java 子进程 → `app.exit(0)` |

- **SPEC-DSK-TRAY-001a**: 托盘只允许由 Rust 代码手动创建，`tauri.conf.json` 不配置 `app.trayIcon`，避免自动托盘和手动托盘重复出现。
- **SPEC-DSK-TRAY-001b**: 手动托盘必须显式使用默认窗口图标，避免 Windows 隐藏图标区出现空白或不可见图标。
- **SPEC-DSK-TRAY-001c**: 左键点击托盘图标 → 显示并聚焦窗口。
- **SPEC-DSK-TRAY-001d**: 托盘不提供「Web仪表盘」入口——完整仪表盘（aw-webui，MPL-2.0）不随仓库分发，避免指向 404。如本地放置了 aw-webui，可直接在浏览器访问 `http://localhost:5700/`。

### SPEC-DSK-WIN-001: 窗口行为

- 初始大小：1200×800，可调整
- 最小尺寸：800×600
- 标题：`SelfAnalyst`
- **SPEC-DSK-WIN-001a**: 关闭按钮 → `window.hide()` 隐藏到托盘，不退出
- **SPEC-DSK-WIN-001b**: 窗口 `data_directory` 指向 `./data/desktop/`

### SPEC-DSK-BACKEND-001: Java 后端管理

- **SPEC-DSK-BACKEND-001a**: Tauri `setup` 钩子中启动 `java -jar self-analyst-app.jar`
- **SPEC-DSK-BACKEND-001b**: 启动后轮询 `http://localhost:5700/0/info`，最多等待 10s
- **SPEC-DSK-BACKEND-001c**: 10s 内未就绪 → 弹错误对话框 → 退出
- **SPEC-DSK-BACKEND-001d**: Tauri `on_exit` 中发送 `SIGTERM` 终止 Java 进程
- **SPEC-DSK-BACKEND-001e**: Java 进程意外退出时，Tauri 显示"后端已停止"通知并退出

### SPEC-DSK-WEB-001: WebView 配置

- `url`: `http://localhost:5700/desktop-ui/`
- 启用 DevTools（`Ctrl+Shift+I`，仅 dev 构建）
- CSP 允许 `localhost:5700` 连接
- 禁用导航到外部 URL（拦截 `on_navigation`）

---

## 5. 构建规格

### SPEC-DSK-BLD-001: 构建产物

| 平台 | 产物 |
|------|------|
| Windows | `dist/SelfAnalyst.exe` (13MB) |
| | `dist/self-analyst-app.jar` (54MB) |
| | `dist/tools/` (PaddleOCR + whisper, 737MB) |

- **SPEC-DSK-BLD-001a**: `powershell -File scripts/build-dist.ps1` 一键构建完整 dist/
- **SPEC-DSK-BLD-001b**: 构建步骤: mvn package → 复制 jar + tools → cargo build --release → 复制 exe
- **SPEC-DSK-BLD-001c**: exe 从自身所在目录寻找 `self-analyst-app.jar`（同级目录）

### SPEC-DSK-BLD-002: 开发模式

- `pnpm tauri dev` — 启动 Tauri 开发窗口
- Java 后端需手动启动 `java -jar self-analyst-app.jar`

---

## 6. 测试规格

### SPEC-DSK-TST-001: 手动测试

| 测试 | 步骤 | 预期 |
|------|------|------|
| 启动 | 双击 exe | Java 启动 → WebView 显示 desktop-ui |
| 最小化 | 点关闭按钮 | 窗口隐藏，托盘图标显示 |
| 恢复 | 左键点击托盘图标 | 窗口重新显示 |
| 重复启动 | 桌面端运行时再次双击 exe | 提示 `SelfAnalyst 已在运行`，托盘图标数量不增加 |
| Web版桌面 | 托盘→Web版桌面 | 默认浏览器打开 `http://localhost:5700/desktop-ui/` |
| 关于 | 托盘→关于 | 原生消息框显示版本和后端服务 |
| 退出 | 托盘→退出 | Java 进程终止，窗口关闭 |
| 后端挂掉 | kill Java 进程 | Tauri 提示"后端已停止"并退出 |

---

## 7. 与现有模块关系

- 不依赖 `self-analyst-app` 作为 Maven 模块（通过子进程启动 jar）
- 运行时默认加载 Java 后端提供的 `desktop-ui/` 轻量桌面页
- 完整 Web UI（aw-webui，MPL-2.0）不随仓库分发；缺失时 `http://localhost:5700/` 返回 404，桌面端不再提供其入口
- `tools/` 目录内容打包进 bundle

---

## 规格追溯矩阵

| 规格 ID | 对应文件 |
|---------|---------|
| SPEC-DSK-ARCH-001 | lib.rs |
| SPEC-DSK-TRAY-001 | lib.rs, tauri.conf.json |
| SPEC-DSK-WIN-001 | lib.rs, tauri.conf.json |
| SPEC-DSK-BACKEND-001 | lib.rs |
| SPEC-DSK-WEB-001 | lib.rs, tauri.conf.json |
| SPEC-DSK-BLD-001..002 | tauri.conf.json, package.json |
