# self-analyst-content SDD 规格说明书

> Specification-Driven Development — 本文档定义系统的精确行为契约，所有实现必须可追溯至本文档的某一项规格。

---

## 1. 系统标识

| 属性 | 值 |
|------|-----|
| 产品名称 | SelfAnalyst Content Capture |
| 模块名 | `self-analyst-content` |
| 版本 | 1.0.0 |
| 语言 | Java 21 |
| 构建系统 | Maven 3.x（父聚合 pom 子模块） |

---

## 2. 模块定位

### SPEC-CTX-001: 模块职责

`self-analyst-content` 是一个独立的内容采集模块，负责从活跃窗口中提取文本内容，通过 HTTP 推送到 ActivityWatch API。

### SPEC-CTX-002: 四层采集策略

| 层 | 机制 | 触发条件 | 输出标签 |
|------|------|---------|---------|
| 1. 应用/标题排除 | `ThinDetector.isExcluded` / `isTitleExcluded` | 命中排除列表或密码标题 | 无心跳（跳过） |
| 2. UIA 树 | Windows UIAutomation COM | 始终执行（未排除） | `source: "uia"` |
| 3. UIA Document 标题 | `ThinDetector.extractDocumentTitle` | thin 且 UIA Document.Name 非空 | `source: "uia"` |
| 4. OCR 标题条 | PaddleOCR/Tesseract，仅截取顶部 N 像素 | thin 且第3层未命中 | `source: "ocr"/"hybrid"` |

### SPEC-CTX-003: 模块边界

- `self-analyst-content` **不依赖** `self-analyst-aw` 或 `self-analyst-app`
- 与 AW 服务通过 HTTP REST API 通信（与现有 WindowWatcher 模式一致）
- 依赖：JNA、Tess4J、Jackson

---

## 3. 模块结构

```
self-analyst-content/
├── pom.xml
└── src/
    ├── main/java/com/example/selfanalyst/content/
    │   ├── ContentWatcher.java              # 采集器入口，定时轮询
    │   ├── ContentEvent.java                # 内容事件数据模型
    │   ├── uia/
    │   │   ├── UiaCom.java                  # JNA COM 接口映射
    │   │   └── UiaTreeWalker.java           # 树遍历 + 文本提取
    │   ├── ocr/
    │   │   ├── OcrEngine.java               # OCR 接口
    │   │   └── TesseractOcrEngine.java      # Tess4J 实现
    │   ├── thin/
    │   │   └── ThinDetector.java            # 内容密度启发式
    │   ├── capture/
    │   │   ├── ContentCapture.java           # 协调器
    │   │   ├── ScreenCapturer.java           # 截屏工具 (AWT Robot)
    │   │   └── HybridMerger.java            # UIA + OCR 结果合并
    │   └── platform/
    │       ├── PlatformCapture.java          # 平台适配接口
    │       └── WindowsCapture.java           # Windows 实现
    └── test/java/com/example/selfanalyst/content/
        ├── thin/ThinDetectorTest.java
        ├── capture/HybridMergerTest.java
        └── uia/UiaTreeWalkerTest.java
```

---

## 4. 数据模型

### SPEC-MDL-100: ContentEvent

```java
public record ContentEvent(
    Instant timestamp,            // UTC 采集时间
    double duration,              // 距上次变化的秒数
    String app,                   // 进程名 (如 WeChat.exe)
    String title,                 // 窗口标题
    String textContent,           // 提取的全部文本
    String source,                // "uia" | "ocr" | "hybrid"
    int uiaChars,                 // UIA 提取的字符数
    int ocrChars                  // OCR 提取的字符数
) {}
```

### SPEC-MDL-101: UIA Node

```java
public record UiaNode(
    String name,                  // UIA_NamePropertyId
    String value,                 // UIA_ValueValuePropertyId
    int controlType,              // UIA_ControlTypePropertyId (枚举值)
    String className,             // UIA_ClassNamePropertyId
    double[] bounds,              // [x, y, width, height]
    boolean isPassword,           // UIA_IsPasswordPropertyId
    List<UiaNode> children
) {}
```

- **SPEC-MDL-101a**: `controlType` 值必须能映射到人类可读名称（如 50020 → "Text"）
- **SPEC-MDL-101b**: `textContent` 由 UIA 树中所有文本节点的 name/value 递归拼接而成
- **SPEC-MDL-101c**: 密码类型节点 (`isPassword = true`) 的 value 必须替换为 `"***"`，不提取真实密码

---

