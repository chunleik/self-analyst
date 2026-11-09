package com.selfanalyst.mcp;

import com.selfanalyst.agent.AgentIntegrationGateway.TurnContext;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalRegistryTest {
    private final TurnContext turn = new TurnContext("a".repeat(32), "b".repeat(12), true, "https://example.com/v1");
    @Test void bindsTurnConsumesOnceAndRechecksRevocation() throws Exception {
        try (var registry = new ApprovalRegistry(Clock.systemUTC(), Duration.ofSeconds(5)); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var allowed = new AtomicBoolean(true);
            Future<?> operation = executor.submit(() -> registry.require(turn,"mcp-call","https://example.com/mcp","search",Map.of("q","synthetic"),allowed::get));
            var request = waitFor(registry);
            assertFalse(registry.decide(request.id(),turn.sessionId(),"wrong",true));
            allowed.set(false);
            assertTrue(registry.decide(request.id(),turn.sessionId(),turn.turnId(),true));
            assertThrows(ExecutionException.class, () -> operation.get(2,TimeUnit.SECONDS));
            assertFalse(registry.decide(request.id(),turn.sessionId(),turn.turnId(),true));
            assertTrue(registry.pending(null).isEmpty());
        }
    }
    @Test void decisionsAreNotPermissionsWithoutManagedDesktop() {
        try (var registry = new ApprovalRegistry()) {
            assertThrows(SecurityException.class, () -> registry.require(new TurnContext("s","t",false,"x"),"native","x","write",Map.of(),()->true));
            assertTrue(registry.pending(null).isEmpty());
        }
    }
    @Test void cancelAndTimeoutNeverApprove() throws Exception {
        try (var registry = new ApprovalRegistry(Clock.systemUTC(),Duration.ofSeconds(2)); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var task=executor.submit(() -> registry.require(turn,"native","local","write",Map.of(),()->true));
            waitFor(registry); registry.cancelTurn(turn);
            assertThrows(ExecutionException.class,()->task.get(1,TimeUnit.SECONDS));
        }
        try (var registry = new ApprovalRegistry(Clock.systemUTC(),Duration.ofMillis(30))) {
            assertThrows(SecurityException.class,()->registry.require(turn,"native","local","write",Map.of(),()->true));
        }
    }
    @Test void immutableArgumentSnapshotAndSessionFiltering() throws Exception {
        try (var registry = new ApprovalRegistry(); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var args=new java.util.HashMap<String,Object>(); args.put("value","before");
            var task=executor.submit(() -> registry.require(turn,"mcp","https://example.com","tool",args,()->true));
            var request=waitFor(registry); args.put("value","after");
            assertTrue(request.arguments().toString().contains("before"));
            assertTrue(registry.pending("other").isEmpty());
            assertTrue(registry.decide(request.id(),turn.sessionId(),turn.turnId(),true)); task.get(1,TimeUnit.SECONDS);
        }
    }
    private static ApprovalRegistry.Request waitFor(ApprovalRegistry registry) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while (registry.pending(null).isEmpty() && System.nanoTime()<deadline) Thread.sleep(5);
        assertFalse(registry.pending(null).isEmpty()); return registry.pending(null).getFirst();
    }
}
