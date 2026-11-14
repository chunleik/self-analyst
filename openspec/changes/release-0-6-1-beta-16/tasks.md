## 1. 准备与验证

- [x] 1.1 核对 #155 精确 head `e37f46f` 的五项检查均成功，基于实际合并 main `2db1d8f` 建立版本分支，确认 beta.16 标签未占用。
- [x] 1.2 同步 13 个自身版本文件，解析 XML/JSON/TOML 确认一致；新增 `docs/releases/v0.6.1-beta.16.md`，保留实机验收限制。
- [x] 1.3 运行发布通道 Node 测试、全量 Node 与 OpenSpec strict、diff 检查；记录平台跳过。提交独立版本 PR，等待最新提交全部必需检查。

## 后续发布门禁

版本 PR 合并后才在精确合并提交打标签。使用现有 GitHub Actions 打包，核验工作流成功、便携 ZIP、NSIS EXE 和两份 SHA-256 后报告完成。当前工具若无法创建标签/Release，明确报告已合并但未发布，不改工作流绕过、不声称打包已启动。发布保持 Pre-release、非 latest，不自动归档其他 change。

## 本地验证记录

13 个自身版本文件逐文件一处替换，XML/JSON/TOML 解析和包名定位一致；`git diff --check` 通过。发布通道 Node 23 项因非 Windows 全部跳过，当前无 pwsh，未运行实际 PowerShell 通道判定，不计为通过。全量 Node 411 项：379 通过、32 跳过、0 失败。OpenSpec 1.11.0 `validate --all --strict` 45 项通过。Windows 实际版本校验与全量验证等待版本 PR 最新提交 CI。