## 5. UIAutomation 规格 (层1)

### SPEC-UIA-001: UIA 实现方式

> **已被取代**（2026-06-19）：由 [`accessibility-sidecar.md`](accessibility-sidecar.md) `SPEC-AXS-*` 接管——改为常驻 Rust 边车 + OS 中性协议。本节描述的 PowerShell one-shot 方式将被移除；`SPEC-UIA-002/003/005/006` 的语义保留。

- 使用 PowerShell one-shot 进程调用 `System.Windows.Automation` .NET API
- 脚本写入临时 .ps1 文件，执行 `powershell -File xxx.ps1 <hwnd_hex>`
- 输出为 UTF-8 编码的 JSON 树结构
- Java 端通过 `ProcessBuilder` + `readAllBytes()` 读取，UTF-8/GBK fallback 解码

### SPEC-UIA-002: 节点属性提取

每个 UI 元素提取以下属性（通过 `AutomationElement.Current.*`）：

| .NET 属性 | 字段 | 说明 |
|----------|------|------|
| `Current.Name` | name | 控件名称 |
| `Current.ControlType.ProgrammaticName` | controlType | 控件类型名 (如 "ControlType.Button") |
| `Current.ClassName` | className | 窗口类名 |
| `Current.IsPassword` | isPassword | 是否密码字段 |
| `Current.BoundingRectangle` | bounds | [left, top, width, height] |
| `ValuePattern.Current.Value` | value | Edit/ComboBox 的实际文本 |

### SPEC-UIA-003: 树遍历

- 使用 `TreeWalker.ControlViewWalker` 跳过原始布局元素
- `GetFirstChild(el)` → `GetNextSibling(el)` 迭代遍历
- 深拷贝为 Java `UiaNode` 树结构

### SPEC-UIA-005: 跳过类型

以下控件类型在文本提取时**跳过**（只做 UI chrome，不含用户内容）：

| 类型 ID | 名称 |
|---------|------|
| 50014 | ScrollBar |
| 50006 | Image |
| 50038 | Separator |
| 50027 | Thumb |
| 50022 | ToolTip |
| 50012 | ProgressBar |

### SPEC-UIA-006: 文本提取规则

| 控件类型 | 提取策略 |
|---------|---------|
| Edit (50004), ComboBox (50003) | 优先取 `value`，其次 `name` |
| Document (50030), Group (50026), Pane (50033), Custom (50025) | 取 `name` 或 `value`，总是递归子节点 |
| Button (50000), Text (50020), ListItem (50007), Hyperlink (50005), TabItem (50019), MenuItem (50011), TreeItem (50024), DataItem (50029), Header (50034), HeaderItem (50035), TitleBar (50037), CheckBox (50002), RadioButton (50013) | 取 `name`，不递归 |
| 其他 | 只递归子节点 |

- **SPEC-UIA-006a**: 空字符串和 null 的 name/value 不加入输出

---

## 6. OCR 规格 (层2)

### SPEC-OCR-001: OcrEngine 接口

```java
public interface OcrEngine {
    String recognize(BufferedImage image);
}
```

- **SPEC-OCR-001a**: `recognize()` 返回图片中识别到的完整文本
- **SPEC-OCR-001b**: 无法识别时返回空字符串 `""`，不抛异常
- **SPEC-OCR-001c**: 图片为 null 或尺寸为 0 时返回 `""`
- **SPEC-OCR-001d**: `isAvailable()` 返回引擎是否可用，不可用时 `recognize()` 直接返回 `""`

### SPEC-OCR-002: PaddleOcrEngine (默认首选)

- 使用 PaddleOCR-json v1.4.1 独立可执行文件 (`tools/PaddleOCR-json/PaddleOCR-json.exe`)
- 通过 `ProcessBuilder` 启动常驻子进程，并使用 stdin/stdout 管道调用
  - 输入：包含临时 PNG 绝对路径的单行 JSON (`{"image_path":"..."}`)
  - 输出：JSON 格式识别结果 (`{"code":100,"data":[{"text":"...","box":[...], "score":0.98}]}`)
  - 工作目录设为 exe 所在目录以确保模型文件正确加载
  - 启动时只清理由 SelfAnalyst 工具路径启动且父进程已不存在的遗留进程，不清理其他目录中的同名进程或其他活跃 SelfAnalyst 实例的 OCR 进程
