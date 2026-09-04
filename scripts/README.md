# scripts 目录说明

本目录包含 SelfAnalyst 的开发启动、安装、构建、打包和静态验收脚本。

## 日常开发

| 脚本 | 用途 |
|------|------|
| `run.ps1` | 启动已构建 JAR；可选先构建或跳过测试 |

```powershell
.\scripts\run.ps1
.\scripts\run.ps1 -Build
.\scripts\run.ps1 -Build -SkipTests
```

默认访问 `http://localhost:5700`；若 `config.toml` 修改了 `aw.port`，使用实际配置端口。

## 安装与卸载

| 脚本 | 用途 |
|------|------|
| `install.ps1` | 安装 Windows 用户级启动环境 |
| `install.sh` | 安装 Linux/macOS 用户级启动环境 |
| `uninstall.ps1` | Windows 卸载；可选保留用户数据 |
| `uninstall.sh` | Linux/macOS 卸载；可选保留用户数据 |

## 构建与打包

| 脚本 | 用途 |
|------|------|
| `build-axsidecar.ps1` | 构建 Rust accessibility sidecar 并暂存到 Java 资源目录 |
| `build-dist.ps1` | 构建 Java、桌面壳、sidecar，并组装 `dist/` |
| `build-portable.ps1` | 生成 Windows 免安装 ZIP 及 SHA-256 |
| `build-installer.ps1` | 生成内含后端 JAR 与 jlink runtime 的 NSIS 安装包及 SHA-256 |

OCR 与声音功能已暂时移除，因此构建脚本不下载或携带 PaddleOCR、Tesseract、Whisper 和语音模型。

## 验收检查

这些脚本用于 CI 或手动静态回归，失败时以非零状态退出：

| 脚本 | 验证目标 |
|------|----------|
| `check-desktop-tray.ps1` | 托盘、单实例和桌面壳配置 |
| `check-desktop-chat-tab.ps1` | 会话页前端结构和资源 |
| `check-desktop-chat-session-store.ps1` | 会话 REST/存储前端接线 |
| `check-desktop-config-editor.ps1` | TOML raw 配置编辑器 |
| `check-desktop-i18n.ps1` | 中英文消息目录和文案接线 |
| `check-desktop-behavior-advice.ps1` | 行为建议卡片 |
| `check-packaged-jar.ps1` | 在无采集临时环境中验证 fat JAR 的启动、认证、状态、静态资源和关闭 |
| `check-installer.ps1` | 在工作区临时目录中完成 NSIS 静默安装、重装、资源检查、后端冒烟和卸载 |

```powershell
.\scripts\check-desktop-tray.ps1
.\scripts\check-desktop-chat-tab.ps1
.\scripts\check-desktop-chat-session-store.ps1
.\scripts\check-desktop-config-editor.ps1
.\scripts\check-desktop-i18n.ps1
.\scripts\check-desktop-behavior-advice.ps1
.\scripts\check-packaged-jar.ps1
.\scripts\check-installer.ps1
```
