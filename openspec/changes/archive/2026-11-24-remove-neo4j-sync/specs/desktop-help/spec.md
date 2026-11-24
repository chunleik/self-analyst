## MODIFIED Requirements

### Requirement: SPEC-HELP-DOCS-001 帮助文档与个人知识指南
帮助菜单 SHALL 在“使用指南”后提供中文“帮助文档”“个人知识指南”及对应英文“Documentation”“Personal knowledge guide”。帮助文档 SHALL 指向固定项目文档索引 `https://github.com/chunleik/self-analyst/blob/main/docs/README.md`，个人知识指南 SHALL 指向 `https://github.com/chunleik/self-analyst/blob/main/docs/personal-ontology.md`，两种界面语言均使用这些已存在的文档，不推导不存在的译本。打开方式、运行数据隔离、失败提示和桌面安全重试 SHALL 与项目官网保持一致。现有使用指南 SHALL 继续根据语言打开对应 README。

#### Scenario: 查阅知识相关说明
- **WHEN** 用户选择“个人知识指南”或“Personal knowledge guide”
- **THEN** 系统默认浏览器或 Web 新标签页打开现有个人知识文档，应用当前页面与草稿不受影响

#### Scenario: 查阅文档索引
- **WHEN** 用户选择“帮助文档”或“Documentation”
- **THEN** 系统默认浏览器或 Web 新标签页打开项目文档索引，链接不包含本地运行数据或凭据

#### Scenario: 文档打开失败
- **WHEN** 打开任一新增文档目标发生可检测错误
- **THEN** 应用显示本地化错误及对应可复制链接，桌面重试仍由系统默认浏览器打开，关闭错误提示后可继续操作菜单
