package com.selfanalyst.agent;

import com.selfanalyst.observability.ObservabilityService;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Explicit reactive trace context. Never attaches telemetry headers or thread-local baggage. */
public final class AgentObservability {
    private static final Object KEY = new Object();
    private record Call(String session, io.opentelemetry.context.Context parent) {}
    private static final class Sources {
        final Set<String> tools = new HashSet<>(), accounts = new HashSet<>();
        boolean mail, image, unknown;
    }
    private final ObservabilityService service;
    private final Map<String, Sources> sessions = new LinkedHashMap<>(128, .75f, true);
    public AgentObservability(ObservabilityService service) { this.service = Objects.requireNonNull(service); }
    /** Reused source IDs must never relabel already-observed history as a newly approved identity. */
    public synchronized void sourceConfigurationChanged() {
        for (Sources source : sessions.values()) source.unknown = true;
        service.sourceConfigurationChanged();
    }
    private Sources source(String session) {
        if (session == null) { var value = new Sources(); value.unknown = true; return value; }
        var value = sessions.computeIfAbsent(session, ignored -> new Sources());
        while (sessions.size() > 256) sessions.remove(sessions.keySet().iterator().next());
        return value;
    }
    public synchronized void prepare(String session, boolean restoredHistory, boolean mail, boolean image) {
        boolean known = sessions.containsKey(session);
        var value = source(session);
        value.unknown |= !known && (restoredHistory || mail);
        value.mail |= mail; value.image |= image;
    }
    public synchronized void mail(String session, String account) {
        var value = source(session); value.mail = true;
        if (account == null || account.isBlank()) value.unknown = true; else if (value.accounts.size() < 32) value.accounts.add(account); else value.unknown = true;
    }
    public synchronized void tool(String session, String source) {
        var value = source(session);
        if (source == null) value.unknown = true; else if (value.tools.size() < 128) value.tools.add(source); else value.unknown = true;
    }
    private synchronized ObservabilityService.Content content(String session, String input, String output, String category) {
        var s = source(session);
        return new ObservabilityService.Content(input, output, category, Set.copyOf(s.tools), Set.copyOf(s.accounts), s.mail, s.image, s.unknown);
    }
    public <T> Flux<T> trace(String operation, String session, Supplier<Flux<T>> action) {
        return Flux.deferContextual(context -> {
            Call inherited = context.getOrDefault(KEY, null);
            var span = service.begin(operation, session, inherited == null ? null : inherited.parent());
            AtomicReference<String> outcome = new AtomicReference<>("success");
            return Flux.defer(action).doOnError(error -> outcome.set(outcome(error)))
                    .doFinally(signal -> span.finish(signal == reactor.core.publisher.SignalType.CANCEL ? "cancelled" : outcome.get(), 0, 0, "unavailable", null))
                    .contextWrite(c -> c.put(KEY, new Call(session, span.context())));
        });
    }
    /** Suppliers serialize only already-authorized call data, and only after the content gate. */
    public Mono<String> toolCall(String session, String source, Supplier<String> input, Supplier<String> action) {
        return toolCall(session, source, "toolText", input, action);
    }
    public Mono<String> toolCall(String session, String source, String category, Supplier<String> input, Supplier<String> action) {
        return Mono.deferContextual(context -> {
            if (source != null) tool(session, source);
            Call inherited = context.getOrDefault(KEY, null);
            var span = service.begin("tool.call", session, inherited == null ? null : inherited.parent());
            AtomicReference<String> outcome = new AtomicReference<>("success");
            AtomicReference<ObservabilityService.Content> captured = new AtomicReference<>();
            return Mono.fromCallable(action::get).doOnNext(result -> {
                if (binary(result)) prepare(session, false, false, true);
                var provenance = content(session, null, null, category);
                try { if (span.allowContent(provenance)) captured.set(content(session, input.get(), result, category)); }
                catch (RuntimeException ignored) { /* Telemetry must not change a successful tool call. */ }
            }).doOnError(error -> outcome.set(outcome(error)))
              .doFinally(signal -> span.finish(signal == reactor.core.publisher.SignalType.CANCEL ? "cancelled" : outcome.get(), 0, 0, "unavailable", captured.get()))
              .contextWrite(c -> c.put(KEY, new Call(session, span.context())));
        });
    }
    public ObservabilityService.SpanHandle begin(String operation) { return service.begin(operation, null, null); }
    public void catalog(ObservabilityService.SpanHandle span, String source, Supplier<String> catalog) {
        var provenance = new ObservabilityService.Content(null, null, "toolCatalog", Set.of(source), Set.of(), false, false, false);
        ObservabilityService.Content captured = null;
        try { if (span.allowContent(provenance)) captured = new ObservabilityService.Content(null, catalog.get(), "toolCatalog", Set.of(source), Set.of(), false, false, false); }
        catch (RuntimeException ignored) { /* Discovery succeeds even when optional content cannot be encoded. */ }
        span.finish("success", 0, 0, "unavailable", captured);
    }
    public static Model model(Model delegate, Supplier<AgentObservability> owner, String operation) {
        return new Model() {
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.deferContextual(context -> {
                    var o = owner.get(); if (o == null) return delegate.stream(messages, tools, options);
                    Call call = context.getOrDefault(KEY, null);
                    String session = call == null ? null : call.session();
                    // Any image taints the entire derived turn, including subsequent compaction.
                    if (messages != null) for (Msg msg : messages) if (msg.getContent() != null)
                        for (var block : msg.getContent()) if (block instanceof ImageBlock || block instanceof TextBlock t && binary(t.getText())) o.prepare(session, false, false, true);
                    var span = o.service.begin(operation, session, call == null ? null : call.parent());
                    boolean capture = span.allowContent(o.content(session, null, null, "modelText"));
                    AtomicReference<String> input = new AtomicReference<>();
                    StringBuilder output = capture ? new StringBuilder() : null;
                    AtomicReference<ChatUsage> usage = new AtomicReference<>();
                    AtomicReference<String> outcome = new AtomicReference<>("success");
                    return Flux.defer(() -> delegate.stream(messages, tools, options))
                            .doOnNext(response -> {
                                if (capture && input.get() == null) input.set(text(messages));
                                if (response.getUsage() != null) usage.set(response.getUsage());
                                if (response.getContent() != null) for (var block : response.getContent()) {
                                    if (block instanceof TextBlock t && t.getText() != null && !t.getText().isEmpty()) {
                                        span.firstToken(); if (binary(t.getText())) o.prepare(session, false, false, true);
                                        if (output != null) append(output, t.getText());
                                    }
                                }
                            }).doOnError(error -> outcome.set(outcome(error)))
                            .doFinally(signal -> {
                                var u = usage.get();
                                span.finish(signal == reactor.core.publisher.SignalType.CANCEL ? "cancelled" : outcome.get(),
                                    u == null ? 0 : u.getInputTokens(), u == null ? 0 : u.getOutputTokens(), u == null ? "unavailable" : "actual",
                                    capture && signal == reactor.core.publisher.SignalType.ON_COMPLETE && input.get() != null
                                        ? o.content(session, input.get(), output.toString(), "modelText") : null);
                            }).contextWrite(c -> c.put(KEY, new Call(session, span.context())));
                });
            }
            public String getModelName() { return delegate.getModelName(); }
            public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
            public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
            public int getContextWindowSize() { return delegate.getContextWindowSize(); }
        };
    }
    public static AgentTool nativeTool(AgentTool delegate, Supplier<AgentObservability> owner) {
        return new AgentTool() {
            public String getName() { return delegate.getName(); }
            public String getDescription() { return delegate.getDescription(); }
            public Map<String,Object> getParameters() { return delegate.getParameters(); }
            public Boolean getStrict() { return delegate.getStrict(); }
            public boolean isReadOnly() { return delegate.isReadOnly(); }
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                var o = owner.get(); if (o == null) return delegate.callAsync(param);
                String session = param.getRuntimeContext() == null ? null : param.getRuntimeContext().getSessionId();
                return Mono.defer(() -> { o.tool(session, null); return o.trace("tool.call", session, () -> delegate.callAsync(param).flux()).single(); });
            }
        };
    }
    private static boolean binary(String value) {
        return value != null && (value.contains("data:") || value.matches("(?s).*[A-Za-z0-9+/]{200,}={0,2}.*"));
    }
    private static String outcome(Throwable error) {
        return error instanceof java.util.concurrent.TimeoutException ? "timeout"
                : error instanceof SecurityException ? "rejected" : "error";
    }
    private static String text(List<Msg> messages) {
        StringBuilder out = new StringBuilder();
        if (messages != null) for (Msg m : messages) if (m.getContent() != null) for (var b : m.getContent()) {
            if (b instanceof TextBlock t && t.getText() != null) append(out, t.getText());
        }
        return out.toString();
    }
    private static void append(StringBuilder out, String value) {
        int remaining = 16384 - out.length(); if (remaining > 0) out.append(value, 0, Math.min(remaining, value.length()));
    }
}
