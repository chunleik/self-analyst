## 1. 页面调整

- [x] 1.1 修改 desktop-ui/index.html 和 locales/zh.json、locales/en.json，将导航显示为看板 / Dashboard；保留内部 tab 标识以缩小影响，通过语言切换验证入口一致。
- [x] 1.2 修改 desktop-ui/index.html、styles.css，移除建议卡片和任务侧栏结构与专属布局，时间轴单栏占满可用宽度；验证宽屏和 800×600 下无残留占位或横向溢出。
- [x] 1.3 调整 desktop-ui/agent.js、init.js、ui.js、events.js 中被删除节点的渲染、缓存、监听和刷新引用；核对 chat.js 的任务依赖，保留共享任务加载与会话能力，通过无对应 DOM 节点的初始化、刷新及任务事件回归验证无异常。

## 2. 验证与规格同步

- [x] 2.1 更新或新增 self-analyst-app/src/test/js 下的页面回归测试，覆盖无建议/任务区域、非空和空摘要、快照刷新、详情展开与继续追问；先运行相关测试，再运行 `node --test self-analyst-app/src/test/js/*.test.mjs`，记录结果。
- [x] 2.2 对宽屏及 800×600 的中文/英文看板执行视觉检查，验证时间轴、导航、状态栏、设置、会话任务上下文和继续追问；保存 UI 截图作为交付证据。
- [x] 2.3 使用 openspec-sync-specs 同步 desktop-summary、behavior-advice 增量，调整相关主规格 Purpose 和必要用户文档中的页面名称及展示描述；检查保留后端建议和任务契约，保留旧客户端兼容场景。
- [x] 2.4 运行 `openspec validate simplify-dashboard-timeline --strict`，检查差异仅包含本 change 范围；全部任务验证通过后按 openspec-archive-change 归档。

规划说明：按 design 产物条件跳过独立设计文档。本变更为局部 UI 展示调整，无新增依赖、数据迁移或架构变化；后端建议与共享任务能力保留，内部 tab 标识无需重命名。
## 验证记录

- 2026-09-14：`node --test self-analyst-app/src/test/js/*.test.mjs`，124 项全部通过。
- `pwsh -NoProfile -File scripts/check-desktop-behavior-advice.ps1` 通过；该既有检查已随展示契约改为验证看板时间轴与已移除区域。
- 使用实际桌面 UI 静态资源和模拟 API 响应在本机 Chrome 中验证：中英文、1440×960、800×600、空数据、初始化、详情展开、继续追问上下文和输入焦点、会话任务上下文、设置开关全部通过，无页面异常。本次未运行真实后端或重新打包桌面安装程序。
- 截图与验证脚本位于忽略的输出目录 `artifacts/dashboard-qa/`；中文宽屏截图为 `dashboard-zh-1440.png`，另含英文和窄屏及空数据截图。
- `openspec validate --specs --strict`：25 份主规格通过；`openspec validate simplify-dashboard-timeline --strict` 和 `git diff --check` 通过。
- 已同步 desktop-summary、behavior-advice 主规格并更新 `README.zh-CN.md`。`docs/archive/legacy-specs/behavior-advice.md` 和 `docs/archive/design-proposals/2026-06-05-desktop-agent-dashboard-design.md` 保留历史追溯；`docs/README.md` 继续提供现行规格与历史资料入口。