- **SPEC-OCR-002a**: code=100 时拼接所有 `data[].text` 字段，code≠100 时返回 `""`
- **SPEC-OCR-002b**: 识别完成后自动删除临时 PNG 文件
- **SPEC-OCR-002c**: exe 不存在时 `isAvailable()` 返回 false
- **SPEC-OCR-002d**: 遗留进程清理必须按规范化完整可执行文件路径判断归属，并确认父进程已不存在；不得仅按进程名清理，也不得清理其他活跃 SelfAnalyst 实例的 OCR 进程

### SPEC-OCR-003: TesseractOcrEngine (回退引擎)

- 使用 Tess4J (`net.sourceforge.tess4j.Tesseract`)
- 语言配置为 `chi_sim+eng`（中文简体 + 英文）
- 输入图片在 OCR 前做灰度化预处理
- 构造器中做 smoke test 验证 Tesseract 可用性，失败时 `isAvailable()` 返回 false
- **SPEC-OCR-003a**: 语言数据缺失或 Tesseract 未安装时，`isAvailable()` 返回 false，不抛异常

### SPEC-OCR-004: OCR 引擎选择

- 通过系统属性 `aw.ocr.engine` 或环境变量 `AW_OCR_ENGINE` 配置
- 支持值: `auto`（默认）、`paddle`、`tesseract`
- `auto`: PaddleOCR 存在则用，否则回退 Tesseract
- `paddle`: 强制 PaddleOCR，不存在时报错
- `tesseract`: 强制 Tesseract
- 所有引擎都不可用时使用空引擎（`image -> ""`）

---

## 7. 截屏规格

### SPEC-CAP-001: ScreenCapturer

```java
public class ScreenCapturer {
    public BufferedImage captureWindow(HWND hwnd);
}
```

- **SPEC-CAP-001a**: 使用 `User32.INSTANCE.GetWindowRect(hwnd, rect)` 获取窗口矩形
- **SPEC-CAP-001b**: 使用 `java.awt.Robot.createScreenCapture(rect)` 截屏
- **SPEC-CAP-001c**: 窗口最小化或不可见时返回 null

---

## 8. Thin 检测规格 (层3)

### SPEC-THN-001: ThinDetector 接口

```java
public class ThinDetector {
    public boolean isThin(List<UiaNode> tree, String app, String title);
}
```

### SPEC-THN-002: 已知 Canvas 应用模式

Canvas 判定**优先于** `SPEC-THN-006` 的应用排除短路：浏览器（属排除应用）打开 Google Docs / Figma 时渲染为画布、UIA 树为空，仍需 OCR。命中即 `true`，与应用与字符数无关。

以下窗口标题字符串匹配（大小写不敏感）直接返回 `true`：

```
google docs, google sheets, google slides, google drawings,
figma, excalidraw, miro, canva, tldraw
```

### SPEC-THN-003: 内容密度启发式

| 条件 | 判定 |
|------|------|
| UIA 树总字符数 < 100 | `true` (thin) |
| 内容角色字符 / 总字符 < 0.3 | `true` (thin) |
| 其他 | `false` |

### SPEC-THN-005: UIA Document 标题提取

当窗口被判定为 thin 时，优先通过 `ThinDetector.extractDocumentTitle()` 从 UIA 树中提取内部标题，避免截图：

- 递归搜索树中 `ControlType=50030`（Document）节点
- 取第一个非空 `Name` 属性值作为内部页面/文档标题
- 返回非 null 时直接作为 `textContent`，`source="uia"`，跳过截图和 OCR
- 适用场景：Electron/Tauri 应用启用了 Accessibility 时，WebView 暴露 Document 节点，其 Name = 当前页面标题

### SPEC-THN-006: 应用排除列表（内置）

以下进程名（大小写不敏感，子串匹配）整体跳过采集：

- **IDE**：`idea64`, `idea`
- **数据库工具**：`navicat`
- **文件管理器**：`explorer`
- **编辑器**：`notepad`, `code`（VS Code）
- **浏览器**：`chrome`, `msedge`, `firefox`, `opera`, `brave`, `vivaldi`
- **密码管理器**：`1password`, `keepass`, `bitwarden`, `dashlane`, `enpass`, `roboform`
- **系统凭据进程**：`credentialuibroker`, `consent`（UAC）

追加排除：通过 `ocr.excluded.apps` 属性（逗号分隔）。

### SPEC-THN-007: 窗口标题排除（密码保护）

窗口标题含以下关键词（大小写不敏感）时整体跳过采集：

```
密码  password  凭据  credential
```

### SPEC-THN-004: Chrome 角色定义

