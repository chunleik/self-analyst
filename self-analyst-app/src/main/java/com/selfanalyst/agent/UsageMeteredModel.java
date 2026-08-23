package com.selfanalyst.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.usage.UsageMeter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Records direct model calls made outside ReAct's middleware chain, such as compaction. */
final class UsageMeteredModel implements Model {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Model delegate;
    private final UsageMeter usageMeter;
    private final UsageMeter.Category category;

    UsageMeteredModel(Model delegate, UsageMeter usageMeter, UsageMeter.Category category) {
        this.delegate = delegate;
        this.usageMeter = usageMeter;
        this.category = category;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (usageMeter == null) return delegate.stream(messages, tools, options);
        usageMeter.enforce(category);

        AtomicReference<ChatUsage> usage = new AtomicReference<>();
        StringBuilder generated = new StringBuilder();
        AtomicBoolean recorded = new AtomicBoolean();
        Runnable recordOnce = () -> {
            if (!recorded.compareAndSet(false, true)) return;
            ChatUsage actual = usage.get();
            if (actual != null) {
                usageMeter.record(category, actual.getInputTokens(), actual.getOutputTokens());
                return;
            }
            usageMeter.record(category, estimateInput(messages, tools),
                    PlanMiddleware.estimateTokens(generated.toString()));
        };

        Flux<ChatResponse> responses;
        try {
            responses = delegate.stream(messages, tools, options);
        } catch (Throwable failure) {
            recordOnce.run();
            return Flux.error(failure);
        }
        return responses
                .doOnNext(response -> collect(response, generated, usage))
                .doOnComplete(recordOnce)
                .doOnError(ignored -> recordOnce.run())
                .doOnCancel(recordOnce);
    }

    private static void collect(
            ChatResponse response,
            StringBuilder generated,
            AtomicReference<ChatUsage> usage) {
        if (response == null) return;
        if (response.getUsage() != null) usage.set(response.getUsage());
        if (response.getContent() == null) return;
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                generated.append(text.getText());
            }
        }
    }

    private static long estimateInput(List<Msg> messages, List<ToolSchema> tools) {
        try {
            return PlanMiddleware.estimateTokens(
                    MAPPER.writeValueAsString(new Object[]{messages, tools}));
        } catch (Exception ignored) {
            StringBuilder fallback = new StringBuilder();
            if (messages != null) {
                messages.forEach(message -> fallback.append(message.getTextContent()));
            }
            return PlanMiddleware.estimateTokens(fallback.toString());
        }
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
