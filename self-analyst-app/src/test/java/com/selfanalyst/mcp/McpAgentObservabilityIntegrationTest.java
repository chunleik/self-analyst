package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.agent.*;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.*;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import com.selfanalyst.observability.*;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.client.McpClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real ReAct/middleware and MCP SDK execution; only model and remote MCP bytes are synthetic. */
class McpAgentObservabilityIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"allowed", "unselected", "image", "revoked"})
    void actualAgentToolCycleHonorsSourceContentAndRevocation(String mode) throws Exception {
        root = root.toRealPath();
        var received = new CopyOnWriteArrayList<byte[]>();
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/api/public/otel/v1/traces", exchange -> {
            received.add(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[]{'{','}'}); exchange.close();
        });
        collector.start();
        var toolCalls = new AtomicInteger();
        McpManager.ClientFactory clients = (endpoint,timeout,initialization,max,bearer) -> McpClient.sync(new PinnedMcpTransport(
                (uri,method,headers,body,requestTimeout,maximum) -> {
                    assertTrue(headers.keySet().stream().noneMatch(key -> Set.of("traceparent","tracestate","baggage").contains(key.toLowerCase(Locale.ROOT))),
                            "Telemetry context must not be propagated to an MCP server");
                    var request = JSON.readTree(body);
                    if (!request.has("id")) return new PinnedHttpsClient.Response(202,Map.of(),new byte[0]);
                    Object result = switch(request.path("method").asText()) {
                        case "initialize" -> Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","synthetic","version","1"));
                        case "tools/list" -> Map.of("tools",List.of(Map.of("name","lookup","description","Synthetic lookup","inputSchema",Map.of("type","object","properties",Map.of("query",Map.of("type","string"))))));
                        case "tools/call" -> {
                            toolCalls.incrementAndGet();
                            yield Map.of("content",mode.equals("image")
                                    ? List.of(Map.of("type","image","mimeType","image/png","data","dGlueS1pbWFnZS1zZW50aW5lbA=="))
                                    : List.of(Map.of("type","text","text","synthetic-private-tool-result")),"isError",false);
                        }
                        default -> throw new AssertionError(request);
                    };
                    return new PinnedHttpsClient.Response(200,Map.of("content-type",List.of("application/json")),
                            JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",JSON.convertValue(request.get("id"),Object.class),"result",result)));
                },endpoint,timeout,max,bearer)).requestTimeout(timeout).initializationTimeout(initialization).build();
        CredentialStore telemetryKeys = new CredentialStore() {
            public String write(CredentialTarget target,char[] secret,String storage) { return "windows:00000000-0000-0000-0000-000000000001"; }
            public Optional<char[]> read(String reference,CredentialTarget target) { return Optional.of("synthetic-only-secret".toCharArray()); }
            public void delete(String reference) {} public void close() {}
        };
        try (var sessions = new ChatSessionStore(root.resolve("sessions"));
             var approvals = new ApprovalRegistry();
             var manager = new McpManager(new IntegrationStore(root.resolve("integrations")),new SessionCredentialStore(),
                     new MailSessionPolicy(sessions),Config.testDefaults(root.resolve("memory")),approvals,clients);
             var service = new ObservabilityService(root.resolve("telemetry"),telemetryKeys,true,new ObservabilityEndpointPolicy(),null);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var session = sessions.create(new ChatSessionStore.CreateRequest());
            var turn = new AgentIntegrationGateway.TurnContext(session.id,"t".repeat(12),true,"https://model.example/v1");
            var server = new IntegrationConfig.McpServer("fixture","Fixture",false,"streamable-http","https://example.com/mcp","anonymous",null,"session",IntegrationConfig.Budget.defaults());
            manager.save(0,new IntegrationConfig(1,0,List.of(server),List.of(),List.of()));
            manager.discover("fixture");
            var definition = manager.toolDefinitions("fixture").getFirst();
            manager.approveTool(1,"fixture",definition.name(),definition.digest());
            var config = manager.config();
            server = new IntegrationConfig.McpServer("fixture","Fixture",true,"streamable-http","https://example.com/mcp","anonymous",null,"session",IntegrationConfig.Budget.defaults());
            manager.save(config.revision(),new IntegrationConfig(1,config.revision(),List.of(server),List.of(),config.toolApprovals()));
            var helper = new AgentObservability(service); manager.setObservability(helper);
            service.save(0,new ObservabilityConfig(1,0,"http://127.0.0.1:"+collector.getAddress().getPort()+"/api/public/otel/v1/traces","pk-test",1,"content",
                    Set.of("modelText","toolText"),mode.equals("unselected") ? Set.of() : Set.of("fixture"),Set.of(),"all-runtime","",""));
            service.credential(1,"synthetic-only-secret".toCharArray());
            service.enable(2,1,(String)service.preview(2).get("confirmationToken"));
            helper.prepare(session.id,false,false,false);
            var tool = manager.snapshot(turn).getFirst();
            var toolkit = new Toolkit(); toolkit.registerAgentTool(tool);
            var generations = new AtomicInteger();
            Model fake = new Model() {
                public String getModelName() { return "synthetic"; }
                public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> schemas,GenerateOptions options) {
                    if (generations.incrementAndGet() == 1) return Flux.just(ChatResponse.builder()
                            .content(List.of(ToolUseBlock.builder().id("call-1").name(tool.getName()).input(Map.of("query","synthetic-private-query")).build()))
                            .usage(new ChatUsage(7,2,0)).build());
                    assertTrue(messages.stream().flatMap(m -> m.getContent().stream()).anyMatch(ToolResultBlock.class::isInstance),"Real agent must feed the executed tool result into the second generation");
                    return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("synthetic-derived-answer").build()))
                            .usage(new ChatUsage(11,3,0)).build());
                }
            };
            try (var agent = ReActAgent.builder().name("integration-test").sysPrompt("Synthetic test only")
                    .model(AgentObservability.model(fake,()->helper,"model.generation"))
                    .toolkit(toolkit).middlewares(List.of(new PlanMiddleware(null,()->helper))).maxIters(3).build()) {
                var context = RuntimeContext.builder().userId("test").sessionId(session.id).put(AgentIntegrationGateway.TurnContext.class,turn).build();
                var run = agent.prepareRun(List.of(Msg.builder().role(MsgRole.USER).textContent("synthetic-user-request").build()),context);
                {
                    var future = executor.submit(() -> helper.trace("selfanalyst.turn",session.id,run::stream).collectList().block(Duration.ofSeconds(10)));
                    long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while (approvals.pending(null).isEmpty() && System.nanoTime()<deadline) Thread.sleep(5);
                    assertFalse(approvals.pending(null).isEmpty());
                    if (mode.equals("revoked")) service.disable(2);
                    var request = approvals.pending(null).getFirst();
                    assertTrue(approvals.decide(request.id(),turn.sessionId(),turn.turnId(),true));
                    var events = future.get(10,TimeUnit.SECONDS);
                    assertTrue(events.stream().anyMatch(AgentResultEvent.class::isInstance));
                }
            }
            assertEquals(2,generations.get()); assertEquals(1,toolCalls.get());
            // Drain through the real HTTP OTLP transport, without a test-only recording exporter.
            var drain = ObservabilityService.class.getDeclaredMethod("drain"); drain.setAccessible(true);
            // Reactive completion can precede upstream doFinally on a different scheduler.
            // Wait for all six actual spans, rather than making the assertion race span finalization.
            long exportDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            List<JsonNode> exported;
            do {
                drain.invoke(service); exported=spans(received);
                @SuppressWarnings("unchecked") var status = (Map<String,Object>)service.snapshot().get("status");
                if(mode.equals("revoked") || exported.size()>=6 && ((Number)status.get("accepted")).longValue()>=6) break;
                Thread.sleep(10);
            } while(System.nanoTime()<exportDeadline);
            final var spans = exported;
            String payload = spans.toString();
            assertFalse(payload.contains("synthetic-only-secret"));
            assertFalse(payload.contains("dGlueS1pbWFnZS1zZW50aW5lbA=="));
            if (mode.equals("allowed")) {
                assertTrue(payload.contains("synthetic-private-tool-result"));
                assertTrue(payload.contains("synthetic-derived-answer"));
            } else {
                assertFalse(payload.contains("synthetic-private-tool-result"));
                assertFalse(payload.contains("synthetic-derived-answer"));
            }
            if (!mode.equals("revoked")) {
                @SuppressWarnings("unchecked") var counters = (Map<String,Object>) service.snapshot().get("status");
                assertTrue(((Number)counters.get("accepted")).longValue()>0, "Collector must acknowledge valid OTLP, not merely receive bytes");
                assertEquals(0L,((Number)counters.get("failed")).longValue());
                assertEquals(6,spans.size(),payload);
                assertEquals(2,spans.stream().filter(s->s.path("name").asText().equals("model.generation")).count());
                assertEquals(2,spans.stream().filter(s->s.path("name").asText().equals("agent.reason")).count());
                assertEquals(1,spans.stream().filter(s->s.path("name").asText().equals("tool.call")).count());
                assertEquals(1,spans.stream().map(s->s.path("traceId").asText()).distinct().count(),"Actual SDK must preserve reactive trace ancestry");
                long totalInput=0,totalOutput=0;
                for(var span:spans) for(var attribute:span.path("attributes"))
                    if(attribute.path("key").asText().equals("langfuse.observation.usage_details")) {
                        assertEquals("model.generation",span.path("name").asText(),"No double-counting generation usage on ancestor spans");
                        var usage=JSON.readTree(attribute.path("value").path("stringValue").asText());
                        totalInput+=usage.path("input").asLong();totalOutput+=usage.path("output").asLong();
                    }
                assertEquals(18,totalInput);assertEquals(5,totalOutput);
                for(var child:spans) if(!child.path("name").asText().equals("selfanalyst.turn"))
                    assertTrue(spans.stream().anyMatch(parent->parent.path("spanId").equals(child.path("parentSpanId"))),child.toString());
            }
            manager.endTurn(turn);
        } finally { collector.stop(0); }
    }
    private static List<JsonNode> spans(List<byte[]> bodies) throws Exception {
        var spans = new ArrayList<JsonNode>();
        for(byte[] body:bodies) for(var resource:JSON.readTree(body).path("resourceSpans"))
            for(var scope:resource.path("scopeSpans")) for(var span:scope.path("spans")) spans.add(span);
        return spans;
    }
}
