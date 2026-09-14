## 1. 会话界面

- [x] 1.1 修改 self-analyst-app/src/main/resources/desktop-ui/chat.js 及相关布局样式，移除长期记忆面板的渲染、专用加载和事件绑定；验证打开、切换与刷新会话均无面板、空白占位或专用列表请求。
- [x] 1.2 更新 self-analyst-app/src/test/js/ 下相关回归测试，覆盖面板不显示、会话发送与切换正常；运行受影响的 node --test 测试文件并通过。

## 2. 后台总结与兼容验证

- [x] 2.1 修改 MemoryExtractionService 自动筛选及相关提示词：合格 active，其余丢弃，不新增自动 pending；confirm_all 按 smart 兼容，off 保留。补充并通过合格归纳、凭据、敏感推断、低可信和一次性信息回归测试。
- [x] 2.2 运行 DesktopMemoryControllerTest 与 MemoryExtractionServiceTest，验证面板移除后 API、去重、过滤和后台失败隔离仍正常；使用 mvn -pl self-analyst-app -am '-Dtest=DesktopMemoryControllerTest,MemoryExtractionServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test。
- [x] 2.3 对桌面会话界面进行视觉检查，记录面板消失及聊天区域布局正常的截图证据。

## 3. 规格与收尾

- [x] 3.1 同步 openspec/specs/long-term-memory/spec.md，并检查 README.md 和 docs/ 中现行用户说明，更新仍要求通过会话面板操作的内容；确认文档描述与实际行为一致。
- [x] 3.2 运行 openspec validate hide-memory-panel-auto-summarize --strict，确认通过；完成上述验证后按归档技能归档并记录验证结果。

## 4. 历史重评与会话纠错

- [x] 4.1 在记忆服务和桌面启动链路实现后台分批历史 pending 重评，合格项原 ID 激活，其余删除；用临时存储测试验证重启幂等、模型/预算/保存失败保留与重试，以及并发更正和删除不被覆盖。
- [x] 4.2 在聊天提示词和受控记忆操作链路加入质疑引导、明确更正和忘记；验证指代不明先追问、明确内容直接更新、off 下显式纠错正常、失败不虚报成功，以及下一轮不再使用旧内容。
- [x] 4.3 更新用户说明中的会话澄清示例和自动筛选、迁移行为；运行相关 Java 与 Node 回归测试后再完成第 3 节的规格同步、严格校验和归档。


## 验证记录

- 针对性 Maven 测试：DesktopMemoryControllerTest、MemoryExtractionServiceTest、PendingMemoryReviewTest、MemoryToolsTest 通过；随构建运行的桌面 Node 测试通过。
- 静态布局视觉检查：使用现有 index.html 与 CSS 构造不连接用户后端的会话页预览，浏览器截图确认无长期记忆面板或占位；截图见实施会话工具记录。未使用真实用户记忆进行迁移测试。
- 历史 docs/archive/legacy-specs/long-term-memory.md 保持历史资料属性，不作为现行契约；现行行为由主规格及 README 说明。

- 全量 `mvn test` 通过；桌面 Node 测试 124 项通过；25 项主规格校验及本 change 严格校验通过。
