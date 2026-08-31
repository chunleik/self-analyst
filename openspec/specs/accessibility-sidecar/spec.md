# 无障碍树边车规格

## Purpose

定义当前 Windows 无障碍树边车的中性查询协议、节点安全边界、进程生命周期、失败降级和发布可用性；本规格不把尚未实现的 macOS backend 或未经自动化验证的性能目标描述为当前能力。

## Requirements

### Requirement: SPEC-AXS-001、SPEC-AXS-003 边车进程与中性查询边界
Windows 无障碍树查询 SHALL 由独立常驻边车进程执行。Java 与边车之间 SHALL 只传递不透明数值句柄、
UTF-8 JSON 和中性节点数据，不得在跨进程协议中暴露 `HWND` 或其他 OS 原生对象。该边界不表示当前
Java 前台窗口采集已经与所有 OS API 解耦，也不承诺存在非 Windows backend。

#### Scenario: Windows 前台窗口以中性句柄查询
- **WHEN** Windows 采集器取得非零前台窗口句柄并请求无障碍树
- **THEN** 查询通过独立边车执行，跨进程边界只包含数值句柄和中性节点数据

#### Scenario: 不支持的平台
- **WHEN** 当前平台没有实现可用的无障碍 backend
- **THEN** 查询安全失败并返回空，不把未来平台设计当作已实现能力

### Requirement: SPEC-AXS-010、SPEC-AXS-011、SPEC-AXS-012 UTF-8 NDJSON 协议
客户端与边车 SHALL 使用 stdin/stdout 上的 UTF-8 NDJSON；一行请求对应一行响应，stderr 不参与协议。
请求 SHALL 包含单调递增的 `id` 和窗口 `handle`；生产客户端 SHALL 使用十六进制字符串发送句柄。
响应 SHALL 回填 `id`，并使用 `ok/null/error` 状态；状态不是 `ok` 时 SHALL 不提供可用 root。

#### Scenario: SPEC-AXS-T01 请求响应配对
- **WHEN** 客户端发送一行包含递增 `id` 和十六进制 `handle` 的 UTF-8 JSON
- **THEN** 边车输出一行响应，回填相同 `id`，并使用 `ok`、`null` 或 `error` 状态

#### Scenario: SPEC-AXS-T01 目标不存在
- **WHEN** 句柄可解析但目标元素不存在
- **THEN** 响应使用相同 `id` 和 `null` 状态，且不提供 root

#### Scenario: 非法请求
- **WHEN** 边车收到无法解析的请求
- **THEN** 边车返回 `error` 状态，并继续按行处理后续请求

### Requirement: SPEC-AXS-013、SPEC-AXS-013a、SPEC-AXS-013b、SPEC-AXS-013c 中性节点模型
成功响应的 root SHALL 是递归节点树。每个节点 SHALL 提供中性 `role` 和四元素 `bounds`，并 MAY
提供 `name`、`value`、`secure` 和 `children`；空 `name/value`、`secure=false` 或空 children MAY
被省略。`bounds` SHALL 为四个有限数；矩形读取失败时 SHALL 使用全零边界。

#### Scenario: 返回递归节点树
- **WHEN** Windows 无障碍查询成功
- **THEN** root 递归提供中性角色、可选安全字段和四元素有限边界

#### Scenario: 可选字段为空
- **WHEN** 节点没有名称、值、子节点且不是安全输入框
- **THEN** 响应 MAY 省略相应空字段，而不是填入正文或不可信占位内容

### Requirement: SPEC-AXS-014、SPEC-AXS-014a、SPEC-AXS-014b 密码值不得越过边车
Windows 原生节点被标记为密码输入时，边车 MUST 输出 `secure=true`，并 MUST 将 `value` 固定为掩码
或省略；真实密码值 MUST NOT 离开边车进程。

#### Scenario: SPEC-AXS-T03 安全输入框脱敏
- **WHEN** Windows 节点的密码属性为 true
- **THEN** 输出 `secure=true`，且 `value` 不包含真实文本

