package com.selfanalyst.chatgpt;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Quota errors pause automatic work until an explicit successful test or process restart. */
public final class ChatGptInferenceGate {
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    public boolean available(String account) { return !paused.contains(account); }
    public void resume(String account) { paused.remove(account); }
    public void observe(String account, Throwable failure) {
        for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause()) {
            if (failure instanceof ChatGptResponsesModel.ChatGptResponsesException response
                    && response.code() == ChatGptResponsesModel.Code.RATE_LIMIT) {
                paused.add(account);
                return;
            }
        }
    }
    public void requireAvailable(String account) {
        if (!available(account)) throw new IllegalStateException("chatgpt_usage_paused");
    }
    public GuardedModel guard(String account, ChatGptResponsesModel delegate) { return new GuardedModel(account, delegate); }
    public final class GuardedModel implements Model, AutoCloseable {
        private final String account;
        private final ChatGptResponsesModel delegate;
        private GuardedModel(String account, ChatGptResponsesModel delegate) { this.account = account; this.delegate = delegate; }
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                requireAvailable(account);
                return delegate.stream(messages, tools, options).doOnError(error -> observe(account, error));
            });
        }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public void close() { delegate.close(); }
    }
}
