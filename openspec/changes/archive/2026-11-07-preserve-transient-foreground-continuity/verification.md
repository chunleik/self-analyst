# 验证记录

## 基线与范围

- 基于已合并 #145 的 `main`：`53bb05ef0bca0e9a0801b7e52599ed9d39a62a97`。
- 修改仅限内容采集器、合成回归测试及标题采集规格；未修改 AQL、依赖、配置、版本或发布工作流。
- 先创建 change 并完成规划产物，再按任务实施；主规格 `SPEC-WCH-001` 已同步。

## 回归证据

- 空窗口/零句柄在捕获、心跳及发送前复查中的新增测试，在原实现复现 14 个失败。
- 补充的 UIA 完成确认与已准备心跳交错测试，在缺少确认失效分支时复现 4 个失败。
- 最终定向命令：`mvn -pl self-analyst-content -am '-Dtest=ContentWatcherTest,ContentHeartbeatDeliveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 结果：42 项通过，0 失败、0 错误、0 跳过。覆盖完整重试 body、sourceEventId/captureSessionId、次数上限、恢复后新采集、排除优先级及两种查询交错。
- 独立只读复审已核对最终代码与测试，无剩余范围内问题。

## 全量与规格验证

- 同步最新 main 后重新运行 `mvn test`：Java 共 1275 项，1268 通过、7 跳过，无失败或错误；Node 共 345 项，313 通过、32 跳过。
- Java 跳过项为两项显式启用的 benchmark、两个 Windows/UIA 冒烟、三个 Windows 凭据测试；Node 跳过项为 Windows 平台限定测试及 Windows CI 浏览器冒烟。未把这些项计为通过。
- 容器的 JDK 21 缺少完整 `--release 21` 支持，因此沿用现有编译适配器，将该选项转换为同一 JDK 的 `-source 21 -target 21`，并使用缓存依赖离线执行 Maven。仓库构建配置未改动。
- `openspec validate preserve-transient-foreground-continuity --strict` 通过；归档前 `openspec validate --all --strict` 共 40 项通过，归档后 39 项通过。
- `git diff --check` 通过。
- Windows 原生编译、UIA 合成冒烟及其他必需检查由 PR CI 验证；本地未执行打包、发版或读取真实前台。

## 文档与归档

仅澄清内部采集连续性，无新增用户操作或配置，因此无需调整 README、`PRIVACY.md`、`docs/testing.md` 或 `docs/architecture.md`。现有 `docs/archive/` 历史材料继续保留作背景，不引入新的行为来源；本 change 随实现完成归档。
