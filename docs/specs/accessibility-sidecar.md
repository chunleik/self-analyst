# 无障碍树边车（Accessibility Sidecar）SDD 规格说明书

> Specification-Driven Development spec. 本文档定义把 UIA 采集从「一次性 PowerShell 进程」改造为「常驻 Rust 边车进程 + OS 中性协议」的行为契约。所有实现必须可追溯至本文档某一项规格。

---

## 1. 文档元信息

| 属性 | 值 |
|------|-----|
| 特性名称 | 无障碍树边车（Accessibility Sidecar） |
| 文档状态 | Windows 已实现；macOS 部分为设计预留 |
| 日期 | 2026-06-19 |
| 涉及模块 | `self-analyst-content`（Java 客户端）、新增 `self-analyst-axsidecar`（Rust 二进制） |
| 取代 | `SPEC-UIA-001`（PowerShell one-shot 实现） |
| 保留 | `SPEC-UIA-002/003/005/006`（属性提取、遍历、跳过、文本提取规则的语义不变） |
| 目标平台 | Windows 优先实现；macOS 协议预留、暂不实现 |

---

## 2. 改造前背景

改造前实现（见 [content.md](content.md) §5 的已取代条款）：

- `SPEC-UIA-001` 曾在每次 UIA 查询时启动一次 `powershell.exe`，加载 .NET UIAutomation 并输出 JSON 树。
- 单次调用曾耗时 1–3 秒，主要成本是进程启动、程序集加载与 JIT，而非树遍历本身。
- `ContentWatcher` 曾用 `cachedWalk/cachedHwnd` 降低调用频率，但不能消除窗口切换时的冷启动延迟。
- 旧接口直接暴露 JNA `HWND`，阻碍了 OS 中性实现。

旧下游已经只依赖纯数据模型 `UiaNode`；当前实现用 `AxSidecarClient.query(long) → UiaNode` 替换旧
PowerShell 入口，并保留 Java 侧文本提取与 Thin/OCR 合并逻辑。

---

## 3. 设计结论

### SPEC-AXS-001：边车架构

无障碍树抽取改由**一个常驻子进程（边车）**承担：

- **每个 OS 一个原生二进制**，对 Java 暴露**同一套 stdio JSON 协议**。
- 边车独占全部 OS 原生无障碍 API 调用（Windows: UI Automation；macOS: Accessibility / AXUIElement）。
- Java 侧成为**平台中性客户端**：按当前 OS 选择并拉起对应二进制，只收发中性 JSON，不含任何 OS 原生句柄类型。

### SPEC-AXS-002：边车语言与形态

- 边车用 **Rust** 实现。理由（设计决策）：
  - Windows 当前使用 Rust `uiautomation` crate；未来 macOS 可使用 `objc2` / `core-foundation` / Accessibility 绑定，并共享同一 stdio/JSON 骨架与构建路径；
  - Rust 已在仓库内（Tauri 桌面壳），不新增第二种边车语言；
  - 单个自包含原生二进制，**无运行时依赖**（对比 .NET 在 macOS 需随包分发运行时）。
- 不选 C#：其唯一优势是贴合现有 PowerShell 的 UIAutomation API，纯 Windows 红利，到 macOS（无 AX 托管绑定，需 P/Invoke）不成立。
- 不选 Swift+C# 各取最优：会引入两种边车语言，丢失共享骨架。

### SPEC-AXS-003：OS 中性边界

- Java↔边车契约只使用**中性窗口句柄**（不透明 `long`），不出现 `HWND`/`AXUIElement` 等 OS 类型。
- 角色、节点模型、坐标系一律中性化（见 §5）。OS 差异**全部收敛在边车内部**。

---

## 4. 目标与非目标

### 4.1 目标

- 把单次 UIA 查询延迟从 1–3 秒降到**常驻态典型 < 100ms**。
- 建立 OS 中性契约，使**接入 macOS 无需改动 Java 业务逻辑**——只增加一个同语言二进制。
- **行为不回归**：对相同窗口，最终 `textContent` 输出与现状一致（`SPEC-UIA-005/006` 语义保留）。
- 保持降级语义：边车任何失败 → 查询返回 `null`；可选 OCR 已启用时由 `ContentWatcher` 退化为 OCR，否则保留空 UIA 结果（`SPEC-WCH-003`/`SPEC-NFR-102`）。
- 顺手修正 `PlatformCapture` 的 `HWND` 泄漏。

### 4.2 非目标

