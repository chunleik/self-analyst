## 1. 修正双语说明

- [x] 1.0 翻译 `README.md` 运行数据目录部分的四行中文，依据 `docs/runtime-storage.md` 同步中文版并核对两版信息一致。

- [x] 1.1 翻译 `README.md` 的长期记忆章节并同步 `README.zh-CN.md`；逐段核对信息一致，扫描英文版中文字符，确认仅保留语言入口等必要内容。
- [x] 1.2 运行 `git diff --check` 与 `openspec validate fix-readme-memory-language --strict`，确认文档检查通过。
