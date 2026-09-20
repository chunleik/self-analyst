package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.function.Supplier;

/** Adds the latest local memory summary whenever an agent invocation assembles its system prompt. */
final class DynamicMemoryContextMiddleware implements MiddlewareBase {

    private final Lang lang;
    private final Supplier<String> memorySummary;
    private final Supplier<ZonedDateTime> now;

    DynamicMemoryContextMiddleware(Lang lang, Supplier<String> memorySummary) {
        this(lang, memorySummary, ZonedDateTime::now);
    }

    DynamicMemoryContextMiddleware(Lang lang, Supplier<String> memorySummary,
                                   Supplier<ZonedDateTime> now) {
        this.lang = lang;
        this.memorySummary = memorySummary;
        this.now = now;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromSupplier(() -> enrich(currentPrompt));
    }

    String enrich(String currentPrompt) {
        String memoryContext = AgentPrompts.transientMemoryContext(lang, memorySummary.get());
        if (currentPrompt == null || currentPrompt.isBlank()) {
            return memoryContext;
        }
        String prompt = currentPrompt.replace(AgentPrompts.CURRENT_TIME_PLACEHOLDER,
                AgentPrompts.formatCurrentTime(lang, now.get()));
        return prompt + "\n\n" + memoryContext;
    }
}
