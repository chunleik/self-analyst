package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.agent.AgentIntegrationGateway.TurnContext;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Managed-UI decisions only. Every receipt belongs to one immutable operation and one live turn. */
public final class ApprovalRegistry implements AutoCloseable {
    public record Request(String id, String sessionId, String turnId, String kind, String target,
                          String toolName, Object arguments, long expiresAt) { }
    private record Pending(Request request, CompletableFuture<Boolean> decision) { }
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration lifetime;
    private volatile boolean closed;
    public ApprovalRegistry() { this(Clock.systemUTC(), Duration.ofMinutes(2)); }
    public ApprovalRegistry(Clock clock, Duration lifetime) { this.clock = clock; this.lifetime = lifetime; }

    public void require(TurnContext turn, String kind, String target, String tool, Object arguments,
                        BooleanSupplier stillAuthorized) {
        if (closed || !turn.managedDesktop() || !stillAuthorized.getAsBoolean())
            throw new SecurityException("approval_unavailable");
        if (pending.size() >= 32) throw new IllegalStateException("approval_limit");
        // Deep-copy untrusted arguments before UI exposure; never keep a caller's mutable map.
        Object immutable;
        try {
            var json = new ObjectMapper();
            byte[] bytes = json.writeValueAsBytes(arguments);
            if (bytes.length > 32768) throw new IllegalArgumentException("approval_arguments_too_large");
            immutable = json.readTree(bytes);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("invalid_arguments"); }
        String id = UUID.randomUUID().toString();
        var request = new Request(id, turn.sessionId(), turn.turnId(), kind, target, tool, immutable,
                clock.millis() + lifetime.toMillis());
        var entry = new Pending(request, new CompletableFuture<>());
        pending.put(id, entry);
        try {
            boolean approved = entry.decision().get(lifetime.toMillis(), TimeUnit.MILLISECONDS);
            if (!approved || clock.millis() >= request.expiresAt() || closed || !stillAuthorized.getAsBoolean())
                throw new SecurityException("approval_rejected_or_stale");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException("approval_cancelled");
        } catch (ExecutionException | TimeoutException e) {
            throw new SecurityException("approval_expired");
        } finally { pending.remove(id, entry); }
    }
    public List<Request> pending(String sessionId) {
        return pending.values().stream().map(Pending::request)
                .filter(r -> r.expiresAt() > clock.millis() && (sessionId == null || sessionId.equals(r.sessionId())))
                .sorted(Comparator.comparingLong(Request::expiresAt)).toList();
    }
    public boolean decide(String id, String sessionId, String turnId, boolean approve) {
        Pending entry = pending.get(id);
        if (closed || entry == null || !Objects.equals(entry.request().sessionId(), sessionId)
                || !Objects.equals(entry.request().turnId(), turnId) || clock.millis() >= entry.request().expiresAt())
            return false;
        return entry.decision().complete(approve);
    }
    public void cancelTurn(TurnContext turn) {
        pending.values().stream().filter(p -> p.request().sessionId().equals(turn.sessionId())
                && p.request().turnId().equals(turn.turnId())).forEach(p -> p.decision().complete(false));
    }
    public void cancelAll() { pending.values().forEach(p -> p.decision().complete(false)); }
    @Override public void close() { closed = true; cancelAll(); }
}
