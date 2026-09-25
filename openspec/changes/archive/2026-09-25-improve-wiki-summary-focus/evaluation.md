# noisy-focus 实机对比

模型 `deepseek-flash`，基址为安装版配置中的 `llm.base-url`。夹具是匿名合成数据，各 1 次调用。原始 JSON 留在本地 `target/`，不提交。

| | 改动前（main，diagnostic-http） | 改动后（本分支，formal） |
|---|---|---|
| 状态 | accepted | accepted |
| 片段数 | 5 | 2 |
| primaryTask | 多应用界面查看：Chrome 页面浏览、企业微信沟通、搜索与系统锁屏 | 企业微信沟通 |
| 免责声明次数 | 0 | 0 |
| 噪声是否进入文案 | 进入。片段包含 SelfAnalyst 主窗口、Windows 搜索和锁屏 | 未进入。采样元数据 `noiseOmittedFacts=4` |

改动前的正式传输在 213 毫秒内返回 `WIKI_MODEL_FAILURE`，没有可用文案，所以改动前的数字来自同一模型的 diagnostic-http。改动后的 diagnostic-http 因 4096 token 上限被截断，不作为对比；正式传输一次完成。

这次样本里两边都没有写出“不证明”一类免责声明。能看到的差别是主次和噪声：改动后把用时最长的企业微信沟通放在首位，不再把锁屏、搜索和 SelfAnalyst 主窗口写成任务。
