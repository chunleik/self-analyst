## MODIFIED Requirements

### Requirement: SPEC-DSK-BACKEND-001、001a、001b、001c、001d、001e Java 后端管理
桌面壳 SHALL 优先使用已解析分发根目录中的 Java runtime 执行 `self-analyst-app.jar`，仅在分发未携带
runtime 时回退到系统 Java，并注入 desktop token 和唯一端口文件。桌面壳 SHALL 将 Windows 扩展长度资源路径转换为 Java 可加载的等价路径，保留中文、空格和 UNC 共享语义。带安装布局标记但缺少后端 JAR 的
分发 MUST fail closed，不得回退到可执行文件目录中的其他 JAR。壳 SHALL 最多等待 30 秒取得
`1..65535` 的十进制端口，再最多等待 30 秒以 token 调用
`/desktop/lifecycle/health`。端口文件读取后 SHALL 清理。后端提前退出、端口非法、端口等待超时或
健康检查超时时，桌面启动 MUST fail closed，不使用猜测端口。正常退出 SHALL 请求认证 shutdown，
等待有限时间后终止仍存活的子进程；Windows Job Object SHALL 在壳异常终止时回收受管后端。

#### Scenario: 合法端口与健康检查
- **WHEN** 后端在期限内发布合法端口且认证健康检查成功
- **THEN** 壳记录端口、删除握手文件并创建桌面入口

#### Scenario: 非法或缺失端口
- **WHEN** 端口文件内容为 0、超过 65535、非数字，或在期限内未出现
- **THEN** 壳退出启动流程，不创建使用硬编码端口的窗口

#### Scenario: 后端启动期间退出
- **WHEN** Java 子进程在发布端口或通过健康检查前退出
- **THEN** 壳立即结束启动，并保留后端日志供诊断

#### Scenario: 后端未响应 shutdown
- **WHEN** 正常退出请求后 Java 子进程在等待期内仍未结束
- **THEN** 壳终止并等待该受管子进程退出

#### Scenario: 安装资源不完整
- **WHEN** 安装布局标记存在但安装 resource 目录缺少后端 JAR
- **THEN** 壳在启动任何后端前失败，不使用相邻目录中的未知 JAR

#### Scenario: Windows 安装资源路径兼容
- **WHEN** 安装资源路径带有 Windows 扩展长度前缀，或路径包含中文和空格
- **THEN** 后端从同一资源位置成功加载主类并完成端口与健康握手，不因路径表示形式报主类缺失

