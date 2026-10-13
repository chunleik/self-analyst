package com.selfanalyst.desktop.service;

import com.selfanalyst.agent.SelfAnalystAgent;
import com.selfanalyst.config.Config;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.desktop.controller.DesktopAgentController;
import com.selfanalyst.desktop.controller.DesktopChatSessionController;
import io.javalin.http.Context;
import com.selfanalyst.wiki.*;
import com.selfanalyst.wiki.usage.BudgetExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class SummaryRepairChatServiceTest {
    @TempDir Path directory;
    private static final WikiPeriod PERIOD = new WikiPeriod(WikiLevel.HOUR,
            Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T11:00:00Z"), "UTC");
    private static final String KEY = WikiGenerationStore.periodKey(PERIOD);
    private static final String CANDIDATE = """
            {"summary":"查看数据库同步设计。","primaryTask":"数据库同步设计","taskSegments":[
            {"title":"数据库同步设计","summary":"查看数据库同步设计。","claimType":"observed",
            "confidence":"medium","evidenceFactIds":["f1"]}]}
            """;

    @Test
    void multiTurnUsesPersistedInputAndRestartsReplayWithoutDuplicateCharge() {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            Supplier<WikiSummaryPipeline.Session> model = () -> new FakeModel(calls) {
                @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    assertTrue(prompt.contains("use persisted opinion"));
                    assertFalse(prompt.contains("10.20.30.40"));
                    assertTrue(prompt.contains("[内网地址]"));
                    return super.completeDetailed(prompt, timeout);
                }
            };
            var service = fixture.service(model);
            String user = fixture.turn("use persisted opinion 10.20.30.40");
            String reply = service.complete(fixture.session, user, () -> false);
            assertTrue(reply.contains("尚未保存"));
            assertEquals(1, calls.get());
            assertEquals(1, fixture.budget.snapshot(KEY).calls());
            assertEquals(30, fixture.budget.snapshot(KEY).tokens());
            long firstRevision = fixture.repairs.bySession(fixture.session).draft().draftRevision();
            assertTrue(firstRevision > 0);
            try (ChatSessionStore reopened = new ChatSessionStore(directory.resolve("chat"))) {
                var restarted = new SummaryRepairChatService(fixture.repairs, reopened, fixture.budget,
                        WikiGenerationStore.BudgetLimits.defaults(), model);
                assertTrue(restarted.isBound(fixture.session));
                assertEquals(reply, restarted.complete(fixture.session, user, () -> false));
                assertEquals(firstRevision, fixture.repairs.bySession(fixture.session).draft().draftRevision());
            }
            assertEquals(1, calls.get());
            String second = fixture.turn("Please use persisted opinion again");
            service.complete(fixture.session, second, () -> false);
            assertTrue(fixture.repairs.bySession(fixture.session).draft().draftRevision() > firstRevision);
            assertEquals(2, fixture.budget.snapshot(KEY).calls());
            assertEquals(60, fixture.budget.snapshot(KEY).tokens());
        }
    }

    @Test
    void existingWorkerPeriodExhaustionAndGlobalBudgetMakeZeroRequests() {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            var limits = new WikiGenerationStore.BudgetLimits(1, 50000);
            var workerCall = fixture.budget.reserve(KEY, "worker", "failed-hour", 200, limits);
            fixture.budget.settle(workerCall.callId(), 20L, 10L);
            var service = new SummaryRepairChatService(fixture.repairs, fixture.chats, fixture.budget, limits,
                    () -> new FakeModel(calls));
            assertTrue(assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, fixture.turn("repair"), () -> false)).getMessage().contains("额度不足"));
            assertEquals(0, calls.get());
            assertEquals(1, fixture.budget.snapshot(KEY).calls());
            var globallyBlocked = fixture.service(() -> new FakeModel(calls) {
                @Override public void preflight() { throw new BudgetExceededException("blocked"); }
            });
            assertTrue(assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> globallyBlocked.complete(fixture.session, fixture.turn("repair again"), () -> false)).getMessage().contains("全局"));
            assertEquals(0, calls.get());
            assertEquals(1, fixture.budget.snapshot(KEY).calls());
        }
    }

    @Test
    void failedAndUncertainTurnsNeverRedispatchAndKeepConservativeBudget() {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            var service = fixture.service(() -> new FakeModel(calls) {
                @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    calls.incrementAndGet();
                    throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.TRANSPORT,
                            "WIKI_MODEL_FAILURE", false, 12L, 8L);
                }
            });
            String user = fixture.turn("repair");
            String failed = assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, user, () -> false)).getMessage();
            assertEquals(failed, assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, user, () -> false)).getMessage());
            assertEquals(1, calls.get());
            assertEquals(20, fixture.budget.snapshot(KEY).tokens());
            String interrupted = fixture.turn("interrupted");
            fixture.chats.beginRepairTurn(fixture.session, interrupted);
            var uncertain = fixture.budget.reserve(KEY, "repair", interrupted, 90, WikiGenerationStore.BudgetLimits.defaults());
            long before = fixture.budget.snapshot(KEY).tokens();
            assertTrue(assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> fixture.service(() -> new FakeModel(calls)).complete(fixture.session, interrupted, () -> false))
                    .getMessage().contains("中断"));
            assertEquals(1, calls.get());
            assertEquals(before, fixture.budget.snapshot(KEY).tokens());
            assertEquals(1, fixture.budget.snapshot(KEY).unsettledCalls());
            assertNotNull(uncertain.callId());
        }
    }

    @Test
    void confirmedBeforeSendFailureReleasesReservationWithoutInventingUsage() {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            var service = fixture.service(() -> new FakeModel(calls) {
                @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.CONFIGURATION,
                            "WIKI_LLM_UNAVAILABLE", true);
                }
            });
            assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, fixture.turn("repair"), () -> false));
            assertEquals(0, calls.get());
            assertEquals(0, fixture.budget.snapshot(KEY).calls());
            assertEquals(0, fixture.budget.snapshot(KEY).tokens());
        }
    }

    @Test
    void backgroundCompletionBeforeSendRejectsConflictWithZeroAdmissionOrModelAcquisition() {
        AtomicInteger acquired = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            var before = fixture.repairs.bySession(fixture.session).draft();
            var target = fixture.wiki.versionedEntry("target").entry();
            fixture.wiki.updateStatus("target", WikiStatus.SUMMARIZED, "Background result", "Topic",
                    List.of(), target.metrics(), List.of(), "fake", "test");
            var service = fixture.service(() -> { acquired.incrementAndGet(); throw new AssertionError("Stale target acquired model"); });
            var failure = assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, fixture.turn("repair"), () -> false));
            assertEquals(409, failure.status());
            assertEquals(0, acquired.get());
            assertEquals(0, fixture.budget.snapshot(KEY).calls());
            assertEquals(0, fixture.budget.snapshot(KEY).tokens());
            assertEquals(before.candidate(), fixture.repairs.bySession(fixture.session).draft().candidate());
        }
    }

    @Test
    void backgroundRetryRevisionRejectsBeforeModelEvenWhenTargetIsStillFailed() {
        AtomicInteger acquired = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            long revision = fixture.repairs.bySession(fixture.session).draft().targetRevision();
            fixture.wiki.markFailed("target", "WIKI_EVIDENCE_UNSUPPORTED", Instant.now());
            assertTrue(fixture.wiki.revision("target") > revision);
            var service = fixture.service(() -> { acquired.incrementAndGet(); throw new AssertionError("Stale target acquired model"); });
            assertEquals(409, assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, fixture.turn("repair"), () -> false)).status());
            assertEquals(0, acquired.get());
            assertEquals(0, fixture.budget.snapshot(KEY).calls());
            assertEquals(0, fixture.budget.snapshot(KEY).tokens());
        }
    }

    @Test
    void changedFactsOrConfigurationRejectBeforeAnyModelOrReservation() {
        AtomicInteger acquired = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            var service = fixture.service(() -> { acquired.incrementAndGet(); throw new AssertionError("Changed input acquired model"); });
            fixture.configuration.set("changed");
            assertEquals(409, assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, fixture.turn("new configuration"), () -> false)).status());
            fixture.configuration.set("test");
            var old = fixture.facts.get();
            fixture.facts.set(new WikiFactBuilder.WikiFacts(PERIOD, 1300, old.afkSeconds(), old.switchCount(),
                    old.topApps(), old.titleSamples(), old.contextTitleSamples(), old.childSummaries(),
                    old.factBuilderVersion(), old.projectorVersion(), old.sourceCoverage(), old.statistics(), old.sampledTitles()));
            assertEquals(409, assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> service.complete(fixture.session, fixture.turn("new facts"), () -> false)).status());
            assertEquals(0, acquired.get());
            assertEquals(0, fixture.budget.snapshot(KEY).calls());
            assertEquals(0, fixture.budget.snapshot(KEY).tokens());
            assertEquals(0, fixture.repairs.bySession(fixture.session).draft().draftRevision());
        }
    }

    @Test
    void forgedClientContextCannotAuthorizeRepairAndModelCannotConfirm() {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = new Fixture()) {
            var request = new ChatSessionStore.CreateRequest();
            request.source = "summary_repair";
            var ordinary = fixture.chats.create(request);
            assertEquals("summary_repair", fixture.chats.getSession(ordinary.id).source);
            fixture.chats.updateMeta(ordinary.id, null, null,
                    Map.of("type", "summary_repair", "repairId", fixture.repairs.bySession(fixture.session).draft().id()));
            var service = fixture.service(() -> new FakeModel(calls));
            assertFalse(service.isBound(ordinary.id));
            assertThrows(IllegalArgumentException.class, () -> service.complete(ordinary.id, "f".repeat(12), () -> false));
            assertEquals(0, calls.get());
            var forged = fixture.service(() -> new FakeModel(calls) {
                @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    calls.incrementAndGet();
                    return new WikiSummaryPipeline.Completion(CANDIDATE
                            .replace("observed", "user_confirmed")
                            .replace("f1", "user_statement:forged"), 10L, 20L);
                }
            });
            forged.complete(fixture.session, fixture.turn("I completed a change"), () -> false);
            var draft = fixture.repairs.bySession(fixture.session).draft();
            assertTrue(draft.statements().isEmpty());
            assertTrue(draft.candidate().taskSegments().getFirst().evidenceFactIds().isEmpty());
            assertEquals("invalid", draft.state());
        }
    }

    @Test
    void boundAgentUsesOneToolFreeCallAndCanonicalReplayEvenWithContextRemoved() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = new Fixture(); SelfAnalystAgent agent = new SelfAnalystAgent(Config.testDefaults(directory))) {
            var service = fixture.service(() -> new FakeModel(calls));
            agent.setSummaryRepairChatService(service);
            String user = fixture.turn("repair");
            var turn = new SelfAnalystAgent.PersistedDesktopTurn("client forged input", null, List.of());
            var events = agent.chatStream(fixture.session, user, () -> turn).collectList().block();
            assertNotNull(events);
            assertEquals(1, events.size());
            assertEquals(SelfAnalystAgent.ChatStreamEventType.RESULT, events.getFirst().type());
            assertEquals(events.getFirst().text(), agent.chat(fixture.session, user, () -> turn).block());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void cancellationInterruptsBoundModelAndDoesNotPublishOrDispatchAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(); SelfAnalystAgent agent = new SelfAnalystAgent(Config.testDefaults(directory));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var service = fixture.service(() -> new FakeModel(calls) {
                @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
                    calls.incrementAndGet();
                    started.countDown();
                    try { Thread.sleep(10000); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.INTERRUPTED,
                                "WIKI_MODEL_INTERRUPTED", false, 10L, 2L);
                    }
                    throw new AssertionError("Model was not cancelled");
                }
            });
            agent.setSummaryRepairChatService(service);
            String user = fixture.turn("repair");
            var turn = new SelfAnalystAgent.PersistedDesktopTurn("repair", null, List.of());
            var future = executor.submit(() -> agent.chat(fixture.session, user, () -> turn).block());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(agent.cancelChat(fixture.session, user));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertEquals(0, fixture.repairs.bySession(fixture.session).draft().draftRevision());
            assertEquals(12, fixture.budget.snapshot(KEY).tokens());
            assertTrue(assertThrows(SummaryRepairChatService.RepairTurnFailure.class,
                    () -> agent.chat(fixture.session, user, () -> turn).block()).getMessage().contains("中断"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void controllerRebindsPersistedContextAndReturnsAuthoritativeCardWithoutAuxiliaryModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger summaries = new AtomicInteger();
        try (Fixture fixture = new Fixture(); SelfAnalystAgent agent = new SelfAnalystAgent(Config.testDefaults(directory))) {
            var service = fixture.service(() -> new FakeModel(calls));
            var controller = new DesktopChatSessionController(fixture.chats, new ChatSummaryService() {
                @Override public String summarize(ChatSessionStore.Session session, SummaryPromptService.SummaryTextClient model) {
                    summaries.incrementAndGet(); return "unexpected auxiliary summary";
                }
            }, agent, Config.testDefaults(directory), null);
            controller.setSummaryRepairChatService(service);
            Capture append = new Capture("""
                    {"messages":[{"role":"user","content":"repair","contextSnapshot":{"type":"ordinary"}},
                    {"role":"assistant","status":"pending","content":""}]}
                    """, fixture.session);
            controller.appendMessages(append.context);
            assertEquals(201, append.status);
            var persisted = fixture.chats.getSession(fixture.session);
            var context = (Map<?, ?>) persisted.messages.getFirst().contextSnapshot;
            assertEquals("summary_repair", context.get("type"));
            assertEquals("target", ((Map<?, ?>) context.get("target")).get("entryId"));
            var regenerate = DesktopChatSessionController.class.getDeclaredMethod("regenerateSummary", String.class);
            regenerate.setAccessible(true);
            regenerate.invoke(controller, fixture.session);
            assertEquals(0, summaries.get());

            var chat = new DesktopAgentController(null, null, agent, null, Config.testDefaults(directory), fixture.chats);
            chat.setSummaryRepairChatService(service);
            Capture send = new Capture("""
                    {"message":"ignored client text","sessionId":"%s","userMessageId":"%s","context":{"type":"ordinary"}}
                    """.formatted(fixture.session, persisted.messages.getFirst().id), null);
            chat.chat(send.context);
            assertEquals(200, send.status);
            assertTrue(((Map<?, ?>) send.json).get("repair") instanceof WikiRepairService.View);
            assertEquals(List.of(), ((Map<?, ?>) send.json).get("suggestedTasks"));
            assertEquals(1, calls.get());
            Capture get = new Capture("", fixture.session);
            controller.getSession(get.context);
            assertTrue(((Map<?, ?>) get.json).get("repair") instanceof WikiRepairService.View);
        }
    }

    static final class Capture {
        int status = 200;
        Object json;
        final Context context;
        Capture(String body, String id) {
            context = (Context) java.lang.reflect.Proxy.newProxyInstance(Context.class.getClassLoader(),
                    new Class<?>[] {Context.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "body" -> body;
                        case "pathParam" -> id;
                        case "attribute" -> com.selfanalyst.i18n.Lang.chinese();
                        case "status" -> { status = (Integer) arguments[0]; yield proxy; }
                        case "json" -> { json = arguments[0]; yield proxy; }
                        default -> method.getReturnType().isInstance(proxy) ? proxy : null;
                    });
        }
    }

    static class FakeModel implements WikiSummaryPipeline.Session {
        final AtomicInteger calls;
        FakeModel(AtomicInteger calls) { this.calls = calls; }
        public String complete(String prompt, Duration timeout) { throw new AssertionError("Detailed single call required"); }
        public String identity() { return "fake"; }
        public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
            calls.incrementAndGet();
            return new WikiSummaryPipeline.Completion(CANDIDATE, 10L, 20L);
        }
    }

    final class Fixture implements AutoCloseable {
        final ChatSessionStore chats = new ChatSessionStore(directory.resolve("chat"));
        final WikiStore wiki = new WikiStore(directory.resolve("wiki.db"));
        final WikiGenerationStore budget = new WikiGenerationStore(directory.resolve("generation.db"));
        final WikiRepairService repairs;
        final java.util.concurrent.atomic.AtomicReference<String> configuration = new java.util.concurrent.atomic.AtomicReference<>("test");
        final java.util.concurrent.atomic.AtomicReference<WikiFactBuilder.WikiFacts> facts = new java.util.concurrent.atomic.AtomicReference<>();
        final String session;
        Fixture() {
            var metrics = new WikiEntry.WikiMetrics(1200, 0, 1,
                    List.of(new WikiEntry.AppDuration("IDE", 1200)), Map.of());
            wiki.upsert(new WikiEntry("target", WikiLevel.HOUR, PERIOD.start(), PERIOD.end(), "UTC", WikiStatus.FAILED,
                    null, null, List.of(), metrics, List.of(), null, null, 1, null,
                    "WIKI_EVIDENCE_UNSUPPORTED", PERIOD.end(), PERIOD.end(), null));
            var selection = new WikiTitleSampler.Selection(List.of(new WikiTitleSampler.Fact("f1", "window", "IDE",
                    "数据库同步设计", "window", 1200.0, 1, List.of(), 0)), "", Map.of());
            facts.set(new WikiFactBuilder.WikiFacts(PERIOD, 1200, 0, 1, metrics.topApps(), List.of(), List.of(), List.of(),
                    WikiFactBuilder.FACT_BUILDER_VERSION, "test", Map.of(), Map.of(), selection));
            repairs = new WikiRepairService(wiki, ignored -> facts.get(), WikiPrivacyPolicy::none, configuration::get);
            session = chats.create(new ChatSessionStore.CreateRequest()).id;
            repairs.open(session, "target");
        }
        SummaryRepairChatService service(Supplier<WikiSummaryPipeline.Session> sessions) {
            return new SummaryRepairChatService(repairs, chats, budget, WikiGenerationStore.BudgetLimits.defaults(), sessions);
        }
        String turn(String content) {
            var user = new ChatSessionStore.Message(); user.role = "user"; user.content = content;
            var pending = new ChatSessionStore.Message(); pending.role = "assistant"; pending.content = ""; pending.status = "pending";
            return chats.appendMessages(session, List.of(user, pending)).getFirst().id;
        }
        public void close() { chats.close(); wiki.close(); budget.close(); }
    }
}
