## 1. 原生重启能力

- [x] 1.1 在 self-analyst-desktop/src-tauri/src/lib.rs 及必要的生命周期模块实现仅受管主页面可调用的重启命令与单次调度，补充 Rust 测试验证调用边界及重复请求只调度一次。
- [x] 1.2 协调后端关闭、受管进程回收、单实例资源释放与新实例启动，保留数据根并清除自启动隐藏意图；通过生命周期顺序测试及 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` 验证。

## 2. 设置页入口

- [x] 2.1 修改 self-analyst-app/src/main/resources/desktop-ui/config.js 及对应 HTML/CSS，在底部按 app.language 待重启状态显示按钮；测试覆盖保存成功、保存失败、重新打开、恢复原值、仅其他配置待重启及有草稿/保存中禁用。
- [x] 2.2 接入原生命令、重启中禁止重复操作、失败恢复与浏览器手动说明；在 self-analyst-app/src/test/js/ 添加行为测试，验证调用次数、失败重试与浏览器不调用原生命令。
- [x] 2.3 补齐中英文消息目录，验证按钮、状态及错误文案；运行 `node --test self-analyst-app/src/test/js/i18n*.test.mjs` 与新增重启测试。

## 3. 集成验收与文档

- [x] 3.1 运行针对性 Node/Rust 测试后执行 `mvn test`；记录命令、结果及环境限制，确认现有配置保存和桌面生命周期测试通过。
- [x] 3.2 在隔离测试数据根的 Windows 桌面发行构建验证中文到英文及英文到中文完整重启、auto 选择、旧进程退出、数据根不变、自启动会话重启后显示窗口及新握手；记录实测证据。
- [x] 3.3 验证桌面和普通浏览器中英界面、键盘操作、800×600 与 390×700 布局，保存不含密钥的测试截图，确认底部操作没有遮挡。
- [x] 3.4 同步 README.md 英文说明与 README.zh-CN.md 中文说明，检查完整差异，确认两版语言和内容一致。
- [x] 3.5 使用 openspec-sync-specs 同步 internationalization 与 desktop-shell 主规格，运行 `openspec validate add-language-restart-button --strict` 及主规格严格校验，任务完成后使用 openspec-archive-change 归档。
