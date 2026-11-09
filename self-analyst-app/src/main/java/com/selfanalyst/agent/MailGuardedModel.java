package com.selfanalyst.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import java.util.List;

/** Checks the actual leased recipient at subscription, including every reasoning/compaction send. */
final class MailGuardedModel implements Model {
    private final Model delegate;
    private final Runnable preflight;
    MailGuardedModel(Model delegate, Runnable preflight) { this.delegate = delegate; this.preflight = preflight; }
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> { preflight.run(); return delegate.stream(messages, tools, options); });
    }
    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
}
