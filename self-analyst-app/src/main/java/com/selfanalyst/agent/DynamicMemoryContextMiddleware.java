package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/** Adds the latest local memory summary whenever an agent invocation assembles its system prompt. */
final class DynamicMemoryContextMiddleware implements MiddlewareBase {

    private final Lang lang;
    private final Supplier<String> memorySummary;

    DynamicMemoryContextMiddleware(Lang lang, Supplier<String> memorySummary) {
        this.lang = lang;
        this.memorySummary = memorySummary;
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
        return currentPrompt + "\n\n" + memoryContext;
    }
}