- **不实现 macOS 边车**本身（§7 仅为设计预留；其落地是后续独立工程）。
- 不改动已启用 OCR 时的识别（`SPEC-OCR-*`）、Thin 检测（`SPEC-THN-*`）、混合合并（`SPEC-HYB-*`）逻辑。
- 不改动 heartbeat / `ContentEvent` 对外契约。
- 不实现流式 / 增量树更新；仍是「一次请求一棵完整树」。
- 不保留 PowerShell 实现作为运行时回退（`SPEC-UIA-001` 删除；失败即降级为无 UIA，与现状 COM 失败降级一致）。

---

## 5. OS 中性协议

### SPEC-AXS-010：传输

- 边车与 Java 之间用 **stdin/stdout 的按行分隔 JSON**（NDJSON）：一行一个请求 → 一行一个响应。
- 编码**固定 UTF-8**（不再需要现状的 UTF-8/GBK fallback）。
- stderr 仅用于诊断日志，不参与协议。

### SPEC-AXS-011：请求

```json
{"id": 42, "handle": "0x00000000001A0C3E"}
```

- `id`：单调递增请求号，响应须回填同值。
- `handle`：中性窗口句柄的十六进制字符串（Windows 下即 HWND 数值；macOS 下为边车内部可解析的窗口/元素引用编码）。

### SPEC-AXS-012：响应

```json
{"id": 42, "status": "ok", "root": { /* AxNode */ }}
```

- `status`：`ok` | `null`（目标元素不存在）| `error`（附 `error` 字段）。
- `status != ok` 时 `root` 省略或为 `null`。

### SPEC-AXS-013：节点模型（AxNode）

```json
{
  "role": "Text",
  "name": "...",
  "value": "...",
  "secure": false,
  "bounds": [x, y, w, h],
  "children": [ /* AxNode... */ ]
}
```

- **SPEC-AXS-013a**：`role` 为**中性角色枚举**（见 SPEC-AXS-016），由边车内部从 OS 原生角色映射而来；Java 侧不感知 OS 原生角色。
- **SPEC-AXS-013b**：`bounds` 为 `[x, y, w, h]` double；`Infinity`/`NaN` 必须由边车归零（沿用现状语义）。
- **SPEC-AXS-013c**：空字符串与缺失的 `name`/`value` 允许省略字段。

### SPEC-AXS-014：密码 / 安全字段

- **SPEC-AXS-014a**：安全输入框（Windows `IsPassword=true`；macOS `AXSecureTextField`）必须置 `secure=true`。
- **SPEC-AXS-014b**：`secure=true` 时边车**不得输出真实 value**，`value` 须为空或 `"***"`；真实密码不得离开边车进程。等价保留 `SPEC-MDL-101c`。

### SPEC-AXS-016：中性角色词表

边车把各 OS 原生角色映射到下列中性枚举（取并集，覆盖现有文本提取所需）：

```
Button, Calendar, CheckBox, ComboBox, Edit, Hyperlink, Image, ListItem,
List, Menu, MenuBar, MenuItem, ProgressBar, RadioButton, ScrollBar, Slider,
Spinner, StatusBar, Tab, TabItem, Text, ToolBar, ToolTip, Tree, TreeItem,
Custom, Group, Thumb, DataGrid, DataItem, Document, SplitButton, Window,
Pane, Header, HeaderItem, Table, TitleBar, Separator, SemanticZoom, AppBar,
Unknown
```

- **SPEC-AXS-016a**：未识别的原生角色映射为 `Unknown`。
- **SPEC-AXS-016b**：映射表是契约的一部分——Windows ControlType、macOS AXRole 各自向此枚举的映射须在对应边车实现中固定，并有单测覆盖（§8）。

---

## 6. Windows 边车实现

### SPEC-AXS-040：Windows 原生采集

- 使用 Rust `uiautomation` crate 封装 Windows UI Automation；底层仍对应 `IUIAutomation` / `IUIAutomationElement`。
- 提取属性等价于 `SPEC-UIA-002`：`Name`、`ControlType`、`ClassName`、`IsPassword`、`BoundingRectangle`、`ValuePattern.Value`。
- 遍历等价于 `SPEC-UIA-003`：`ControlViewWalker` 的 `GetFirstChild → GetNextSibling`。
- `ControlType`（50000..）按 SPEC-AXS-016 映射为中性角色。
- `handle` 解析为 `HWND`，`IUIAutomation::ElementFromHandle` 取根元素；空则回 `status: "null"`。

### SPEC-AXS-041：进程内常驻

- 边车进程启动时创建并缓存 `UIAutomation` 与 `ControlViewWalker`，后续请求复用同一 backend，不重复承担进程和 COM 初始化成本。

---

## 7. macOS 边车（设计预留，暂不实现）

> 状态：**设计预留**。本节定义未来 macOS 边车必须满足的契约，但本期不实现、不计入验收。

### SPEC-AXS-050：macOS 原生采集

