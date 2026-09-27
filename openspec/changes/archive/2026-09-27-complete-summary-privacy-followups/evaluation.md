# complete-summary-privacy-followups 实机评测

模型 `deepseek-flash`，传输 Formal PlainTask，温度 0.2。夹具为匿名合成数据；原始 JSON 留在本地 `target/followup-*-live.json`，不提交。

## noisy-focus

| | 结果 |
|---|---|
| 状态 | accepted |
| 片段数 | 2 |
| primaryTask | 企业微信沟通 |
| 噪声 | 未写入摘要文案 |

## missing-activity-coverage

| | 结果 |
|---|---|
| 状态 | accepted |
| primaryTask | 数据库查询参考 |
| 说明 | 覆盖不完整场景仍可产出可校验摘要 |

## parent-abstract-privacy

| | 结果 |
|---|---|
| 状态 | accepted |
| privacyInputOk | true（输入无内网 IP、会议号、私聊、无痕） |
| 子摘要条数 | 10（排除应用/无痕后） |
| 片段数 | 3（未超过上层主题上限） |
| primaryTask | 文档发布与页面浏览 |
| summary | 整体主线围绕订单后台处理、协作沟通，以及文档发布运维与页面浏览展开。 |

敏感片段未出现在最终文案。主题已收敛到三张卡片；本次模型输出本身未触发“其余活动”折叠。
