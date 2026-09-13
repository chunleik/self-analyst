## 1. 更新展示内容

- [x] 1.1 复制英文截图到 website/assets/desktop-chat-en.png，核对 SHA256 与 README 英文截图相同。
- [x] 1.2 更新 website/en/index.html 图片引用与英文界面说明，核对中文页面引用保持不变，并在 docs/website.md 说明资源来源。

## 2. 验证与交付

- [x] 2.1 运行 node --test scripts/website.test.mjs、git diff --check 和 openspec validate update-english-website-screenshot --strict 并确认通过。