### Requirement: SPEC-AXS-016、SPEC-AXS-016a、SPEC-AXS-016b Windows 角色映射
Windows 原生 ControlType SHALL 映射到固定中性角色词表；无法识别或无法读取的角色 MUST 映射为
`Unknown`。该要求不声明未实现平台的角色映射已经存在或已有自动化覆盖。

#### Scenario: 中性角色词表
- **WHEN** Windows 边车输出已识别的角色
- **THEN** 角色必须属于 `Button/Calendar/CheckBox/ComboBox/Edit/Hyperlink/Image/ListItem/List/Menu/MenuBar/MenuItem/ProgressBar/RadioButton/ScrollBar/Slider/Spinner/StatusBar/Tab/TabItem/Text/ToolBar/ToolTip/Tree/TreeItem/Custom/Group/Thumb/DataGrid/DataItem/Document/SplitButton/Window/Pane/Header/HeaderItem/Table/TitleBar/Separator/SemanticZoom/AppBar`

#### Scenario: SPEC-AXS-T02 已知角色映射
- **WHEN** Windows 原生角色分别为 Button 或 Edit
- **THEN** 中性角色分别为 `Button` 或 `Edit`

#### Scenario: SPEC-AXS-T02 未知角色映射
- **WHEN** 原生角色无法识别或读取失败
- **THEN** 中性角色为 `Unknown`

### Requirement: SPEC-AXS-040、SPEC-AXS-041 Windows 树查询与 backend 复用
Windows 边车 SHALL 从窗口句柄取得根元素，并返回本次可读取、受安全遍历边界约束的 Control View
树投影；子节点或兄弟节点读取失败时，局部子树 MAY 被省略而查询仍返回成功。节点 SHALL 读取名称、
值、原生角色、密码标记和边界。边车 SHALL 在进程启动时初始化 Windows backend，并在该进程处理的
后续请求间复用；当前协议不承诺输出 `ClassName`。

#### Scenario: 首次与后续查询
- **WHEN** 同一边车进程处理多次有效窗口查询
- **THEN** 每次返回当次可读取的有界树投影，并复用进程启动时初始化的 Windows backend

#### Scenario: 局部节点读取失败
- **WHEN** 遍历过程中某个子节点或兄弟节点无法读取
- **THEN** 边车 MAY 省略受影响的局部子树，并返回已成功读取的安全投影

#### Scenario: 根元素不存在
- **WHEN** 句柄无法取得 Windows 根元素
- **THEN** 边车返回 `null` 或 `error` 状态，不构造伪造节点树

### Requirement: SPEC-AXS-020 生产生命周期
生产树查询 SHALL 使用共享客户端。该客户端 SHALL 在首次查询时懒启动边车；检测到边车已退出或
管道失效后，后续查询 SHALL 尝试重新启动。边车在查询开始前已经退出时，该次查询 MAY 直接启动替代
进程并成功；边车在请求处理中退出或管道失败时，当前查询 SHALL 返回空，后续查询再尝试启动。
JVM 关闭时 SHALL 对该客户端已知的边车进程发起强制终止。该要求不声明公共 API 无法创建额外客户端，
也不保证进程终止已经完成等待。

#### Scenario: SPEC-AXS-T04 首次查询懒启动
- **WHEN** 共享客户端尚未启动边车并收到首次有效查询
- **THEN** 客户端使用已解析的可用二进制启动进程后发送请求

#### Scenario: SPEC-AXS-T04 边车在查询间退出
- **WHEN** 已启动边车在两次查询之间退出
- **THEN** 下一次查询尝试启动替代进程，并 MAY 在该次查询中成功返回结果

#### Scenario: SPEC-AXS-T04 边车在查询中退出
- **WHEN** 边车在处理当前请求时退出或管道失败
- **THEN** 当前查询返回空，后续查询再尝试启动替代进程