- 用 Accessibility API（`AXUIElement`，ApplicationServices）。
- 前台应用经 `NSWorkspace.frontmostApplication` + 焦点窗口取根 `AXUIElement`。
- `AXRole`（`AXButton`/`AXTextField`/`AXStaticText`/`AXSecureTextField`…）按 SPEC-AXS-016 映射为中性角色；`AXSecureTextField` → `secure=true`。
- `AXPosition`/`AXSize` → `bounds`，转换到与 Windows 一致的屏幕坐标语义。

### SPEC-AXS-051：权限与分发

- 需 TCC 授权：辅助功能（Accessibility）；若该 OS 上同时启用 OCR，还需屏幕录制权限。首启需引导用户授权。
- 二进制必须 **codesign + notarize**，否则 Gatekeeper 拦截；AX 授权绑定签名身份。
- 产出 **arm64 + x86_64 通用二进制**。

---

## 8. 进程生命周期与 Java 集成

### SPEC-AXS-020：生命周期

- **懒启动**：首次查询时拉起边车二进制。
- **单一共享实例**：全进程共用一个边车（现有 `ContentWatcher` 与 `WindowsCapture` 各自 `new UiaTreeWalker()` 须改为共享，查询稀疏，单实例足够）。
- **崩溃自愈**：检测到边车退出 / 管道断裂时，下次查询前重启。
- JVM `shutdown hook` 必须杀掉边车，不留孤儿进程。

### SPEC-AXS-021：超时

- 单次查询有超时（默认见 §9）。超时即 **kill 并重启**边车，本次返回 `null`。
- 现状 `proc.waitFor()` 无超时的隐患在此修复。

### SPEC-AXS-022：并发

- 同时只允许**单个在途请求**（调用方本就串行）。客户端对「写请求 → 读响应」加锁，按 `id` 校验配对。

### SPEC-AXS-023：降级容错

- 下列任一情况，`query(handle)` 返回 `null`；只有显式启用 OCR 时，`ContentWatcher` 才退化为 OCR：二进制缺失 / 启动失败 / 崩溃 / 超时 / 响应解析失败 / `status != ok`。
- 等价保留 `SPEC-WCH-003`、`SPEC-NFR-102`。

### SPEC-AXS-030：Java 客户端

- 新增 `AxSidecarClient`，对外维持 `query(handle) → 节点树`，并以**进程级共享单例**（`AxSidecarClient.shared()`）满足 `SPEC-AXS-020`。
- `UiaTreeWalker` 改为经共享 `AxSidecarClient` 走边车，删除 `UiaPowerShell`；`walk(...)` 入参由 `HWND` 改为中性 `long` 句柄。
- **中性边界设在协议层（中性 role 字符串）+ `AxSidecarClient` 适配层**。客户端把响应里的中性 `role` 适配为内部表示。
- **内部节点表示保留 `UiaNode`（int `controlType`）**，不做全量重命名/重键（设计决策，2026-06-19）：
  - 理由：`ThinDetector`、`extractText`、`controlTypeName` 等围绕 int 的逻辑稳定且有测试覆盖；macOS 边车发出的是**同一套中性 role 字符串**，经客户端适配为同一套 int，故内部 int 表示**不阻碍 macOS 接入**；
  - 代价权衡：全量 `UiaNode→AccessibilityNode` + role 字符串重键会波及单测与集成测试两套用例，属纯 churn、无功能/可移植性收益。
- 文本提取（`SPEC-UIA-005/006`）、`ThinDetector`、`HybridMerger`、`ContentWatcher` 主流程逻辑不变（仅句柄类型 `HWND→long` 随中性化调整）。

### SPEC-AXS-031：句柄中性化

- `PlatformCapture.getForegroundWindow()` 返回类型从 `HWND` 改为中性句柄（不透明 `long` 或 `WindowRef` 值类型）。
- Windows 实现内部仍用 `HWND`，但不再泄漏到接口；转中性句柄后传给边车。

---

## 9. 配置

| 入口 | 默认 | 语义 |
|------|------|------|
| system property `content.axsidecar.path` 或环境变量 `CONTENT_AXSIDECAR_PATH` | 未显式指定 | 显式边车路径；system property 优先 |
| system property `content.axsidecar.timeout-ms` | `1500` | 单次查询超时；超时后终止当前边车并在下次查询重启 |

未指定路径时，Java 先尝试从 classpath `/axsidecar/<binary>` 释放到临时文件，再尝试开发目录
`self-analyst-axsidecar/target/release/`；均不存在时 UIA 返回空，可选 OCR 是否接管由
`aw.ocr.engine` 决定。当前没有
`content.axsidecar.enabled` 配置键；禁用全部上下文标题识别使用 `aw.collection.content=false`。

---

## 10. 性能

### SPEC-AXS-070：延迟目标

