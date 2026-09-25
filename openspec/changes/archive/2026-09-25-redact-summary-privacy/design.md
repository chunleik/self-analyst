## Context

见 proposal.md。窗口采集目前只有应用名和标题。Windows 可通过前台进程命令行识别 `--incognito`、`--inprivate` 和 `-private-window`。

## Goals / Non-Goals

**Goals:**

- 敏感文本不进入模型输入。
- 无痕窗口和用户排除的应用、网站不形成标题事实。

**Non-Goals:**

- 不修改已经保存的历史摘要。
- 不把排除规则做成桌面设置界面。配置键即可。

## Decisions

排除和脱敏在采样生成事实时执行，统计仍使用全部窗口事件。命令行识别只在 Windows 前台进程上做；其他系统仍靠标题标记。

## Risks / Trade-offs

普通 Chrome 无痕窗口的标题不一定包含“无痕”。没有命令行标记时，只能排除标题本身写明无痕的窗口。
