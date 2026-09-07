## ADDED Requirements

### Requirement: SPEC-FILE-092 文件采集桶标识
本产品文件采集器新写入的 bucket ID SHALL 为 `watcher-file_{hostname}`，client SHALL 为 `watcher-file`。
通过兼容 HTTP 导入、ID 以 `aw-watcher-file_` 开头或 client 为 `aw-watcher-file` 的桶 SHALL 仍识别为文件来源。

#### Scenario: 本机文件 heartbeat 写入新桶
- **WHEN** 已启用文件采集的本机发送合规文件 heartbeat
- **THEN** 事件写入 `watcher-file_{hostname}`，且 bucket client 为 `watcher-file`

#### Scenario: 导入文件桶仍按文件来源处理
- **WHEN** 兼容导入包含 `aw-watcher-file_{hostname}` 的合规文件事件
- **THEN** 系统按文件来源接受并永久保留，不因缺少 `watcher-file_` 前缀而拒绝
