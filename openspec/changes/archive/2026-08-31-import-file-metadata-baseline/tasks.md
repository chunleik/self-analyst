## 1. 基线证据与旧规格对齐

- [x] 1.1 逐项对照 `docs/specs/file.md`、当前文件采集代码和现有测试复核 `file-metadata-collection` delta spec；每条保留的 Requirement 必须有代码或测试证据，测试较弱处必须保留明确限定，并以人工审查清单作为完成证据
- [x] 1.2 核对 `SPEC-FILE-TST-001..020` 在 Scenario 中的迁移或不适用结论，确认没有把实现类名、依赖版本、逐步算法或源码追溯矩阵带入 OpenSpec，并以 ID 搜索结果和 diff 审查作为完成证据
- [x] 1.3 修正 `docs/specs/file.md` 中 `SPEC-FILE-025`、`SPEC-FILE-042`、`SPEC-FILE-063` 的过强或不准确表述，使其分别反映进程内 matcher 缓存、元数据/路径安全检查和仅限 FileTools 的 active-root 保证，并为原未编号配置段分配 `SPEC-FILE-080`；验证稳定 ID 与 OpenSpec 基线语义一致

## 2. 用户与架构文档一致性

- [x] 2.1 将 `README.md` 中“文件派生信息、临时读取正文、哈希/摘要/主题/向量”改为文件系统 metadata-only 描述；使用 `rg -n "正文|哈希|摘要|向量|派生信息" README.md` 人工确认剩余命中均准确
- [x] 2.2 更新 `PRIVACY.md` 的文件数据表和本地存储说明，明确普通文件正文不读取、不保存、不外发，允许元数据保存在 `file-watch.db` 和本地 ActivityWatch heartbeat，且 Agent 工具返回的路径元数据可能进入用户配置的 LLM；通过逐段 diff 审查验证边界完整
- [x] 2.3 更新 `SECURITY.md`，将已不存在的“文件提取”改为文件元数据过滤与路径处理，并把“按配置读取或发送”限定为 metadata-only/no-content-send 边界；使用 `rg -n "文件提取|读取或发送" SECURITY.md` 验证过期措辞已消除
- [x] 2.4 修正 `docs/architecture.md` 中“.gitignore 规则原文不进入缓存”的过强承诺，明确规则可在进程内 matcher 缓存但不得持久化、记录或外发；通过与 `SPEC-FILE-025` 的逐句对照验证一致

## 3. 验证与交付检查

- [x] 3.1 运行 `mvn -pl self-analyst-file -am test -DskipTests=false`，确认文件模块及其依赖测试通过，并在结果中单独标明缺少直接测试覆盖的可靠性条款
- [x] 3.2 运行涉及 Desktop API、FileTools 和设置更新的针对性应用测试，确认响应字段、active-root 过滤和隐私文案与基线一致，并记录实际测试类和结果
- [x] 3.3 运行 `openspec validate import-file-metadata-baseline --strict` 和 `git diff --check`，确认 OpenSpec 结构严格有效且文档没有空白或格式错误
- [x] 3.4 人工审查最终 diff，确认只修改 OpenSpec 与约定的现有文档、未修改业务代码、未删除旧文档，并确认同步主规格前所有文档冲突均已解决或显式列为后续 change

## 完成证据（2026-08-31）

### 基线与 ID 审查

- delta spec 包含 44 条 Requirement 和 53 个 Scenario；旧 43 个 `SPEC-FILE-*` 行为 ID 全部保留，
  唯一新增 `SPEC-FILE-080`，`SPEC-FILE-TST-001..020` 全部出现在 Scenario 标题中。
- 逐项核对了文件采集代码和现有测试；未把私有实现类名、源码路径、依赖版本、逐步算法或源码追溯
  矩阵迁入 delta spec。`FileTools` 作为 Agent 对外工具契约名保留。
- 直接测试较弱、主要依靠结构证据的条款包括：新失败错误码与退避、256 条批处理上限、heartbeat
  节流与字段白名单、Agent/LLM 初始化失败时的采集解耦、并发存储、部分平台路径分支和设置拒绝分支；
  delta spec 已用条件范围或非平台泛化措辞限定这些保证。

### 执行的测试与校验

- `mvn -pl self-analyst-file -am test -DskipTests=false`：74 tests，0 failures，0 errors，2 skipped。
- `mvn -pl self-analyst-app -am '-Dtest=DesktopFileControllerTest,FileToolsTest,AgentPromptsTest'`
  `'-Dsurefire.failIfNoSpecifiedTests=false' '-DskipTests=false' test`：`FileToolsTest` 8、
  `AgentPromptsTest` 8、`DesktopFileControllerTest` 8，合计 24 个 Java tests 全部通过；应用 test phase
  同时执行 75 个桌面 Node tests，全部通过。
- `openspec validate import-file-metadata-baseline --strict`、`openspec validate --all --strict` 和
  `git diff --check` 均通过；未跟踪的 OpenSpec 文件另行检查，无行尾空白。

### 已知覆盖限制与后续 change

- 当前没有真实 HTTP 集成测试请求 `GET /desktop/files` 或 `PUT /desktop/files/settings`；controller
  helper 和 Node 静态/UI 测试不能覆盖路由注册、HTTP method/body、JSON 序列化及全部错误响应。
- Desktop 响应测试没有统一 exact-key whitelist；设置测试未直接覆盖非绝对路径、逗号、普通文件、
  超过 100 个路径条目等全部拒绝分支。UI 测试直接断言“不读取文件正文”，但未逐项断言“不计算哈希”
  和“不向 LLM/embedding 发送正文”的完整文案。
- `self-analyst-app/src/main/java/com/selfanalyst/config/SupportedKeys.java` 的用户帮助仍称
  `file.watch.paths` 可包含“文件或目录”，而实际设置与启动流程只接受现存目录。因本 change 明确禁止
  业务源码修改，应另建 change 修正该帮助文本并补对应测试。
