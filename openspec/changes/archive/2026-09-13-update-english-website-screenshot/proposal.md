## Why

英文 README 已使用用户提供的英文界面截图，但英文官网仍展示中文界面并标注中文示例。同步官网的图片与说明，使英文访问者看到对应语言的真实产品界面。

## What Changes

- 新增 website/assets/desktop-chat-en.png，原样复用英文 README 截图。
- 英文官网引用英文截图并将说明改为英文界面示例。
- 更新 docs/website.md 的截图维护说明。
- 仅更新公开展示资源，不改变运行时行为或主规格。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无；设置 skip_specs，仅更新展示内容。

## Impact

涉及英文官网 HTML、PNG 及官网维护文档；中文版继续使用原有截图。随现有 PR 交付，合并后由 Pages 工作流发布。