以下控件类型 ID 归类为 chrome（UI 装饰，非用户内容）：

```
Button(50000), MenuItem(50011), MenuBar(50010), Menu(50009),
ToolBar(50021), TabGroup(50018), Tab(50018), CheckBox(50002),
RadioButton(50013), ComboBox(50003), ScrollBar(50014), Slider(50015)
```

- **SPEC-THN-004a**: 所有不在 chrome 列表中的控件类型 ID 都归类为内容角色

---

## 9. 混合合并规格

### SPEC-HYB-001: HybridMerger

```java
public class HybridMerger {
    public String merge(String uiaText, String ocrText);
}
```

- **SPEC-HYB-001a**: UIA 文本不为空时，用 `\n--- OCR ---\n` 分隔符追加 OCR 文本
- **SPEC-HYB-001b**: UIA 文本为空时，直接返回 OCR 文本
- **SPEC-HYB-001c**: 两者都为空时返回 `""`

---

## 10. ContentWatcher 规格

### SPEC-WCH-001: 采集循环

- 前台窗口句柄、应用名和标题按 `aw.collection.content.pollMs` 轮询，默认 500ms
- 句柄、应用名和标题必须从同一个前台 HWND 快照解析，禁止分次读取不同前台窗口
- 窗口句柄、应用名或标题发生变化时，必须立即执行一次 `capture()`
- 稳定窗口按 `ocr.stable-capture-interval-ms` 重新截图，默认 1500ms
- 稳定窗口间隔以捕获尝试开始时间和单调时钟计算；失败不得退化为 500ms 重试风暴
- 稳定窗口在截图间隔内只复用最近快照，不执行截图或 OCR
- 捕获完成及 heartbeat 发送前必须复核前台身份；窗口已切换时丢弃旧结果
- 采集线程为 daemon，不阻止 JVM 退出
- 通过 HTTP 向 AW API 推送 heartbeat

### SPEC-WCH-002: capture() 流程

```
1. GetForegroundWindow() → HWND
2. GetWindowText(hwnd) → title
3. GetWindowThreadProcessId(hwnd) → PID → app 名称
4. isExcluded(app, title)?
   → true: latest = null，跳过本轮（无心跳）
5. UiaTreeWalker.walk(hwnd) → UIA 文本 + 节点树
6. ThinDetector.isThin(tree, app, title) → 判断
7. IF thin:
   a. ThinDetector.extractDocumentTitle(tree) → docTitle?
      → 非 null: textContent = docTitle, source = "uia"（无截图）
   b. ELSE: ScreenCapturer.captureWindow(hwnd) → 截图顶部 N px (SPEC-OCR-005)
            OcrEngine.recognize(cropped) → OCR 文本
            HybridMerger.merge(uiaText, ocrText) → 最终文本
            source = "ocr" | "hybrid"
   ELSE:
   textContent = uiaText, source = "uia"
8. ContentEvent 构造 → POST /api/0/buckets/aw-watcher-content-{host}/heartbeat
```

### SPEC-WCH-003: 错误处理

- UIA 树遍历异常：返回空 UIA 文本，标记 source 为 "ocr"
- OCR 异常：使用 UIA 文本，标记 source 为 "uia"
- 两者都异常：跳过本次采集
- 不得因单次采集失败而终止循环

---

## 11. 测试规格

### SPEC-TST-100: ThinDetector 测试

| 测试用例 | 输入 | 预期 |
|---------|------|------|
| 总字符 < 100 | 树含 50 个字符，非 Canvas | `true` |
| Canvas 应用 | title 含 "Google Docs"，总字符 500 | `true` |
| 内容密度低 | 总字符 500，内容字符 100 (20%) | `true` |
| 正常应用 | 总字符 500，内容字符 400 (80%) | `false` |
| 空树 | 无节点 | `true` |

### SPEC-TST-101: HybridMerger 测试

| 测试用例 | 预期 |
|---------|------|
| UIA 有内容 + OCR 有内容 | UIA 文本 + `\n--- OCR ---\n` + OCR 文本 |
| UIA 有内容 + OCR 为空 | UIA 文本 |
| UIA 为空 + OCR 有内容 | OCR 文本 |
| 两者为空 | `""` |

---

## 12. 构建与集成

### SPEC-BLD-100: 模块 pom.xml

- `groupId`: `com.selfanalyst`
- `artifactId`: `self-analyst-content`
- parent: `self-analyst` (聚合 pom)
- 依赖: `jna`, `jna-platform`, `tess4j:5.13.0`, `jackson-databind`
- 不需要 mainClass（库模块）

