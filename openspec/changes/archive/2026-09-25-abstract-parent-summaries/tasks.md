## 1. 父子输入

- [x] 1.1 HALF_DAY 依赖 HOUR，DAY 依赖 HALF_DAY，HOUR 仍读原始事实。验证：`WikiWorkerTest.halfDayWaitsForHoursAndDoesNotReadRawTitles` 与 `WikiWorkerTest` 通过。
- [x] 1.2 子周期有效秒数记在首个片段上。验证：`WikiFactBuilderTest` 通过。
- [x] 1.3 上层最多 3 个主题，长尾不点名。验证：`WikiSummaryPipelineTest` 通过。

## 2. 规格与全量验证

- [x] 2.1 把增量规格同步到 `openspec/specs/llm-wiki/spec.md`，运行 `openspec validate abstract-parent-summaries --strict`。
- [x] 2.2 运行 `mvn test`。
