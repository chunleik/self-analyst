## 1. 数据根守卫

- [x] 1.1 在 `self-analyst-app/src/main/java/com/selfanalyst/` 下新增运行存储守卫，规范化真实数据根，以唯一 `FileChannel` 管理 `app.lock`；在对应 JUnit 测试覆盖重叠锁、锁文件残留、路径别名、锁不可用及关闭幂等。
- [x] 1.2 实现 `storage-format.json` 格式 1 的严格读取和首次原子发布，在 `RuntimeStorageGuardTest` 覆盖空根、兼容无标记数据原地接纳、合法空配置和已识别空子目录、未知/混合不兼容数据拒绝、损坏/过新/过旧/不可读标记、临时文件恢复及发布失败无业务写入。
- [x] 1.3 新增 `RuntimeStorageProcessTest` 和子 JVM 测试入口，用临时目录验证两个真实进程互斥、强制终止后重新取得锁、不同根不互斥；测试不得依赖真实用户数据或仅用线程模拟跨进程锁。

- [x] 1.4 盘点当前配置、数据库、事件文件及索引的实际布局和结构/版本元数据，编写逐类只读兼容检查器及脱敏夹具；验证各现有存储全部通过才补标记，配置指定路径只读解析，缺失可选存储允许，未知/损坏/需写入恢复的存储拒绝；用测试前后文件清单和字节比较证明检查及补标记不改变业务文件。

## 2. 后端生命周期集成

- [x] 2.1 在 `App.java`、`AppSession.java` 的生产会话创建边界接入守卫，确保正常配置应用、存储和任何迁移/清理逻辑均在准入后运行，准入阶段只允许兼容检查所需的只读配置与路径解析；新增入口回归测试验证拒绝时无端口文件、无采集、无业务数据改写，直接 JAR 也执行相同准入。
- [x] 2.2 调整 `AppSession` 部分构造失败和正常关闭的资源回收顺序，最后释放数据锁；通过故障注入和进程测试验证关闭未完成时其他实例仍不能取得锁，清理失败不能无锁驻留。
- [x] 2.3 在后端定义五类稳定启动失败退出分类并输出脱敏原因；测试目录不可用、锁占用、锁不可用、格式不支持、格式无效，确认不把所有异常归为同一超时。
- [x] 2.4 调整相关 `src/test/java` 会话夹具，在临时根初始化合法格式后再预置测试配置，保留独立配置读写单元测试；运行 `mvn -pl self-analyst-app -am '-Dtest=RuntimeStorageGuardTest,RuntimeStorageProcessTest,ConfigTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 及新增生命周期测试并记录通过结果。

## 3. 桌面目录和反馈

- [x] 3.1 修改 `self-analyst-desktop/src-tauri/src/lib.rs`，分离资源选择和运行根选择，默认用户目录、非安装分发 `portable.marker` 显式启用随包目录；Rust 测试覆盖安装优先、缺失/有效/不可检查标记、不可写目录、换解压位置路径稳定、默认模式不读取程序旁旧 `data/` 及显式便携选中旧目录。
- [x] 3.2 桌面启动轮询接入退出分类及本地化提示，协调 `startup_log.rs`、后端日志与端口临时文件位置；测试准入前不写 `data/`、失败不连接其他后端，并回归动态端口和桌面单实例唤起语义。
- [x] 3.3 在桌面设置 API/UI 与受信任原生命令中增加只读运行模式/路径及打开数据目录入口；相应 Java、Node 和 Rust 测试验证实际路径一致、任意路径不被接受、非原生和打开失败反馈准确，提供 UI 截图证据。

## 4. 分发和集成验证

- [x] 4.1 更新 `scripts/build-portable.ps1` 及相关 dist/installer/冒烟脚本，移除预置可变数据和空配置，标准包不带便携标记；检查构建产物清单，并在临时目录验证解压默认首次启动和加标记后的全新便携启动。
- [x] 4.2 使用临时隔离用户目录完成安装版、免安装版和直接 JAR 冒烟，验证同格式重启复用、不迁移旧 portable 数据、安装版与显式便携版兼容无标记数据原地接纳、未知或不兼容数据拒绝且保留；回归安装、升级、卸载不删除用户数据。
- [ ] 4.3 运行 `cargo test --manifest-path self-analyst-desktop/src-tauri/Cargo.toml` 和 `mvn test`；为纯 Java 守卫进程测试在 Windows、Linux、macOS CI 配置运行矩阵，仅运行不依赖 Windows UIA 的守卫测试并记录三平台结果，不扩展桌面分发支持范围。

## 5. 文档和规格交付

- [x] 5.1 使用简体中文更新 `README.md`、`docs/architecture.md` 及相关用户/测试指南，说明默认目录、标记用法、不迁移数据位置、无标记旧数据经只读兼容检查后原地补标记、无法确认兼容时拒绝、旧版本不受新锁约束、根外共享限制及手动保留数据的回退方式；核对路径示例和实际 UI，不改写历史发布记录。
- [x] 5.2 实施验证通过后使用 openspec-sync-specs 同步 `runtime-storage`、`desktop-shell`、`user-configuration`；运行 `openspec validate adopt-user-runtime-storage --strict` 和受影响主规格严格校验，确认现行行为与增量一致。
- [ ] 5.3 汇总测试及截图证据，核对任务全部完成后使用 openspec-archive-change 归档；提交或 PR 引用此 change 和验证结果，若进入合并阶段按仓库规则等待最新提交的必需检查含 `Windows 全量验证` 成功，不用本地结果代替。

## 验证记录

- 本地 `mvn test` 全量通过；此后新增正式入口和 API 场景的针对性 Maven 测试通过，Node 116 项通过。
- 桌面 Rust 22 项测试及 Clippy `-D warnings` 通过。
- 最新可执行 JAR 在隔离目录真实启动、认证接口和退出冒烟通过。
- 临时分发包不含数据目录、用户配置或便携标记；保留既有 `dist-portable/data`。
- 桌面冒烟通过中文空格便携路径、移动目录、静默启动、单实例唤起和后端故障提示；记录位于 `.tmp/autostart-7cb2c2fce79f437cb3e9bcf30940d0d4/result.txt`。
- NSIS 安装、重装、资源、内置 JRE 后端冒烟和卸载通过，全部安装文件限定在工作区临时目录。
- 浏览器界面实际复核通过，截图为 `docs/screenshots/runtime-storage.png`；原生打开命令的来源限制复用已有原生命令来源校验，JS 测试覆盖无路径参数和失败反馈。
- 25 项主规格严格校验及本 change 严格校验通过；远端跨平台结果待 PR 检查。
