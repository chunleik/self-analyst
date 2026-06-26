# scripts/

本目录包含 SelfAnalyst 的开发、构建、部署和验证脚本。

## 日常开发

| 脚本 | 用途 |
|------|------|
| `run.ps1` | **启动应用**（开发用，直接跑已编译的 jar） |

```powershell
# 用已有 jar 直接启动（最常用）
.\scripts\run.ps1

# 先重新构建再启动
.\scripts\run.ps1 -Build

# 跳过测试构建后启动
.\scripts\run.ps1 -Build -SkipTests
```

启动后访问 http://localhost:5700（AW + DesktopServer 同端口）。

## 安装 / 卸载

| 脚本 | 用途 |
|------|------|
| `install.ps1` | 安装到 `~\.self-analyst`，生成用户级启动脚本 |
| `install.sh` | 同上（Linux / macOS） |
| `uninstall.ps1` | 卸载，可选保留 memory 数据 |
| `uninstall.sh` | 同上（Linux / macOS） |

## 构建 / 打包

| 脚本 | 用途 |
|------|------|
| `build-dist.ps1` | 打包完整发布产物到 `dist/` 目录 |
| `download-tools.ps1` | 下载外部工具：PaddleOCR-json、whisper.cpp |

## 验收检查（CI / 手动回归）

这三个脚本对应 spec 中的 traceability 验证，失败时抛出异常退出。

| 脚本 | 验证目标 | 对应 spec |
|------|---------|-----------|
| `check-desktop-tray.ps1` | Tauri 托盘配置正确 | `SPEC-DSK-TRAY-*` |
| `check-desktop-chat-tab.ps1` | Chat Tab 前端文件完整 | `SPEC-CHAT-TAB-*` |
| `check-desktop-behavior-advice.ps1` | Behavior Advice 前端文件完整 | `SPEC-ADV-*` |

```powershell
# 单独运行某项检查
.\scripts\check-desktop-tray.ps1
```
