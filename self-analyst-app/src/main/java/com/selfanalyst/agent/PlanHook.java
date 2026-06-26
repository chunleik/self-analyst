package com.selfanalyst.agent;

import com.selfanalyst.usage.UsageMeter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class PlanHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(PlanHook.class);

    /** 可空：未启用计量时为 null。 */
    private final UsageMeter usageMeter;

    public PlanHook(UsageMeter usageMeter) {
        this.usageMeter = usageMeter;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        switch (event) {
            case PreReasoningEvent e -> log.debug("Agent 正在思考...");
            case PostReasoningEvent e -> {
                String text = e.getReasoningMessage().getTextContent();
                if (text != null && !text.isBlank()) {
                    log.debug("  {}", text.lines().limit(3)
                            .reduce("", (a, b) -> a + (a.isEmpty() ? "" : " ") + b));
                }
                // Agent 路径无法从 hook 直接拿到精确 usage，按文本长度近似计入 AGENT 类别。
                // 主体（后台批量）token 在 completePlain 处为精确计量。
                if (usageMeter != null) {
                    usageMeter.record(UsageMeter.Category.AGENT, 0, estimateTokens(text));
                    if (usageMeter.isBlocked()) {
                        log.warn("已达每日 token 预算，提前中止 Agent 循环");
                        e.stopAgent();
                    }
                }
            }
            case PostActingEvent e -> log.debug("工具执行完成");
            case PostCallEvent e -> log.info("Agent 回复:\n{}", e.getFinalMessage().getTextContent());
            default -> {}
        }
        return Mono.just(event);
    }

    /** 粗略 token 估算（中英文混合按 ~3 字符/token）。仅用于 Agent 路径近似计量。 */
    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + 2) / 3;
    }
}
