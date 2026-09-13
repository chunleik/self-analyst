## ADDED Requirements

### Requirement: SPEC-DSK-DOC-001 原生另存为
受管桌面窗口 SHALL 为成功文档打开系统另存为对话框，使用建议文件名和格式过滤器，并允许选择应用数据目录之外的用户目录。文件来源 SHALL 仅为当前受管后端认证的指定会话文档，目标路径 SHALL 来自对话框选择，MUST NOT 接受模型提供的任意写入路径。已有目标文件 SHALL 由用户明确确认覆盖；写入失败 MUST 保留既有目标内容，取消 SHALL 不写入。原生命令 MUST 限于受信任桌面页面，禁止外部页面调用。

#### Scenario: 保存到中文目录
- **WHEN** 用户选择包含中文或空格的其他目录并确认保存
- **THEN** 系统保存与生成成果相同的完整字节，完成后报告成功

#### Scenario: 覆盖、取消和失败
- **WHEN** 目标已存在而用户拒绝覆盖，取消选择或保存写入失败
- **THEN** 既有目标保持不变，系统分别报告取消或失败，应用内成果保持可用

### Requirement: SPEC-DSK-DOC-002 浏览器与发行包支持
浏览器入口 SHALL 提供遵循既有 cookie 认证的文件下载，位置由浏览器设置或保存对话框管理。下载 MUST NOT 在 URL 携带 token，也不得将凭据发送给外部 origin。正式安装包与便携包 SHALL 支持七种格式生成和桌面另存为，不依赖用户安装 Office、Python 或 Node。

#### Scenario: 浏览器下载
- **WHEN** 用户从已认证浏览器会话下载成功文件
- **THEN** 下载使用正确文件名和类型，未经认证请求被拒绝，界面只确认已发起下载

#### Scenario: 干净 Windows 环境
- **WHEN** 用户在未安装 Office、Python 和 Node 的受支持 Windows 环境使用正式分发
- **THEN** 七种格式可生成且可另存为，查看或编辑文件可使用用户自行选择的兼容应用
