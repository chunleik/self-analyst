## Why

中文版首页精简后更容易阅读，但“所有窗口信息”和“下载安装即可使用”会夸大采集范围并遗漏模型配置条件。两种语言的配置说明与章节布局也需要对齐。

## What Changes

- 保留用户精简首页、提前下载入口的方向，准确介绍前台活动与标题采集。
- 补齐 Windows 下载、模型设置、托盘退出和联网数据边界，提供简短功能及文档入口。
- 同步中英文 README 的章节和信息；依据既有模型设置规格更新中文旧说明。
- 将长期记忆说明移出许可证章节，保留必要的数据保留说明。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。仅整理既有行为的文档，设置 `skip_specs: true`，不改变主规格或实现。

## Impact

仅修改 README.zh-CN.md、README.md 与本 change 的追溯材料。验证 Markdown 本地链接、双语章节与配置表一致性、英文正文语言及 git diff --check；无需运行业务测试。现有 docs/ 文档保持原位并通过链接访问。
