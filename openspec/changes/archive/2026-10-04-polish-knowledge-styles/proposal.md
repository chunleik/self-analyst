## Why

“知识”页面（`ontology.css`）是 0.6.0 随个人本体一并加入的首版样式，与桌面端其余页面的视觉语言不一致：引用了未定义的 `--accent-color` 等变量而落到硬编码回退色，徽标底色与卡片同为白色而不可辨认，弹窗受全局 `margin: 0` 重置影响未在视口居中，列表点击后选中高亮不随之更新，筛选区复选框与分页、状态提示缺少排版。

## What Changes

- 统一使用 `styles.css` 已定义的设计变量（颜色、圆角、阴影、过渡、等宽字体），移除硬编码颜色与未定义变量。
- 重排页面层次：标题区、筛选卡片、状态与来源覆盖提示、实体列表与详情两栏；宽屏下实体列表吸附并独立滚动，窄屏下单栏堆叠。
- 实体列表项改为圆角条目，提供悬停、选中（含 `aria-current`）与键盘焦点状态；点击实体或关系链接后选中高亮即时更新。
- 筛选表单：输入控件获得统一的边框、焦点环；“仅未归类”复选框改为行内排列。
- 详情区：元信息段落间距、关系条目卡片化、证据折叠区与证据引用的等宽样式、删除按钮使用危险色。
- 弹窗：视口居中、阴影、遮罩与项目其他弹窗一致。
- 仅迁移既有界面的视觉呈现，不新增、不修改、不移除任何功能、接口、文案或数据行为。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。`personal-ontology` 的 SPEC-ONTO-009 所规定的入口、过滤、管理、状态与键盘可操作性均保持不变，本变更不改变规格级行为，因此在 `.openspec.yaml` 中设置 `skip_specs: true`。

## Impact

- `self-analyst-app/src/main/resources/desktop-ui/ontology.css`：样式重写。
- `self-analyst-app/src/main/resources/desktop-ui/ontology.js`：仅增加样式钩子类名与选中态同步，不改变请求与数据流。
- `self-analyst-app/src/test/js/`：新增知识页样式回归测试，补充选中态断言。
- 不涉及后端、API、依赖、国际化文案或用户文档。
