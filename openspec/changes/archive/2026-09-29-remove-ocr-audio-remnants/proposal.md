## Why

声音与 OCR 模块已经移除，但仓库仍保留恢复指南、旧模块描述及专用兼容代码，容易误导维护者。用户明确要求连旧配置与旧事件兼容一起清理。

## What Changes

- 删除模块移除/恢复指南及入口，修正 Maven 描述，精简现行文档中的过时说明。
- **BREAKING** 删除 OCR/声音配置墓碑与结构化 OCR 映射，旧键使用普通未知键规则。
- **BREAKING** 内容写入不再接受 `ocr_chars` 和 `ocr_title`；迁移只输出现行标题字段，不再保留旧来源。
- 保留禁止正文采集的隐私边界、历史 OpenSpec/发行记录和发布目录防污染清理；不操作用户历史文件。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `user-configuration`: 移除旧采集键的专用兼容待遇。
- `content-event-persistence`: 移除旧字段及来源的接受和保留规则。
- `title-capture`: 删除历史 OCR 兼容例外。

## Impact

影响配置服务、桌面配置映射、事件校验、标题迁移及相应测试；更新 README 双语版本、隐私及架构文档。迁移继续遵循原有高水位和备份边界，不引入启动时全库重写；已有未重扫记录不作为新写入合法性的依据。
