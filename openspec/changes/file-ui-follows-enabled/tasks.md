## 1. 页签可见性

- [x] 1.1 为 `isFileCollectionEnabled` / `syncFileUiVisibility` 补 Node 测试，覆盖默认隐藏、启用显示、关闭后离开文件页；用 `node --test self-analyst-app/src/test/js/file-collector.test.mjs` 确认先失败
- [x] 1.2 实现页签跟随 `enabled`（未知时默认隐藏），并在状态刷新与设置保存后同步；再跑同一测试确认通过

## 2. 关闭入口

- [x] 2.1 为右上角文件状态入口补测试：关闭时打开采集设置、开启时进入文件页；先确认失败
- [x] 2.2 改状态点点击行为并接上事件绑定；再跑 `file-collector.test.mjs` 确认通过

## 3. 规格同步

- [x] 3.1 把 delta 合入 `openspec/specs/file-metadata-collection/spec.md` 的 SPEC-FILE-071 / SPEC-FILE-074
- [x] 3.2 再跑 `node --test self-analyst-app/src/test/js/file-collector.test.mjs` 确认全部通过

## 4. 开关与目录分面

- [x] 4.1 为设置弹窗去目录、文件页托管目录编辑、无目录也可启用补测试，先确认失败
- [x] 4.2 实现分面并允许空目录启用；用 `node --test self-analyst-app/src/test/js/file-collector.test.mjs` 与 `DesktopFileControllerTest` 确认通过
