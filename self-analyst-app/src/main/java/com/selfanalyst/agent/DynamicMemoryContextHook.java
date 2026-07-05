package com.selfanalyst.agent;

import com.selfanalyst.i18n.Lang;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class DynamicMemoryContextHook implements Hook {

    private final Lang lang;
    private final Supplier<String> memorySummary;

    DynamicMemoryContextHook(Lang lang, Supplier<String> memorySummary) {
        this.lang = lang;
        this.memorySummary = memorySummary;
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent e) {
            e.setInputMessages(inject(e.getInputMessages()));
        }
        return Mono.just(event);
    }

    List<Msg> inject(List<Msg> messages) {
        List<Msg> injected = new ArrayList<>(messages);
        int insertAt = !injected.isEmpty() && injected.getFirst().getRole() == MsgRole.SYSTEM ? 1 : 0;
        injected.add(insertAt, Msg.builder()
                .name("memory_context")
                .role(MsgRole.SYSTEM)
                .textContent(AgentPrompts.transientMemoryContext(lang, memorySummary.get()))
                .build());
        return injected;
    }
}
