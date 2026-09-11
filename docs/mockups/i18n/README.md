# 国际化视觉验收

截图来自 2026-09-11 的隔离测试实例，采集和 LLM 关闭，API Key 为空；原生菜单使用正式创建函数及模拟自启动状态，不修改系统启动项。

| 场景 | 英文 | 中文 |
| --- | --- | --- |
| 新生成页面 | [英文页面](en-agent.png) | [中文页面](zh-agent.png) |
| 原生菜单 | [英文长文案](en-menu.png) | [中文禁用状态](zh-menu.png) |
| 关于弹窗 | [英文关于](en-about.png) | [中文关于](zh-about.png) |
| 配置错误 | [英文错误](en-settings-error.png) | [中文错误](zh-settings-error.png) |

[语言保存后仍需重启](en-language-saved.png)展示已保存中文而当前运行语言仍为英文；[启动失败](zh-startup-failure.png)展示后端不可用时按系统语言提供提示。切换语言不会回填翻译已生成的摘要，因此部分设置截图的背景仍可见旧语言快照。

完整实施记录见 `openspec/changes/archive/2026-09-11-extend-internationalization/implementation.md`。
