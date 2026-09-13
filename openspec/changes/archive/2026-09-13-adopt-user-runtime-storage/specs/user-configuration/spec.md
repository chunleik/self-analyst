## MODIFIED Requirements

### Requirement: SPEC-TOML-LOAD-001 便携配置路径
后端 SHALL 把进程工作目录视为运行根，只读写 `./data/config/config.toml`；运行根不再隐含等同于程序分发目录。桌面默认使用当前用户应用数据目录，显式便携模式使用 EXE 所在目录，直接运行 JAR 保留调用者工作目录。配置目录 MUST 不依赖 memory.dir；系统 SHALL 在数据根准入通过后先正常加载 TOML 再解析有效 memory.dir。准入所需的已有配置及有效路径 SHALL 可在持锁后只读解析，但不得保存配置、应用运行设置或初始化业务存储。当前版本 MUST NOT 自动读取或迁移旧 config.properties、memoryDir/config.toml、用户主目录旧 properties 路径或其他运行根的 portable 配置。

#### Scenario: TOML 覆盖 memory.dir
- **WHEN** config.toml 设置新的 memory.dir
- **THEN** 本次启动后续存储使用该目录，而 config.toml 自身路径保持不变

#### Scenario: 默认桌面配置路径
- **WHEN** 用户以默认模式从新的解压位置启动同一应用
- **THEN** 配置仍从同一用户运行根的 `data/config/config.toml` 读取，既有配置优先级不变

#### Scenario: 直接运行 JAR
- **WHEN** 用户在指定工作目录直接启动后端且通过数据根准入
- **THEN** 配置使用该工作目录下的 `data/config/config.toml`，不自动重定向到用户目录
