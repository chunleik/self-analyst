## 1. 隐私

- [x] 1.1 删除窗口采集的命令行无痕检测和 `private_browsing` 标记，标题采样不再读取该字段，无痕标题标记补充“隐身”。验证：`WikiTitleSamplerTest` 通过，全仓不再出现 `privateBrowsing`。
- [x] 1.2 扩展会议号识别，增加文本片段脱敏与子片段排除，并在父级事实构建中使用。验证：新增 `WikiPrivacyPolicyTest` 与 `WikiFactBuilderTest` 中旧子摘要场景通过。
- [x] 1.3 排除应用不出现在提示词应用权重中。验证：`WikiFactBuilderTest.parentRedactsOldChildSummariesAndHidesExcludedApps` 断言提示词不含排除应用，`topApps` 仍含该应用。

## 2. 摘要

- [x] 2.1 上层摘要本地截成一句，小时摘要不变。验证：`WikiSummaryPipelineTest` 的父级与小时用例通过。
- [x] 2.2 删除置信度解析中的覆盖不确定参数。验证：`WikiEvidencePolicyTest` 与 `WikiSummarizerTest` 通过。

## 3. 文档与验证

- [x] 3.1 在 README 与 README.zh-CN 配置表中加入两个隐私配置项，并说明匹配方式和无痕限制。验证：检查两份 README 的完整 diff，英文版不含中文说明。
- [x] 3.2 同步主规格，运行 `mvn test` 与 `openspec validate fix-summary-privacy-gaps --strict`。验证：两者均成功。
