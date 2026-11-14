package com.selfanalyst.observability;

import com.selfanalyst.agent.AgentObservability;
import com.selfanalyst.integrations.*;
import com.fasterxml.jackson.databind.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class AgentObservabilityTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeEach void canonicalTemporaryRoot() throws Exception {
        directory = directory.toRealPath();
    }
    static class Transport extends ObservabilityTransport {
        final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        Transport() { super(new ObservabilityEndpointPolicy()); }
        @Override public int send(ObservabilityEndpointPolicy.ApprovedEndpoint endpoint, String auth, byte[] body, java.util.function.BooleanSupplier active) { if(active.getAsBoolean()) bodies.add(body.clone()); return 200; }
        List<JsonNode> spans() throws Exception {
            var result = new ArrayList<JsonNode>();
            for (byte[] body : bodies) for (var resource : new ObjectMapper().readTree(body).path("resourceSpans"))
                for (var scope : resource.path("scopeSpans")) for (var span : scope.path("spans")) result.add(span);
            return result;
        }
    }
    ObservabilityService enabled(Transport transport, boolean content) throws Exception {
        CredentialStore credentials = new CredentialStore() {
            public String write(CredentialTarget t,char[] secret,String mode) { return "windows:00000000-0000-0000-0000-000000000001"; }
            public Optional<char[]> read(String ref,CredentialTarget t) { return Optional.of("synthetic-secret".toCharArray()); }
            public void delete(String r) {} public void close() {}
        };
        var service = new ObservabilityService(directory, credentials, true, new ObservabilityEndpointPolicy(), transport);
        service.save(0, new ObservabilityConfig(1,0,"http://127.0.0.1:3000/api/public/otel/v1/traces","pk-test",1,
                content ? "content" : "metadata", Set.of("modelText","toolText","toolCatalog","mailHeaders","mailBody"), Set.of("connection"),Set.of("mail-one"),"all-runtime","",""));
        service.credential(1,"synthetic-secret".toCharArray()); service.enable(2,1,(String)service.preview(2).get("confirmationToken")); return service;
    }
    static Model model() { return new Model() {
        public String getModelName() { return "test"; }
        public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("private-output").build())).usage(new ChatUsage(5,3,.0)).build());
        }
    }; }
    static List<Msg> input() { return List.of(Msg.builder().role(MsgRole.USER).textContent("private-input").build()); }
    @Test void generationOncePerSubscriptionWithParentAndMetadataOnly() throws Exception {
        var transport = new Transport();
        try (var service = enabled(transport,false)) {
            var helper = new AgentObservability(service); helper.prepare("session",false,false,false);
            var model = AgentObservability.model(model(), () -> helper,"model.generation");
            var stream = helper.trace("selfanalyst.turn","session",() -> model.stream(input(),List.of(),null));
            stream.blockLast(); stream.blockLast(); service.drain();
            var spans = transport.spans(); assertEquals(4,spans.size());
            assertEquals(2,spans.stream().filter(s->s.path("name").asText().equals("model.generation")).count());
            assertFalse(spans.toString().contains("private-input")); assertFalse(spans.toString().contains("private-output"));
            for(var span:spans) {
                var attributes=new HashMap<String,JsonNode>();
                for(var attribute:span.path("attributes")) attributes.put(attribute.path("key").asText(),attribute.path("value"));
                if(span.path("name").asText().equals("model.generation")) {
                    assertEquals("actual",attributes.get("selfanalyst.usage.source").path("stringValue").asText());
                    assertEquals(5,attributes.get("selfanalyst.usage.input").path("intValue").asInt());
                    assertEquals(3,attributes.get("selfanalyst.usage.output").path("intValue").asInt());
                    var usage=new ObjectMapper().readTree(attributes.get("langfuse.observation.usage_details").path("stringValue").asText());
                    assertEquals(5,usage.path("input").asInt()); assertEquals(3,usage.path("output").asInt());
                } else assertFalse(attributes.containsKey("langfuse.observation.usage_details"));
            }

            for(var child : spans) if(child.path("name").asText().equals("model.generation"))
                assertTrue(spans.stream().anyMatch(p->p.path("spanId").equals(child.path("parentSpanId")) && p.path("traceId").equals(child.path("traceId"))));
        }
    }
    @Test void restoredNativeAndImageDerivedTurnsOmitWholeContent() throws Exception {
        var transport = new Transport();
        try(var service=enabled(transport,true)) {
            var helper=new AgentObservability(service);
            helper.prepare("restored",true,true,false);
            helper.prepare("image",false,false,true);
            helper.prepare("native",false,false,false); helper.tool("native",null);
            helper.prepare("clean",false,false,false);
            var model=AgentObservability.model(model(),()->helper,"model.generation");
            for(String session:List.of("restored","image","native","clean")) helper.trace("selfanalyst.turn",session,()->model.stream(input(),List.of(),null)).blockLast();
            service.drain();
            assertEquals(1,transport.spans().stream().filter(s->s.toString().contains("private-input")).count());
        }
    }
    @Test void toolResultMarksMailBeforeContentAndTaintsNextGeneration() throws Exception {
        var transport=new Transport();
        try(var service=enabled(transport,true)) {
            var helper=new AgentObservability(service);helper.prepare("session",false,false,false);
            helper.trace("selfanalyst.turn","session",()->helper.toolCall("session","connection",()->"args",()->{helper.mail("session","unselected-account");return "private-tool";}).flux()
                .thenMany(AgentObservability.model(model(),()->helper,"model.generation").stream(input(),List.of(),null))).blockLast();
            service.drain(); assertFalse(transport.spans().toString().contains("private-"));
        }
    }
    @Test void rejectedActualMailGuardExportsNoInput() throws Exception {
        var transport=new Transport();
        try(var service=enabled(transport,true)) {
            var helper=new AgentObservability(service);helper.prepare("session",false,false,false);
            var constructor=Class.forName("com.selfanalyst.agent.MailGuardedModel").getDeclaredConstructor(Model.class,Runnable.class);
            constructor.setAccessible(true);
            Model denied=(Model)constructor.newInstance(model(),(Runnable)()->{throw new SecurityException("recipient-not-authorized");});
            assertThrows(SecurityException.class,()->helper.trace("selfanalyst.turn","session",()->AgentObservability.model(denied,()->helper,"model.generation").stream(input(),List.of(),null)).blockLast());
            service.drain();assertEquals(2,transport.spans().size());assertFalse(transport.spans().toString().contains("private-input"));
        }
    }
    @Test void sourceChangeRevokesGrantAndDoesNotRelabelExistingSessionHistory() throws Exception {
        var transport=new Transport();
        try(var service=enabled(transport,true)) {
            var helper=new AgentObservability(service);helper.prepare("old",false,false,false);helper.tool("old","connection");
            String pending=(String)service.preview(2).get("confirmationToken");
            helper.sourceConfigurationChanged();
            assertEquals(false,service.snapshot().get("enabled"));
            assertThrows(java.io.IOException.class,()->service.enable(2,1,pending));
            service.enable(2,1,(String)service.preview(2).get("confirmationToken"));
            helper.prepare("new",false,false,false);
            var model=AgentObservability.model(model(),()->helper,"model.generation");
            for(String session:List.of("old","new")) helper.trace("selfanalyst.turn",session,()->model.stream(input(),List.of(),null)).blockLast();
            service.drain();
            assertEquals(1,transport.spans().stream().filter(span->span.toString().contains("private-input")).count());
        }
    }
    @Test void managerSourceMutationsRevokeButUnchangedDefinitionSaveDoesNot() throws Exception {
        var transport=new Transport();
        try(var service=enabled(transport,true);
            var sessions=new com.selfanalyst.desktop.store.ChatSessionStore(directory.resolve("chat"));
            var approvals=new com.selfanalyst.mcp.ApprovalRegistry();
            var manager=new com.selfanalyst.mcp.McpManager(new IntegrationStore(directory.resolve("integrations")),
                    new SessionCredentialStore(),new com.selfanalyst.integrations.policy.MailSessionPolicy(sessions),
                    com.selfanalyst.config.Config.testDefaults(directory.resolve("memory")),new PinnedHttpsClient(),approvals)) {
            var helper=new AgentObservability(service);manager.setObservability(helper);
            var server=new IntegrationConfig.McpServer("connection","Test",false,"streamable-http","https://example.com/mcp","bearer",null,"session",IntegrationConfig.Budget.defaults());
            manager.save(0,new IntegrationConfig(1,0,List.of(server),List.of(),List.of()));
            assertEquals(false,service.snapshot().get("enabled"));
            service.enable(2,1,(String)service.preview(2).get("confirmationToken"));
            var current=manager.config();manager.save(current.revision(),current);
            assertEquals(true,service.snapshot().get("enabled"));
            manager.credential(manager.config().revision(),"connection","synthetic-source-key".toCharArray(),false);
            assertEquals(false,service.snapshot().get("enabled"));
            service.enable(2,1,(String)service.preview(2).get("confirmationToken"));
            manager.disconnect(manager.config().revision(),"connection");
            assertEquals(false,service.snapshot().get("enabled"));
            service.enable(2,1,(String)service.preview(2).get("confirmationToken"));
            assertThrows(Exception.class,()->manager.importMetadata(manager.config().revision(),"not-json"));
            assertEquals(false,service.snapshot().get("enabled"));
            service.enable(2,1,(String)service.preview(2).get("confirmationToken"));
            manager.delete(manager.config().revision(),"mcp","connection");
            assertEquals(false,service.snapshot().get("enabled"));
        }
    }
    @Test void concurrentSessionsAndSummaryCompactionKeepIndependentParents() throws Exception {
        var transport=new Transport();
        try(var service=enabled(transport,false)) {
            var helper=new AgentObservability(service);
            var streams=new ArrayList<Flux<ChatResponse>>();
            for(int n=0;n<8;n++) {
                String session="session-"+n;
                String operation=n%2==0?"context.compact":"model.generation";
                streams.add(helper.trace("summary.generate",session,()->AgentObservability.model(model(),()->helper,operation).stream(input(),List.of(),null))
                        .subscribeOn(reactor.core.scheduler.Schedulers.parallel()));
            }
            Flux.merge(streams).blockLast();
            // Reactor delivers onComplete before upstream doFinally; blockLast may unblock
            // while a parallel subscriber is still ending its span. Await export, not business completion.
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            List<JsonNode> spans;
            do {
                service.drain(); spans=transport.spans();
                if(spans.size()>=16) break;
                Thread.sleep(10);
            } while(System.nanoTime()<deadline);
            final List<JsonNode> completedSpans=spans;
            assertEquals(16,completedSpans.size());
            assertEquals(8,completedSpans.stream().map(s->s.path("traceId").asText()).distinct().count());
            for(var child:completedSpans) if(!child.path("name").asText().equals("summary.generate"))
                assertTrue(completedSpans.stream().anyMatch(p->p.path("spanId").equals(child.path("parentSpanId"))&&p.path("traceId").equals(child.path("traceId"))));
        }
    }
    @Test void cancellationAndSynchronousFailureFinishExactlyOnce() throws Exception {
        var transport=new Transport();
        try(var service=enabled(transport,false)) {
            var helper=new AgentObservability(service);
            var cancelled=helper.trace("selfanalyst.turn","a",Flux::never).subscribe(); cancelled.dispose();
            assertThrows(IllegalStateException.class,()->helper.trace("summary.generate","b",()->{throw new IllegalStateException("secret-error");}).blockLast());
            service.drain();var spans=transport.spans();assertEquals(2,spans.size());
            assertTrue(spans.toString().contains("cancelled"));assertFalse(spans.toString().contains("secret-error"));
            assertNotEquals(spans.get(0).path("traceId"),spans.get(1).path("traceId"));
        }
    }
}
