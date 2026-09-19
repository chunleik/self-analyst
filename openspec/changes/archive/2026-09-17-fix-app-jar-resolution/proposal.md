## Why

Maven Shade 在本机构建后同时留下正式 JAR 和 `-shaded.jar`，现有解析器把两者都视为可执行分发候选，导致绿色版等打包流程中断。已确认本次两个文件内容相同，但解析应依据产物角色，而非临时人工搬移或文件大小。

## What Changes

- 在现有文件名前缀匹配之后排除 `-shaded.jar` 中间产物。
- 保留正式 JAR 缺失、输出目录缺失及多个正式版本并存时的明确失败，不按时间或大小猜测版本。
- 为正式/中间产物共存、SNAPSHOT 版本、缺失及歧义场景增加真实 PowerShell 调用回归测试。

## Capabilities

### New Capabilities

无。这是构建工具修复，使用 `skip_specs: true`，不新增产品行为规格。

### Modified Capabilities

无。桌面运行、分发布局和数据目录契约保持原有语义。

## Impact

- `scripts/resolve-app-jar.ps1`，以及共用该解析器的绿色版、安装版、普通分发和打包检查流程。
- `self-analyst-app/src/test/js/resolve-app-jar.test.mjs`：临时目录中的文件选择回归，不编译应用、不启动应用、不移动用户数据。
