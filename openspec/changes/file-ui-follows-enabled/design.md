## Context

见 `proposal.md`。当前桌面端始终渲染「文件」页签；`GET /desktop/files` 已返回 `enabled`，采集设置弹窗已支持热更新。可见性只需跟随该标志，不改后端契约。

## Goals / Non-Goals

**Goals:**
- 用现有 `enabled` 驱动页签显隐，避免再引入前端功能开关。
- 关闭时仍能从状态栏进入采集设置，齿轮配置中的 `file.watch.enabled` 继续可用。

**Non-Goals:**
- 不改采集器或 FileTools 查询边界。
- 不把齿轮 raw 配置改成热更新；该入口沿用现有重启语义。

## Decisions

1. **可见性键用 `overview.enabled`，不用 `status === "running"`**
   - 启用但 degraded（路径无效、worker 失败）仍要看到文件页以便排障。
   - 备选：按 `status !== "disabled"`。否决原因：与 `enabled` 语义重复，且把 `runtime_unavailable` 也当成可展示页。

2. **关闭时一并隐藏右上角文件状态点**
   - 与页签同一可见性键，避免禁用态仍露出文件入口。
   - 重新启用走齿轮配置中的 `file.watch.enabled`。

3. **关闭后若当前 tab 是 files，切回 Agent**
   - 比留在空白 `tab-files` 更安全。
   - 备选：切到会话页。否决原因：Agent 是默认首页，行为更可预期。

4. **设置弹窗只开关，目录编辑在文件页**
   - 先开开关再补目录，避免隐藏页签后无处添加目录。
   - 启用且无目录时后端返回 `degraded/paths_unavailable`，文件页展示目录编辑器。
   - 备选：启用仍强制至少一个目录。否决原因：设置面已不再编辑目录。

## Risks / Trade-offs

- [首次使用找不到文件功能] → 保留右上角「文件」状态点与齿轮配置两项入口。
- [齿轮改 `file.watch.enabled` 后页签不立刻出现] → 沿用现有重启语义；日常开关走采集设置热更新。
- [加载完成前页签闪一下] → 在拿到 `enabled` 前默认隐藏页签，与默认关闭一致。
