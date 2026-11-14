# 实施状态与历史暂停记录

2026-11-14 用户要求转为“上传到我本地的 Langfuse，结合 MCP 删除和查询，一起写提案”。已停止继续实施和测试。以下是指令到达前已完成的工作，不代表统一提案已全部实现。

## 工作区

- 云端目录：`/workspace/scratch/4a0b433e65ea/self-analyst-main`
- 分支：`codex/connections-delete-tool-dialog`
- 基线：`4957820373a90f9b4a87fa0b0eb04aef6d1722b4`（main / beta.16）
- 所有代码、文档和主规格修改均保留为未提交差异；无 push/PR/合并/发版/归档。
- 没有访问用户电脑、真实邮箱、真实 MCP 或用户 Langfuse。

## 已保留实现

1. 受管 `POST /desktop/integrations/delete`，版本/kind/id 校验、定向删除、复用授权和凭据清理；锁定一致快照、严格 JSON、catalogAvailable 状态。
2. 连接页草稿/已保存删除、明确确认、保留其它草稿、冲突处理及结果未知写入保护。
3. MCP tools 只读弹窗，真实目录、描述/schema/批准状态、显式发现、中英/键盘/焦点处理；不包含 resources/prompts。
4. Java/Node 测试、合成浏览器脚本、双语 README/连接文档及 MCP 相关主规格差异。

## 已结束的验证

- 针对性 Java：14 项全部通过，0 失败/错误/跳过；Maven 在暂停指令前正常退出 0。
- 同次 Maven 随附 Node：432 项，400 通过、32 条件跳过、0 失败。
- 单独前端 Node `*.test.mjs`：427 项，395 通过、32 条件跳过、0 失败。
- MCP 弹窗独立 Node：10/10；连接模型+弹窗聚焦：34/34。
- 旧 MCP-only 范围 OpenSpec 严格校验：46/46；统一提案扩展后的 change 严格校验通过，全项目文档严格校验也为 46/46。
- Python 合成浏览器脚本语法及 diff 检查通过。

## 未完成/受阻

- 完整 Java reactor 未运行；暂停后不继续启动。
- Chromium 进程单例 socket 被云端 OS 拒绝（Operation not permitted），浏览器脚本未实际执行场景、无截图。
- 另尝试可用云端 CUA 浏览器访问已观察到的合成服务器地址，返回 net::ERR_CONNECTION_REFUSED；未访问到页面。合成服务器已停止。
- Windows/Tauri 原生视觉及端到端、真实 MCP/QQ/163/Langfuse 未验证。
- Langfuse 部分仅 proposal/design/specs/tasks，没有生产实现、依赖、配置或上传行为。

## 当时的后续门槛

暂停时仅继续完善和校验统一提案；该限制已由下方新的明确实施/交付指令更新。仍不得把任务表中的既有实现勾选解释为整体完成。

## 用户后续修订与授权

用户要求 Langfuse 为开发者功能、可单独选择上传隐私内容、服务器客户自定。提案已改为 local/LAN/远程目的地，非回环强制 HTTPS，元数据默认及分类别/来源内容授权；首期图片/附件明确不上传内容，不绕过采集/模型过滤。新能力路径改为 `developer-observability`，原 SPEC-OBS ID 保留。

随后用户明确授权“调整完后，实施、提 PR，然后合并，最后发版”。修订规格后可按任务恢复实施，不能据此连接用户电脑或实际上传私人内容，不本地打包、不绕过必需检查、不合入无关 #154。上方暂停测试事实仍有效，Langfuse 实际完成情况由后续任务/验证补充。

## 恢复实施（进行中）

已新增独立观测配置/受管 API、Windows 安全凭据绑定、OTel SDK + 专用 OTLP HTTP/JSON 传输、有界队列/撤销、模型/工具路径与开发者 UI；DesktopServer 负责共享服务接线和关闭。数据守卫识别可选观测文件但不迁移、不修复、不启用，坏配置由观测服务单独 fail closed。

安全审阅正在补足撤销/DNS 竞态、结构化媒体排除、被权限拒绝模型不导出内容、OTLP 部分拒绝、收件方 preview/IP/CA 确认。尚未视为最终验收通过。浏览器合成脚本已接入现有 Linux CI job，当前本地浏览器限制未改变。仅做测试/编译，不生成本地发布包。

## 提交前验证完成

最终完整 Maven 七模块成功：Java 1406 项（1398 通过、8 跳过），Node 448 项（416 通过、32 跳过），0 失败/错误。独立安全复核此前阻断均已消除。主规格新增 developer-observability，连接/用户配置同步；双语 README、PRIVACY、第三方 Apache-2.0 依赖声明与用户指南已更新。浏览器/Windows/真实服务限制见 tasks.md，仍无真实邮箱/服务数据或本地发布包。后续按授权发布功能 PR，等待其最新提交 CI 后合并。
