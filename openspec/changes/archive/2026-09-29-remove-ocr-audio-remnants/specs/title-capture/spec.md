## MODIFIED Requirements

### Requirement: SPEC-CTX-003 不启用替代正文识别
当前标题采集器 MUST NOT 使用截图、OCR、麦克风、系统声音或语音转写作为无障碍查询的替代或回退。

#### Scenario: 无障碍查询不可用
- **WHEN** 无障碍查询没有返回可用结果
- **THEN** 系统只保留操作系统窗口元数据，不启动其他正文识别方式
