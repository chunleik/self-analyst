## 1. 采样和输入

- [x] 1.1 为跨来源完全/部分覆盖、同应用多主题、长观察降权及紧凑输入编写回归测试，先运行并确认旧实现失败。
- [x] 1.2 实现标题跨来源去重、紧凑模型投影及时间/主题排序，运行 WikiTitleSamplerTest、WikiFactBuilderTest 和 WikiSummarizerTest 验证时长、引用和单次模型调用。
- [x] 1.3 将应用配置默认预算统一为 24000，补充 Config、模板及显式覆盖测试并运行通过。

## 2. 复验与交付

- [x] 2.1 使用上轮冻结三日快照，在 12000/24000 下记录输入覆盖；调用用户配置 DeepSeek 生成新版三日摘要并保存本地对比报告，不覆盖上轮结果。
- [x] 2.2 同步双语 README、架构文档及 Wiki 主规格，运行跨模块 Maven 测试和 OpenSpec 严格校验后归档。

验证记录：修改前4项新回归均失败；定向修复后通过。`mvn test` 完整运行通过（Java 790项、6项既有条件跳过，Node 167项通过）；最后的时间/语义边界以 `mvn -pl self-analyst-wiki -am '-Dtest=WikiTitleSamplerTest,WikiFactBuilderTest,WikiSummarizerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 复验25项通过。

冻结快照本地输入：相同12000预算由第一阶段17/16/16条增加为65/58/55条；24000为98/120/118条。本轮三次deepseek-flash真实调用全部成功解析，输入token合计30742，具体三日摘要与用量保存在Git忽略目录 `.tmp/summary-eval-topic/comparison.md`。最终代码再次生成的六份prompt与调用时逐字节相同。本轮没有改写用户事件库、已有Wiki或用户配置；评估为单次配对，不宣称统计学质量结论。
