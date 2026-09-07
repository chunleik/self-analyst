## MODIFIED Requirements

### Requirement: SPEC-TOML-FMT-004 双语注释模板
空文件模板 SHALL 按功能表组织全部受支持键，以注释形式提供默认值、声明类型及中文和 English 说明。
模板本身 MUST 是合法 TOML 且不产生覆盖。模板 SHALL 指导 Windows 路径使用单引号字面量或正斜杠。
`aw.mode`、`aw.port`、`aw.base-url`、`aw.timeout`、`aw.data-dir` 的双语说明 SHALL 使用事件服务用语，
MUST NOT 使用 ActivityWatch 品牌名；键名保持 `aw.*`。

#### Scenario: 模板可解析
- **WHEN** 后端生成完整配置模板
- **THEN** 解析成功、用户覆盖集为空，所有受支持键均有双语说明

#### Scenario: aw 键说明不含品牌名
- **WHEN** 后端生成完整配置模板
- **THEN** `aw.mode`、`aw.port`、`aw.base-url`、`aw.timeout`、`aw.data-dir` 的中英注释均不包含
  字符串 ActivityWatch
