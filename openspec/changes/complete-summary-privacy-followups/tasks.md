## 1. 浏览器探测

- [x] 1.1 实现 `BrowserChromeProbe`（主机名与无痕标记），浏览器仍不产生 context_title。验证：新增单元测试用合成 UiaNode。
- [x] 1.2 ContentWatcher 对浏览器执行探测并写入 `url_host` / `private_browsing`。验证：heartbeat 组装测试。
- [x] 1.3 内容事件校验允许并约束新字段。验证：相关 persistence 测试通过。

## 2. Wiki 隐私与旧摘要

- [x] 2.1 WikiPrivacyPolicy / TitleSampler 按 `url_host` 与 `private_browsing` 排除。验证：采样测试。
- [x] 2.2 已保存 SUMMARIZED 条目本地脱敏并打标。验证：WikiStore/Worker 测试。

## 3. 评测与文档

- [x] 3.1 增加上层子摘要夹具并扩展评测入口。验证：离线评测与可选 live 评测。
- [x] 3.2 更新 README 双语文档与主规格同步。验证：`openspec validate --strict`，英文 README 无中文混入。
- [x] 3.3 运行 `mvn test`。验证：BUILD SUCCESS。
