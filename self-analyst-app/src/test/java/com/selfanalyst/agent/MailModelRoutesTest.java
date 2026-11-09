package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.service.ChatSummaryService;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import com.selfanalyst.i18n.Lang;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MailModelRoutesTest {
    @TempDir Path root;
    @Test void actualChatCompactionAndListSummaryNeverSendToChangedTarget() throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(500,-1); exchange.close(); }); server.start();
        Config config = config(Config.testDefaults(root), Map.of("llmApiKey", "synthetic-key", "llmBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "llmModel", "synthetic", "agentCompactionEnabled", true, "agentCompactionTriggerMessages", 2));
        try (var store = new ChatSessionStore(root); var agent = new SelfAnalystAgent(config)) {
            var session = store.create(new ChatSessionStore.CreateRequest());
            var policy = new MailSessionPolicy(store); policy.consentTarget(session.id, "https://original.example/v1"); policy.beforeMailResult(session.id, "https://original.example/v1");
            agent.setMailSessionPolicy(policy);
            String response = agent.chat(session.id, "a".repeat(12), () -> new SelfAnalystAgent.PersistedDesktopTurn("Continue", null,
                    List.of(Msg.builder().role(MsgRole.USER).textContent("Mail-derived history").build(), Msg.builder().role(MsgRole.ASSISTANT).textContent("Paraphrase").build()), true)).block();
            assertEquals(com.selfanalyst.i18n.Messages.text(config.effectiveLanguage(), "mail.modelConsentRequired"), response);
            try (var task = agent.plainTask(session.id)) {
                assertFalse(task.permitsMailOrigin(session.id));
                assertThrows(MailSessionPolicy.ModelConsentRequiredException.class, () -> task.complete("Mail-derived summary", Duration.ofSeconds(1)));
                assertNotNull(new ChatSummaryService(Lang.english()).summarize(store.getSession(session.id), task));
            }
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }
    @Test void guardedCompactorChecksAtActualSubscriptionAndPreservesHistory() {
        var calls = new AtomicInteger();
        try (var store = new ChatSessionStore(root)) {
            String id = store.create(new ChatSessionStore.CreateRequest()).id;
            var policy = new MailSessionPolicy(store); policy.consentTarget(id, "https://old.example/v1"); policy.beforeMailResult(id, "https://old.example/v1");
            Model raw = new Model() {
                public String getModelName() { return "fake"; }
                public Flux<ChatResponse> stream(List<Msg> m,List<ToolSchema> t,GenerateOptions o) { calls.incrementAndGet(); return Flux.empty(); }
            };
            Model guarded = new MailGuardedModel(raw, () -> policy.requireModelTarget(id, "https://new.example/v1"));
            var states = new JsonFileAgentStateStore(root.resolve("states"));
            try (var agent = ReActAgent.builder().name("test").model(raw).stateStore(states).build()) {
                var state = agent.getAgentState("desktop", id);
                for (int i=0;i<6;i++) state.contextMutable().add(Msg.builder().role(i%2==0?MsgRole.USER:MsgRole.ASSISTANT).textContent("Mail text " + i).build());
                agent.saveAgentState("desktop", id);
                var compactor = new TransactionalAgentStateCompactor(agent, root.resolve("workspace"), guarded,
                        CompactionConfig.builder().triggerMessages(2).keepMessages(2).flushBeforeCompact(false).offloadBeforeCompact(false).model(guarded).build());
                assertFalse(compactor.compactIfNeeded("desktop", id).block());
                assertEquals(6, agent.getAgentState("desktop", id).getContext().size()); assertEquals(0,calls.get());
            } finally { states.close(); }
        }
    }
    @Test void approvedApiRecipientNeverForwardsChatOrPlainBodyAcross307Or308() throws Exception {
        var redirected = new AtomicInteger(); var originCalls = new AtomicInteger(); var status = new AtomicInteger(307);
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        other.createContext("/", exchange -> { redirected.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(500,-1); exchange.close(); }); other.start();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin.createContext("/", exchange -> {
            originCalls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location","http://127.0.0.1:" + other.getAddress().getPort() + "/capture");
            exchange.sendResponseHeaders(status.get(),-1); exchange.close();
        }); origin.start();
        String endpoint = "http://127.0.0.1:" + origin.getAddress().getPort() + "/v1";
        Config config = config(Config.testDefaults(root), Map.of("llmApiKey","synthetic", "llmBaseUrl",endpoint,"llmModel","synthetic"));
        try (var store = new ChatSessionStore(root); var agent = new SelfAnalystAgent(config)) {
            var policy = new MailSessionPolicy(store); agent.setMailSessionPolicy(policy);
            for (int redirect : List.of(307,308)) {
                status.set(redirect); String id = store.create(new ChatSessionStore.CreateRequest()).id;
                policy.consentTarget(id,endpoint); policy.beforeMailResult(id,endpoint);
                assertThrows(RuntimeException.class, () -> agent.chat(id,"a".repeat(12),"Synthetic mail body").block(Duration.ofSeconds(5)));
                try (var task = agent.plainTask(id)) {
                    try { task.complete("Synthetic mail summary",Duration.ofSeconds(3)); } catch (RuntimeException expected) { /* Redirect is a failure. */ }
                }
            }
            assertTrue(originCalls.get() >= 4); assertEquals(0,redirected.get());
        } finally { origin.stop(0); other.stop(0); }
    }
    static Config config(Config base, Map<String,Object> values) throws Exception {
        var components = Config.class.getRecordComponents(); Class<?>[] types = new Class<?>[components.length]; Object[] args = new Object[components.length];
        for (int i=0;i<components.length;i++) { types[i]=components[i].getType(); args[i]=values.containsKey(components[i].getName())?values.get(components[i].getName()):components[i].getAccessor().invoke(base); }
        return Config.class.getDeclaredConstructor(types).newInstance(args);
    }
}
