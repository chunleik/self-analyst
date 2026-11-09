package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.agent.AgentIntegrationGateway.TurnContext;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.client.McpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class McpApprovalIntegrationTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void reviewEnableCallFreezeAndRevokeUseRealSdkWithoutNetwork() throws Exception {
        var calls=new AtomicInteger(); var description=new AtomicReference<>("Reviewed description");
        var executed=new AtomicReference<Object>();
        McpManager.ClientFactory factory=(endpoint,timeout,initialization,max,bearer)->McpClient.sync(new PinnedMcpTransport(
                (u,m,h,body,t,limit)->{
                    var request=JSON.readTree(body); String method=request.path("method").asText();
                    if (!request.has("id")) return new PinnedHttpsClient.Response(202,Map.of(),new byte[0]);
                    Object result=switch(method) {
                        case "initialize" -> Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));
                        case "tools/list" -> Map.of("tools",List.of(Map.of("name","search","description",description.get(),"inputSchema",Map.of("type","object","properties",Map.of("payload",Map.of("type","object"))))));
                        case "tools/call" -> { calls.incrementAndGet();executed.set(request.path("params").path("arguments"));yield Map.of("content",List.of(Map.of("type","text","text","synthetic")),"isError",false); }
                        default -> throw new AssertionError(method);
                    };
                    return new PinnedHttpsClient.Response(200,Map.of("content-type",List.of("application/json")),
                            JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",JSON.convertValue(request.get("id"),Object.class),"result",result)));
                },endpoint,timeout,max,bearer)).requestTimeout(timeout).initializationTimeout(initialization).build();
        try (var sessions=new ChatSessionStore(root.resolve("memory")); var approvals=new ApprovalRegistry();
             var manager=new McpManager(new IntegrationStore(root),new SessionCredentialStore(),new MailSessionPolicy(sessions),
                     Config.testDefaults(root.resolve("memory")),approvals,factory);
             var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var session=sessions.create(new ChatSessionStore.CreateRequest());
            var turn=new TurnContext(session.id,"b".repeat(12),true,"https://model.example.com/v1");
            var server=new IntegrationConfig.McpServer("fixture","Fixture",false,"streamable-http","https://example.com/mcp","anonymous",null,"session",IntegrationConfig.Budget.defaults());
            manager.save(0,new IntegrationConfig(1,0,List.of(server),List.of(),List.of()));
            manager.discover("fixture");
            assertTrue(manager.snapshot(turn).isEmpty());
            var definition=manager.toolDefinitions("fixture").getFirst();
            manager.approveTool(1,"fixture",definition.name(),definition.digest());
            var current=manager.config();
            server=new IntegrationConfig.McpServer("fixture","Fixture",true,"streamable-http","https://example.com/mcp","anonymous",null,"session",IntegrationConfig.Budget.defaults());
            manager.save(current.revision(),new IntegrationConfig(1,current.revision(),List.of(server),List.of(),current.toolApprovals()));
            var tool=manager.snapshot(turn).getFirst();
            assertEquals(0,calls.get());
            var nested=new HashMap<String,Object>(); nested.put("value","before");
            var operation=executor.submit(()->tool.callAsync(ToolCallParam.builder().input(Map.of("payload",nested)).build()).block());
            var request=waitFor(approvals); nested.put("value","after");
            assertTrue(request.arguments().toString().contains("before"));
            assertTrue(approvals.decide(request.id(),turn.sessionId(),turn.turnId(),true));
            operation.get(5,TimeUnit.SECONDS); assertEquals(1,calls.get());
            assertTrue(executed.get().toString().contains("before")); assertFalse(executed.get().toString().contains("after"));
            // A server-side schema/description change after review is not an approved execution.
            var changed=executor.submit(()->tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block());
            var changedRequest=waitFor(approvals);description.set("Changed description");
            approvals.decide(changedRequest.id(),turn.sessionId(),turn.turnId(),true);changed.get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());
            // Revocation interrupts a pending receipt and no bytes for tools/call are sent.
            var pending=executor.submit(()->tool.callAsync(ToolCallParam.builder().input(Map.of()).build()).block());
            waitFor(approvals); manager.disconnect(manager.config().revision(),"fixture"); pending.get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertTrue(approvals.pending(null).isEmpty());
            manager.endTurn(turn);
        }
    }
    private static ApprovalRegistry.Request waitFor(ApprovalRegistry approvals) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (approvals.pending(null).isEmpty() && System.nanoTime()<deadline) Thread.sleep(5);
        assertFalse(approvals.pending(null).isEmpty());return approvals.pending(null).getFirst();
    }
}
