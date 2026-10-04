## 1. 样式

- [x] 1.1 重写 `self-analyst-app/src/main/resources/desktop-ui/ontology.css`，仅使用 `styles.css` 中已定义的设计变量；以新增的 `ontology-style.test.mjs` 断言不存在未定义变量与硬编码十六进制颜色
- [x] 1.2 在 `ontology.js` 中补充样式钩子类名（状态提示、复选框字段、分页、危险按钮、证据条目），并在点击实体或关系链接后同步列表选中态与 `aria-current`；以 `ontology.test.mjs` 新增断言验证
- [x] 1.3 弹窗在视口居中、宽屏列表吸附独立滚动、窄屏单栏堆叠；以 `ontology-style.test.mjs` 断言对应规则，并用本地回环预览在 1280px 与 600px 宽度下截图目检

## 2. 验证

- [x] 2.1 在 `self-analyst-app` 下运行 `node --test`，全部通过
- [x] 2.2 运行 `openspec validate polish-knowledge-styles --strict`，通过