- **常驻态**单次查询（含 IPC 往返 + 树遍历）典型 **< 100ms**；首次冷启动（拉起进程 + COM 初始化）可放宽至 < 1s。
- 与 `SPEC-NFR-101`（树遍历本身 < 200ms）不冲突——本项约束端到端 IPC 延迟。

---

## 11. 测试规格

### SPEC-AXS-T01：协议往返

| 测试 | 预期 |
|------|------|
| 发送 `{id,handle}`，读一行响应 | 响应 `id` 与请求一致 |
| 不存在的 handle | `status:"null"`，`query` 返回 `null` |
| 边车非法输出 | 解析失败 → `query` 返回 `null`，不抛异常 |

### SPEC-AXS-T02：角色映射

| 测试 | 预期 |
|------|------|
| Windows ControlType.Button(50000) | 中性 `role="Button"` |
| Windows ControlType.Edit(50004) | `role="Edit"` |
| 未知 ControlType | `role="Unknown"` |
| macOS `AXButton`（预留，实现时启用） | `role="Button"` |

### SPEC-AXS-T03：密码脱敏

| 测试 | 预期 |
|------|------|
| 安全输入框 | `secure=true` 且 value 不含真实文本 |

### SPEC-AXS-T04：生命周期与降级

| 测试 | 预期 |
|------|------|
| 边车进程被杀 | 下次查询自动重启并成功，或降级返回 `null` |
| 查询超时 | 在 `timeout-ms` 内返回 `null`，边车被重启 |
| 二进制缺失 | `query` 返回 `null`；OCR 启用时走 OCR，否则保持 UIA-only 降级 |
| JVM 退出 | 无残留边车进程 |

### SPEC-AXS-T05：行为等价

| 测试 | 预期 |
|------|------|
| 对同一窗口树，新链路文本提取结果 | 与 `SPEC-UIA-006` 规则下的现状输出一致 |

---

## 12. 构建与集成

### SPEC-AXS-060：边车构建产物

- 新增 `self-analyst-axsidecar`（Cargo 项目）。`scripts/` 构建脚本编译当前 OS 的边车二进制并随包分发；运行时按 OS 选择。
- 二进制随 `self-analyst-content` 资源打包，运行时释放到临时目录或安装目录；该边车始终属于核心上下文标题识别，不依赖可选 PaddleOCR 包。

### SPEC-AXS-061：模块接线

- 根 `pom.xml` 不直接管理 Rust 构建；由 `scripts/` 编排 Cargo 构建 + 资源拷贝。
- `self-analyst-content` 仅依赖「资源路径 + 进程协议」，不依赖 Rust 工具链存在于运行环境。

---

## 13. 与现有规格的关系

| 关系 | 规格 |
|------|------|
| **取代** | `SPEC-UIA-001`（PowerShell one-shot 实现方式） |
| **语义保留、载体改变** | `SPEC-UIA-002/003/005/006`（属性、遍历、跳过、文本提取规则） |
| **数据模型** | `SPEC-MDL-101`：协议层用中性 `AxNode`（`role` 字符串）；Java 内部节点表示保留 `UiaNode`（int `controlType`），由 `AxSidecarClient` 适配（见 SPEC-AXS-030 决策）。`SPEC-MDL-101c` 密码脱敏由 `SPEC-AXS-014b` 在边车侧承接 |
| **不变** | `SPEC-OCR-*`、`SPEC-THN-*`、`SPEC-HYB-*`、`SPEC-WCH-*`、`SPEC-NFR-102` |

---

## 14. 规格追溯矩阵

| 规格 ID | 对应文件 | 对应测试 |
|---------|---------|---------|
| SPEC-AXS-001..003 | （架构，无单一文件） | — |
| SPEC-AXS-010..016 | `self-analyst-axsidecar`（协议）、`AxSidecarClient.java` | SPEC-AXS-T01/T02/T03 |
| SPEC-AXS-040..041 | `self-analyst-axsidecar/src`（Windows） | SPEC-AXS-T02 |
| SPEC-AXS-050..051 | `self-analyst-axsidecar/src`（macOS，预留） | —（暂不实现） |
| SPEC-AXS-020..023 | `AxSidecarClient.java`（进程生命周期） | SPEC-AXS-T04 |
| SPEC-AXS-030..031 | `AxSidecarClient.java`、`UiaTreeWalker.java`、`PlatformCapture.java`、`WindowsCapture.java`、`ContentCapture.java`、`ContentWatcher.java` | SPEC-AXS-T05 |
| SPEC-AXS-060..061 | `self-analyst-axsidecar/Cargo.toml`、`scripts/*` | — |
| SPEC-AXS-070 | `AxSidecarClient.java` | SPEC-AXS-T04 |
