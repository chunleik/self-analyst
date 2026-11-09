package com.selfanalyst.agent;

import com.selfanalyst.integrations.policy.MailSessionPolicy;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Explicit local policy, deliberately independent of tools' self-declared readOnly annotations. */
final class MailNativeToolGuard implements AgentTool {
    private static final Set<String> LOCAL_READS = Set.of("queryWiki", "wikiStatus", "findMemory", "getConfig",
            "searchFiles", "listRecentFiles", "getFileMetadata", "fileCollectionStatus");
    private static final Set<String> CONFIRMED_WRITES = Set.of("correctMemory", "setConfigValue");
    private final AgentTool delegate;
    private final Supplier<MailSessionPolicy> policy;
    private final Supplier<AgentIntegrationGateway> gateway;
    MailNativeToolGuard(AgentTool delegate, Supplier<MailSessionPolicy> policy, Supplier<AgentIntegrationGateway> gateway) {
        this.delegate = delegate; this.policy = policy; this.gateway = gateway;
    }
    @Override public String getName() { return delegate.getName(); }
    @Override public String getDescription() { return delegate.getDescription(); }
    @Override public Map<String, Object> getParameters() { return delegate.getParameters(); }
    @Override public Boolean getStrict() { return delegate.getStrict(); }
    @Override public boolean isReadOnly() { return LOCAL_READS.contains(getName()); }
    private static Map<String, Object> snapshot(Map<String, Object> value) {
        @SuppressWarnings("unchecked") Map<String,Object> copy = (Map<String,Object>) freeze(value, 0);
        return copy;
    }
    private static Object freeze(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("NATIVE_TOOL_ARGUMENTS_TOO_DEEP");
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) return value;
        if (value instanceof Double n && Double.isFinite(n)) return value;
        if (value instanceof Float n && Float.isFinite(n)) return value;
        if (value instanceof Map<?,?> map) {
            Map<String,Object> copy = new java.util.LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("NATIVE_TOOL_ARGUMENT_INVALID");
                copy.put(key, freeze(entry.getValue(), depth + 1));
            }
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copy = new java.util.ArrayList<>();
            for (Object item : list) copy.add(freeze(item, depth + 1));
            return java.util.Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("NATIVE_TOOL_ARGUMENT_INVALID");
    }
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.defer(() -> {
            // Freeze before showing approval. ToolCallParam otherwise only copies the outer map.
            Map<String, Object> arguments = snapshot(param.getInput());
            ToolCallParam execution = ToolCallParam.builder(param).input(arguments).build();
            var currentPolicy = policy.get();
            var turn = param.getRuntimeContext() == null ? null
                    : param.getRuntimeContext().get(AgentIntegrationGateway.TurnContext.class);
            boolean legacySearchChange = getName().equals("setConfigValue")
                    && String.valueOf(arguments.get("key")).startsWith("websearch.");
            boolean mail = false;
            if (currentPolicy != null) {
                if (turn == null) return Mono.error(new IllegalStateException("MAIL_TOOL_CONTEXT_REQUIRED"));
                // The server-owned legacy chat has no persisted session or turn id and never
                // receives integration tools. Do not query its sentinel as a generated session.
                // Keep this exception narrow: model arguments cannot create this runtime context.
                boolean legacy = "legacy_default".equals(turn.sessionId()) && !turn.managedDesktop()
                        && turn.turnId() == null
                        && "legacy_default".equals(param.getRuntimeContext().getSessionId());
                if (!legacy) mail = currentPolicy.containsMail(turn.sessionId());
            }
            if (mail && getName().equals("semanticSearchWiki"))
                return Mono.error(new IllegalStateException("MAIL_EMBEDDING_DENIED: use queryWiki"));
            if (mail && !LOCAL_READS.contains(getName()) && !CONFIRMED_WRITES.contains(getName()))
                return Mono.error(new IllegalStateException("MAIL_NATIVE_TOOL_DENIED"));
            if (legacySearchChange || mail && CONFIRMED_WRITES.contains(getName())) {
                var approvals = gateway.get();
                if (approvals == null || turn == null || !turn.managedDesktop())
                    return Mono.error(new IllegalStateException("NATIVE_TOOL_APPROVAL_REQUIRED"));
                approvals.approveNative(turn, getName(), arguments);
            }
            return delegate.callAsync(execution);
        });
    }
}
