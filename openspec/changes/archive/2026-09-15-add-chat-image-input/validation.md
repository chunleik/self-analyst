# 实施与验证记录

## 完成内容

- 图片二进制独立上传，SQLite 图片表保存元数据和有序轮次关联，普通消息 JSON 限额不变。
- PNG/JPEG 实际格式、字节和像素校验；消息绑定的跨会话、重复和已使用 ID 在同一事务拒绝。
- AgentState 保存受管 URL 引用，仅在模型调用边界展开为图片数据；重试、重启和压缩保留所需引用。
- 两种消息面共享图片草稿，支持选择、粘贴、预览、移除、图文和纯图片发送；失败保留草稿并显示提示。
- 删除 intent、未关联上传到期清理、孤立文件清理及两类历史的引用回收已接入；生产服务每小时执行维护。

## 自动化验证

环境：Windows，JDK 21，本机 Maven 镜像。模型测试只连接本地假 OpenAI-compatible 服务，不发送用户数据。

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
mvn -s C:/Users/10478/.m2/settings-aliyun.xml test
mvn -s C:/Users/10478/.m2/settings-aliyun.xml -pl self-analyst-app -am '-Dtest=ChatImageHttpTest,ChatImageModelTest,ChatImageStoreTest,TransactionalAgentStateCompactorTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
node --test self-analyst-app/src/test/js/chat-images.test.mjs
openspec validate add-chat-image-input --strict
openspec validate --specs --strict
git diff --check
```

结果：完整 Maven 回归通过（应用模块 445 项 Java 测试，原有 1 项跳过）；最后补充的缺图回归通过，当前全部 130 项 Node 测试通过；26 项主规格严格校验通过，diff 无空白错误。

关键证据：

- `ChatImageHttpTest` 验证超过 1 MiB 的实际图片上传、原子追加、纯图片请求、服务商明确不支持后重试、同轮次去重、PUT 关联不可改、256 KiB JSON 限制和删除后不可读取。
- `ChatImageModelTest` 验证 PNG/JPEG 实际 HTTP 请求、持久状态无 base64、重启追问及缺图时不降级为纯文本。
- `ChatImageStoreTest` 验证恢复、跨会话/重复引用回滚、大小/格式/像素/数量校验和双历史引用回收。
- `TransactionalAgentStateCompactorTest` 验证摘要接收图片占位、保留尾部原图及既有压缩失败恢复。
- `chat-images.test.mjs` 验证会话隔离、预览释放、部分上传重试、创建会话后失败保留草稿，以及发送期间切换会话不清除其他草稿。

## 浏览器验证

使用 `ChatImageHttpTest.main` 在独立 `target/chat-image-uiqa` 数据目录启动本地测试服务，通过真实生产 UI 验证：

- 增强消息面发送纯图片，图片和 canonical 回复保留。
- 刷新恢复历史图片，图片加载完成且自然宽度非零。
- 系统剪贴板 PNG 粘贴到增强输入框，Enter 发送完成并追加历史。
- 原生 fallback 图文混合输入与 Enter 发送。
- 桌面 1280×900 与窄窗口布局检查；图片输入保持可访问。
- 测试中发现并修复增强组件发送后重建导致消息暂时消失、图片按钮上传失败缺少反馈的问题。

本地截图：`target/chat-image-enhanced.jpg`、`target/chat-image-fallback.jpg`。测试截图、日志和测试会话均为忽略的输出，不作为用户数据提交。

## 规格与文档

主规格 `openspec/specs/chat-image-input/spec.md` 已同步新增的 5 条要求。README.md 和 README.zh-CN.md 已分别补充对应语言的用法与限制，英文新增内容无中文说明。既有 `docs/` 历史和用户文档不需要迁移或删除；本次用户向说明集中在双语 README。

本轮完成本地实现与验证，不代表已打包部署或已通过 GitHub 的 Windows 全量验证；远端交付仍须遵循功能分支、PR 和必需检查流程。
