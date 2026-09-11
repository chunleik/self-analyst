## Why

用户要求仓库默认展示英文 README，并允许读者跳转中文版，方便不同语言的读者了解项目和使用方式。

## What Changes

- 将现有中文介绍完整保留为根目录 `README.zh-CN.md`。
- 将根目录 `README.md` 翻译为英文，作为默认文档入口。
- 两份 README 顶部提供 English / 简体中文互链，保留有效的命令、配置、模块及文档链接。
- 此次是用户明确要求的英文 README 例外，其他文档仍使用简体中文。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。仅翻译及组织文档，不改变应用行为契约，使用 `skip_specs: true`。

## Impact

影响根目录两份 README；无代码、依赖或配置默认值修改。验证中英文内容覆盖、代码示例及相对链接，无需运行应用测试。