### SPEC-BLD-101: 父 pom.xml

- 在 `<modules>` 中添加 `<module>self-analyst-content</module>`
- 在 `<dependencyManagement>` 中添加 `tess4j` 版本管理

### SPEC-BLD-102: self-analyst-app 集成

- `self-analyst-app` 的 pom.xml 添加对 `self-analyst-content` 的依赖（可选，当前通过 HTTP 通信）
- `AppSession` 中可选用 `ContentWatcher` 启动内容采集

---

## 13. 非功能需求

### SPEC-NFR-100: OCR 延迟

单次 OCR 识别（含截图）耗时不超过 2 秒。

### SPEC-NFR-101: UIA 性能

单次 UIA 树遍历耗时不超过 200ms（典型应用）。

### SPEC-OCR-005: 隐私标题条策略（Privacy Title Strip）

OCR 截图**必须**仅对窗口顶部 N 像素（`ocr.title-strip-height`，默认 80）进行识别：

- 仅捕获应用标题栏/选项卡行，不捕获正文内容
- 默认不保存截图；常规内容存储仅接收识别后的标题条文本
- 设置为 0 会禁用标题条限制（不推荐，会识别完整窗口内容）
- 宽度超过 960 像素的标题条必须横向分片，避免等比缩放降低文字高度

OCR 处理顺序（thin 窗口）：

```
trimBlackBorders(image)
  → subimage(0, 0, w, min(titleStripHeight, h))   ← 顶部裁剪
  → 宽度 > 960 时按 960px 横向分片，相邻分片重叠 64px
  → 对每个分片执行 OcrEngine.recognize()
  → 合并文本，仅去除相邻分片边界上的精确重复行
```

标题条模式应过滤由工具栏图标产生的单字符 OCR 噪声。完整窗口模式不执行该过滤，
避免丢失正文中的合法单字符内容。

### SPEC-OCR-006: 原始调试样本

- `ocr.sample.enabled` 默认必须为 `false`
- 仅当显式设置为 `true` 时，保存 OCR 前的原始窗口截图及对应 JSON 元数据
- 调试样本保留原始截图，不使用标题条裁剪图替代
- 样本使用 240 槽循环覆盖，目录由 `ocr.sample.dir` 配置
- 原始截图可能包含正文或敏感信息，开关仅用于本地调试

### SPEC-OCR-007: 图片指纹与 OCR 复用

- 指纹必须基于实际 OCR 输入（标题条裁剪及分片之后）；未进入 OCR 输入的像素变化
  不得直接使缓存失效
- 指纹使用像素颜色量化后计算 SHA-256，降低轻微渲染噪声造成的无效 OCR
- 窗口句柄、应用名、窗口标题或图片指纹任一变化时，必须重新执行 OCR
- 图片未变化时复用最近 OCR 文本，不生成新的调试样本或 `sample_id`
- 非空结果最多复用 `ocr.force-refresh-ms`（允许 30000～60000ms），默认 60000ms，
  超时后强制重新识别；复用时间从 OCR 完成时开始计算
- 空结果仅复用 5000ms，避免一次瞬时失败被长期缓存

### SPEC-NFR-102: 降级容错

- Tesseract 未安装：跳过 OCR，只使用 UIA
- UIA COM 调用失败：降级为纯 OCR
- 两者都不可用：模块静默跳过，不抛异常

---

## 规格追溯矩阵

| 规格 ID | 对应文件 | 对应测试 |
|---------|---------|---------|
| SPEC-CTX-001..003 | ContentWatcher.java | — |
| SPEC-MDL-100..101 | ContentEvent.java, UiaNode.java | — |
| SPEC-UIA-001..006 | UiaCom.java, UiaTreeWalker.java | UiaTreeWalkerTest |
| SPEC-OCR-001..007 | OcrEngine.java, TesseractOcrEngine.java, ContentCapture.java, OcrSampleStore.java | ContentCaptureTest, OcrSampleStoreTest |
| SPEC-CAP-001 | ScreenCapturer.java | — |
| SPEC-THN-001..007 | ThinDetector.java | ThinDetectorTest |
| SPEC-HYB-001 | HybridMerger.java | HybridMergerTest |
| SPEC-WCH-001..003 | ContentWatcher.java, ContentCapture.java | ContentWatcherTest, ContentCaptureTest |
| SPEC-BLD-100..102 | pom.xml (content + root + app) | — |
