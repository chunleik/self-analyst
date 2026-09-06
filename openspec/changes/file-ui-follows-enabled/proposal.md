## Why

文件采集默认关闭，但桌面端始终露出「文件」页签和禁用态空页面，用户会误以为功能已接入。界面应跟随 `file.watch.enabled`：关闭时收起文件页，开启后再出现。

## What Changes

- **修改**：顶栏「文件」页签仅在文件采集启用时可见；关闭时隐藏页签，不再展示禁用态文件页。
- **修改**：关闭采集后若用户正停在文件页，桌面端立即切回 Agent。
- **修改**：右上角「文件」状态点也跟随开关；关闭时一并隐藏，开启后显示并可进入文件页。
- **修改**：采集设置弹窗只管理启用开关；监控目录的添加与保存改到已启用的文件页。
- **修改**：允许在尚未配置监控目录时启用采集，文件页随后出现以便补目录。
- **保留**：齿轮全局配置中的 `file.watch.enabled` 仍可作为启用入口。
- **非 BREAKING**：`GET /desktop/files` 字段不变；`PUT /desktop/files/settings` 仍接受 `enabled` 与 `paths`，但不再因空目录拒绝启用。

## Capabilities

### New Capabilities

- 无

### Modified Capabilities

- `file-metadata-collection`：文件页签跟随采集开关；设置面只管理开关，监控目录在文件页编辑。

## Impact

- 桌面 UI：`index.html` 页签、`ui.js` 状态栏、`files.js` 页签渲染、`events.js` 状态点点击。
- 规格：`openspec/specs/file-metadata-collection/spec.md` 中 SPEC-FILE-071 及相关场景。
- 测试：`self-analyst-app/src/test/js/file-collector.test.mjs` 及可能新增的页签显隐测试。
- API、采集器与配置默认值不变；`file.watch.enabled` 仍默认为 `false`。
