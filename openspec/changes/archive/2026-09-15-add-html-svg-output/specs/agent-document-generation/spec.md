## MODIFIED Requirements

### Requirement: SPEC-DOC-001 九种文档格式
系统 SHALL 支持 CSV、JSON、XLSX、DOCX、PDF、Markdown、PPTX、HTML 和 SVG，文件内容、扩展名和 MIME 类型 MUST 一致。CSV SHALL 支持表头及正确的引号、换行转义；JSON SHALL 保留记录值类型和嵌套结构；XLSX SHALL 支持带表头的明细和多工作表；DOCX、PDF 和 Markdown SHALL 支持标题、段落、列表和表格；PPTX SHALL 支持封面、分节、正文和表格页面，标题、正文和表格 SHALL 可编辑。HTML SHALL 支持包含内嵌 SVG 的离线交互页面；独立 SVG SHALL 保留矢量图形结构。格式转换 MUST NOT 静默丢弃不支持的内容。

#### Scenario: 生成九种格式
- **WHEN** 用户分别请求用九种格式生成各自支持的内容
- **THEN** 生成的文件可被对应格式解析器读取，包含请求的文字和数据，Office 文件不是整页截图

#### Scenario: 中文及长内容
- **WHEN** 内容包含中文、长段落、多行单元格或跨页表格
- **THEN** 文档保留内容，PDF 无缺字，Word/PDF 合理分页，PPT 自动拆页或报告需缩减内容，不静默裁切文字

#### Scenario: 不支持的组合
- **WHEN** 请求内容无法用指定格式表达且尚未明确简化方式
- **THEN** 工具返回可理解的限制及调整建议，不生成标记为成功的残缺文件

### Requirement: SPEC-DOC-006 资源限制和被动内容
生成 SHALL 限制输入大小、记录数量、页数、文件大小、时间和临时存储，并在达到限制时明确失败或要求缩小范围，MUST NOT 静默截断。生成与校验 SHALL 将用户和采集文本视为数据，不执行宏、脚本、公式或外部资源加载。HTML 专用生成源 SHALL 允许声明页面代码，脚本仅在用户下载后自行用浏览器打开时执行；独立 SVG MUST NOT 包含脚本或事件处理属性。普通报告字段中的脚本片段 MUST NOT 自动提升为可执行页面代码。CSV 的电子表格公式前缀 SHALL 安全转义并注明转义策略，JSON SHALL 可提供未经该展示转义的忠实值；XLSX 的非可信文字 SHALL 写成字符串。

#### Scenario: 达到预算
- **WHEN** 导出超出声明的记录数、文件大小或执行时间限制
- **THEN** 返回具体限制及缩小范围建议，不发布只包含前一部分数据的成功文件

#### Scenario: 数据含公式或外部内容
- **WHEN** 标题含公式前缀，或报告内容含脚本与外部图片地址
- **THEN** 表格打开不执行该公式，渲染不执行脚本或请求该外部资源，内容按格式允许的文本形式保留或明确拒绝

## RENAMED Requirements

- FROM: `SPEC-DOC-001 七种文档格式`
- TO: `SPEC-DOC-001 九种文档格式`

## ADDED Requirements

### Requirement: SPEC-DOC-007 离线交互 HTML
系统 SHALL 接受明确的 HTML 页面生成源并生成 UTF-8 的单个 `.html` 文件，MIME SHALL 为 `text/html; charset=utf-8`。页面 SHALL 支持内嵌 CSS、JavaScript、数据及 SVG，核心展示和交互 SHALL 在下载后由浏览器离线打开时可用，不依赖 CDN、外部脚本、样式、字体、图片或后端服务。生成源中的普通数据 SHALL 保持数据语义，不因包含标签或脚本结束标记而意外成为代码。

#### Scenario: 下载并交互
- **WHEN** 用户请求含筛选按钮、计算功能及 SVG 图表的 HTML，并下载后在断网浏览器打开
- **THEN** 页面显示中文和图形，筛选、计算及 SVG 元素更新正常，不需要配套资源文件

#### Scenario: 请求依赖外部资源
- **WHEN** 页面依赖外部脚本、样式或图片才能完成请求的功能
- **THEN** 系统明确拒绝并说明自包含要求，不静默移除资源后发布残缺成果

#### Scenario: 普通报告与直接导出
- **WHEN** 用户用既有正文、表格结构或受控数据查询请求 HTML
- **THEN** 系统生成转义文本的自包含 HTML，直接导出保留来源、条件、条数和覆盖信息，不要求模型逐条转写原始数据

### Requirement: SPEC-DOC-008 独立 SVG 文件
系统 SHALL 支持直接生成 UTF-8 的 `.svg` 文件，MIME SHALL 为 `image/svg+xml`，根元素 SHALL 属于 SVG 命名空间，具有有效的画布尺寸或 viewBox，保留文字、路径和图形等矢量元素。文件 SHALL 自包含，允许文件内片段引用，MUST NOT 包含脚本、事件处理属性、外部资源引用、外部实体或嵌入 HTML 内容。系统 MUST NOT 将整个输出栅格化以替代 SVG。

#### Scenario: 独立矢量图
- **WHEN** 用户请求 SVG 流程图，包含中文、箭头、渐变和内部复用图形
- **THEN** 下载的 SVG 可由浏览器打开，缩放保持矢量结构，内部引用有效

#### Scenario: 非法或主动内容
- **WHEN** SVG 不是合法 XML、根命名空间错误，或含脚本、事件属性、外部实体或资源引用
- **THEN** 生成明确失败，不执行内容、不读取实体引用，也不发布成功文件

### Requirement: SPEC-DOC-009 HTML 与 SVG 的受管交付
HTML 与 SVG SHALL 沿用所属会话和轮次、成功发布、下载、历史恢复及不可变版本规则。生成源 SHALL 作为有界受管源码保存并可用于本会话后续修改，不进入聊天消息正文。下载 SHALL 使用附件方式；系统 MUST NOT 在消息面、预览框或后台浏览器中执行成果，MUST NOT 自动打开下载文件或向其注入桌面 token、cookie、运行时配置或本地存储路径。页面 SHALL 不依赖 SelfAnalyst API。

#### Scenario: 修改与恢复
- **WHEN** 用户重启后要求修改本会话已有 HTML 或 SVG
- **THEN** 系统可读取其有界生成源并生成关联的新版本，旧版本仍可下载，文件卡片显示正确格式

#### Scenario: 下载不触发执行
- **WHEN** HTML 成果发布、历史会话恢复或用户点击下载
- **THEN** 应用只展示文件元数据并按附件交付，不执行页面脚本；未授权或跨会话内容请求仍被拒绝

#### Scenario: 不兼容的输入组合
- **WHEN** 页面或 SVG 源码被用于不支持的目标格式，或直接数据导出请求 SVG 而没有矢量布局
- **THEN** 系统返回明确限制，不把源码显示成普通正文后宣称转换成功
