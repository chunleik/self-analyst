## Why

当前会话仅传递文本，即使配置视觉模型，用户也无法发送截图或图片进行分析。需要接通用户主动选择的图片从输入、持久化到模型请求的完整链路，并保持会话重试与恢复语义。

## What Changes

- 新增图片选择、粘贴截图、预览、移除、图文及纯图片发送，增强消息面与原生 fallback 行为一致。
- 新增独立受管图片上传、服务端消息关联及历史图片读取；图片不写入消息 content 或业务 context。
- 新增视觉模型输入与明确的不支持图片错误，失败仍可复用原轮次重试。
- 增加会话删除、孤立上传清理、图片资源限制与相关回归验证。
- 此功能仅处理用户主动提交的图片，不扩展后台截图或文件内容采集。

## Capabilities

### New Capabilities

- `chat-image-input`: 用户主动图片输入、受管存储、历史恢复、多模态请求及生命周期。

### Modified Capabilities

无。以新增图片能力扩展现有文本会话；既有消息状态机、生成文档关联和文本请求预算继续有效。

## Impact

- 桌面 UI 的 composer、Deep Chat 适配器、原生 fallback、API 客户端及中英文文案。
- DesktopServer 路由、聊天控制器、ChatSessionStore、SQLite 迁移及删除协调。
- SelfAnalystAgent、模型请求适配、AgentState 恢复与上下文压缩。
- 新增独立二进制上传接口，消息 JSON 只携带服务端图片 ID；兼容已有无图片会话。
- 更新 README.md 与 README.zh-CN.md、相关用户文档和主规格。
