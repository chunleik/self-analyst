## Context

AgentScope 2.0.4 将 InterruptControl 从持久会话状态移动到运行专属 RunControl。ReActAgent.prepareRun 返回懒执行的单次 AgentRun；该句柄在尚未订阅时也能接受中断，而旧 session interrupt 只查找已经登记的运行。

## Goals / Non-Goals

保留匹配 session/user turn 的取消、完整回滚与正常重试。保留已有应用级聊天 gate、模型版本租约、修复聊天线程中断路径，不引入新的并发模型。

## Decisions

- 在模型流订阅之前创建 AgentRun，并将它的 interrupt 绑定到 ActiveDesktopChat 的专属取消回调。取消先于 beginModelCall 时仍由既有生命周期拒绝执行；发生在登记与订阅之间时由运行句柄阻止启动。
- 已运行的取消继续采用协作式中断，不改成强制取消。模型结果仍由当前轮生命周期过滤，现行资源清理和持久回滚流程不变。
- 回滚只清理持久对话、shutdownInterrupted、curIter 和 replyId。中断标志随运行销毁，下一轮使用新 RunControl，无需也不应复位会话级控制。

## Risks / Trade-offs

协作式中断可能等待当前模型流退出，保持既有行为。使用可控 HTTP 模型测试验证取消、状态清理及下一次调用；额外覆盖尚未订阅的运行句柄取消。

## Migration Plan

在最新 main 上完整覆盖原依赖补丁；先定向测试，再运行 Maven 全套测试和 OpenSpec 严格校验。创建替代 PR，等待最新提交 Windows 全量验证后合并，再关闭已被覆盖的 #126。回滚需整体回滚依赖及适配代码。