#### Scenario: SPEC-AXS-T04 JVM 关闭
- **WHEN** JVM shutdown hook 执行且客户端仍持有边车进程
- **THEN** 客户端对该进程发起强制终止

### Requirement: SPEC-AXS-021 查询超时
单次查询 SHALL 使用 `content.axsidecar.timeout-ms` 配置的超时，默认 1500 ms。响应未在超时内到达时，
当前查询 SHALL 返回空并终止当前边车；新边车只在下一次查询时重新懒启动。

#### Scenario: SPEC-AXS-T04 查询超过配置时间
- **WHEN** 响应未在默认 1500 ms 或显式配置值内到达
- **THEN** 当前查询返回空、当前边车被终止，下一次查询重新懒启动

### Requirement: SPEC-AXS-022 单在途请求与 ID 校验
同一共享客户端 SHALL 串行化“写请求—读响应”序列，使任一时刻最多一个请求在途，并 SHALL 校验响应
`id` 与请求一致。ID 不匹配时当前查询 SHALL 返回空。

#### Scenario: 并发调用被串行化
- **WHEN** 多个调用方同时使用共享客户端查询
- **THEN** 请求按锁顺序逐个完成，不交叉消费响应

#### Scenario: 响应 ID 不匹配
- **WHEN** 边车响应的 `id` 与当前请求不同
- **THEN** 客户端丢弃该响应并返回空

### Requirement: SPEC-AXS-023 失败降级
二进制缺失、启动失败、进程崩溃、管道错误、超时、响应解析失败、ID 不匹配或非 `ok` 状态时，
无障碍查询 SHALL 返回空。标题采集 SHALL 继续使用系统窗口标题，并 MUST NOT 启用截图、OCR 或其他
正文识别回退。

#### Scenario: SPEC-AXS-T04 二进制缺失或启动失败
- **WHEN** 客户端无法解析或启动边车二进制
- **THEN** 查询返回空，标题采集继续保留系统窗口标题

#### Scenario: SPEC-AXS-T01 非法输出或非 ok 状态
- **WHEN** 边车输出无法解析、ID 不匹配或状态不是 `ok`
- **THEN** 查询返回空，不向上游暴露部分或不可信树

### Requirement: SPEC-AXS-030、SPEC-AXS-031 Java 集成语义
Java 采集边界 SHALL 使用不透明 `long` 窗口句柄，而不是向平台中性接口泄漏 `HWND`。客户端 SHALL
把中性 role 和递归节点数据适配为仅供当前调用使用的瞬时树，供标题提取消费；零句柄 SHALL 直接
返回空而不向边车发送请求。

#### Scenario: 零句柄不发起查询
- **WHEN** 前台窗口句柄为零
- **THEN** 无障碍查询直接返回空，不启动或调用边车

#### Scenario: 中性节点供标题提取使用
- **WHEN** 客户端收到合法中性节点树
- **THEN** 标题提取可消费角色、名称、值、安全标记、边界和子节点，而不持久化整棵树

### Requirement: SPEC-AXS-060、SPEC-AXS-061 Windows 发布可用性
正式 Windows 分发流程 SHALL 构建并随应用资源提供可运行边车；运行机器 MUST NOT 依赖 Cargo 或
Rust 工具链。有效 system property `content.axsidecar.path` SHALL 优先于环境变量
`CONTENT_AXSIDECAR_PATH`；没有有效显式路径时，客户端 MAY 使用随包资源。所有候选均不可用时 SHALL
按失败降级处理。

#### Scenario: Windows 分发包包含边车
- **WHEN** 使用正式 Windows 分发流程构建应用
- **THEN** 应用包包含可运行边车，目标机器无需安装 Rust 工具链

#### Scenario: 显式路径优先级
- **WHEN** system property 和环境变量同时提供有效边车路径
- **THEN** 客户端使用 system property 指定的路径

#### Scenario: 没有可用边车路径
- **WHEN** 显式路径和随包资源均不可用
- **THEN** 无障碍查询返回空并保留系统窗口标题
