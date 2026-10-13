package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.wiki.*;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DesktopSummaryRepairControllerTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    @Test
    void completeLocalPathHasAuthoritativeBindingConfirmationAndIdempotentCommit() throws Exception {
        try (WikiStore wiki = new WikiStore(directory.resolve("wiki.db"));
             ChatSessionStore sessions = new ChatSessionStore(directory.resolve("chat"))) {
            wiki.upsert(failed("hour"));
            var repairs = repairs(wiki);
            var controller = new DesktopSummaryRepairController(repairs, sessions);
            String session = sessions.create(new ChatSessionStore.CreateRequest()).id;
            Captured create = invoke(controller::create, Map.of("sessionId", session, "targetId", "hour", "expectedTargetRevision", wiki.revision("hour")), null);
            assertEquals(200, create.status);
            var opened = (WikiRepairService.View) create.result;
            assertFalse(opened.failureTextAvailable());
            assertEquals("summary_repair", ((Map<?, ?>) sessions.getSession(session).contextSnapshot).get("type"));
            String repair = opened.draft().id();
            var task = Map.of("title", "服务部署", "summary", "我已经完成服务部署", "claimType", "user_confirmed", "confidence", "high", "evidenceFactIds", List.of());
            var candidate = Map.of("summary", "我已经完成服务部署", "primaryTask", "服务部署", "taskSegments", List.of(task));
            Captured edit = invoke(controller::edit, Map.of("sessionId", session, "draftRevision", 0, "candidate", candidate), repair);
            assertEquals(200, edit.status);
            long revision = ((WikiRepairService.View) edit.result).draft().draftRevision();
            Captured invalid = invoke(controller::validate, Map.of("sessionId", session, "draftRevision", revision), repair);
            assertEquals("invalid", ((WikiRepairService.View) invalid.result).draft().state());
            Captured confirm = invoke(controller::confirm, Map.of("sessionId", session, "draftRevision", revision, "taskIndexes", List.of(0)), repair);
            assertEquals(200, confirm.status);
            var confirmed = (WikiRepairService.View) confirm.result;
            revision = confirmed.draft().draftRevision();
            assertEquals("medium", confirmed.draft().candidate().taskSegments().getFirst().confidence());
            assertEquals(1, confirmed.draft().statements().size());
            Captured valid = invoke(controller::validate, Map.of("sessionId", session, "draftRevision", revision), repair);
            assertEquals("valid", ((WikiRepairService.View) valid.result).draft().state());
            revision = ((WikiRepairService.View) valid.result).draft().draftRevision();
            Map<String, Object> commitBody = Map.of("sessionId", session, "draftRevision", revision, "idempotencyKey", "request-1");
            Captured commit = invoke(controller::commit, commitBody, repair);
            assertEquals(200, commit.status);
            Captured duplicate = invoke(controller::commit, commitBody, repair);
            assertEquals(((WikiRepairService.View) commit.result).draft().committedRevision(), ((WikiRepairService.View) duplicate.result).draft().committedRevision());
            assertEquals(WikiStatus.SUMMARIZED, wiki.versionedEntry("hour").entry().status());
            assertEquals(failed("hour").metrics().activeSeconds(), wiki.versionedEntry("hour").entry().metrics().activeSeconds());
            assertEquals("user_confirmed", wiki.versionedEntry("hour").entry().taskSegments().getFirst().claimType());
        }
    }

    @Test
    void rejectsCrossSessionForgedFlagsMetricsAndStaleDraftWithoutLosingInput() throws Exception {
        try (WikiStore wiki = new WikiStore(directory.resolve("wiki.db"));
             ChatSessionStore sessions = new ChatSessionStore(directory.resolve("chat"))) {
            wiki.upsert(failed("hour"));
            var repairs = repairs(wiki);
            var controller = new DesktopSummaryRepairController(repairs, sessions);
            String owner = sessions.create(new ChatSessionStore.CreateRequest()).id;
            String other = sessions.create(new ChatSessionStore.CreateRequest()).id;
            var opened = repairs.open(owner, "hour");
            String id = opened.draft().id();
            assertEquals(404, invoke(controller::validate, Map.of("sessionId", other, "draftRevision", 0), id).status);
            var malicious = new LinkedHashMap<String, Object>();
            malicious.put("summary", "浏览服务文档"); malicious.put("primaryTask", "服务文档"); malicious.put("taskSegments", List.of());
            malicious.put("metrics", Map.of("activeSeconds", 0));
            assertEquals(400, invoke(controller::edit, Map.of("sessionId", owner, "draftRevision", 0, "candidate", malicious), id).status);
            malicious.remove("metrics"); malicious.put("confirmed", true);
            assertEquals(400, invoke(controller::edit, Map.of("sessionId", owner, "draftRevision", 0, "candidate", malicious), id).status);
            malicious.remove("confirmed");
            assertEquals(200, invoke(controller::edit, Map.of("sessionId", owner, "draftRevision", 0, "candidate", malicious), id).status);
            assertEquals(409, invoke(controller::validate, Map.of("sessionId", owner, "draftRevision", 0), id).status);
            assertEquals("浏览服务文档", repairs.read(owner, id).draft().candidate().summary());
            assertEquals(400, invoke(controller::confirm, Map.of("sessionId", owner, "draftRevision", 1, "taskIndexes", List.of(0.5)), id).status);
        }
    }

    @Test
    void backgroundPublicationAndMissingSessionAreNotSilentlyOverwritten() throws Exception {
        try (WikiStore wiki = new WikiStore(directory.resolve("wiki.db"));
             ChatSessionStore sessions = new ChatSessionStore(directory.resolve("chat"))) {
            wiki.upsert(failed("hour"));
            var repairs = repairs(wiki);
            var controller = new DesktopSummaryRepairController(repairs, sessions);
            String session = sessions.create(new ChatSessionStore.CreateRequest()).id;
            var opened = repairs.open(session, "hour");
            wiki.upsert(failed("hour")); // A real background write increments the target revision.
            assertEquals(409, invoke(controller::validate, Map.of("sessionId", session, "draftRevision", 0), opened.draft().id()).status);
            String missing = "a".repeat(32);
            assertEquals(404, invoke(controller::create, Map.of("sessionId", missing, "targetId", "hour"), null).status);
            assertNotNull(repairs.read(session, opened.draft().id()));
        }
    }

    @Test
    void explicitRefreshRebasesUnsavedInputAndOrdinaryPopulatedSessionCannotBeRebound() throws Exception {
        try (WikiStore wiki = new WikiStore(directory.resolve("wiki.db"));
             ChatSessionStore sessions = new ChatSessionStore(directory.resolve("chat"))) {
            wiki.upsert(failed("hour"));
            var repairs = repairs(wiki);
            var controller = new DesktopSummaryRepairController(repairs, sessions);
            String session = sessions.create(new ChatSessionStore.CreateRequest()).id;
            var opened = repairs.open(session, "hour");
            wiki.upsert(failed("hour"));
            var candidate = Map.of("summary", "保留浏览器输入", "primaryTask", "本地草稿", "taskSegments", List.of());
            Captured refreshed = invoke(controller::refresh, Map.of("sessionId", session, "draftRevision", 0, "candidate", candidate), opened.draft().id());
            assertEquals(200, refreshed.status);
            var view = (WikiRepairService.View) refreshed.result;
            assertEquals(wiki.revision("hour"), view.draft().targetRevision());
            assertEquals(1, view.draft().draftRevision());
            assertEquals("保留浏览器输入", view.draft().candidate().summary());
            assertEquals(List.of(), view.draft().statements());
            assertEquals("unvalidated", view.draft().state());
            String ordinary = sessions.create(new ChatSessionStore.CreateRequest()).id;
            var user = new ChatSessionStore.Message(); user.role = "user"; user.content = "ordinary user";
            sessions.appendMessages(ordinary, List.of(user));
            assertEquals(400, invoke(controller::create, Map.of("sessionId", ordinary, "targetId", "hour"), null).status);
            assertNull(repairs.bySession(ordinary));
        }
    }

    @Test
    void malformedTypesAndUnknownFieldsAreBoundedClientErrors() throws Exception {
        try (WikiStore wiki = new WikiStore(directory.resolve("wiki.db"));
             ChatSessionStore sessions = new ChatSessionStore(directory.resolve("chat"))) {
            var controller = new DesktopSummaryRepairController(repairs(wiki), sessions);
            for (Object body : List.of(Map.of("sessionId", 123, "targetId", "hour"), List.of("not-an-object"),
                    Map.of("sessionId", "a".repeat(32), "targetId", "hour", "state", "quality"))) {
                assertEquals(400, invoke(controller::create, body, null).status);
            }
        }
    }

    private static WikiRepairService repairs(WikiStore wiki) {
        return new WikiRepairService(wiki, entry -> new WikiFactBuilder.WikiFacts(
                new WikiPeriod(entry.level(), entry.periodStart(), entry.periodEnd(), entry.timezone()),
                120, 30, 2, List.of(new WikiEntry.AppDuration("Editor", 120)), List.of(), List.of()),
                WikiPrivacyPolicy::none, () -> "test");
    }

    private static WikiEntry failed(String id) {
        return new WikiEntry(id, WikiLevel.HOUR, START, END, "UTC", WikiStatus.FAILED,
                null, null, List.of(), new WikiEntry.WikiMetrics(120, 30, 2, List.of(new WikiEntry.AppDuration("Editor", 120)), Map.of()),
                List.of(), "fake", "v1", 2, null, "WIKI_EVIDENCE_UNSUPPORTED_CLAIM:summary", START, END, null);
    }

    private static Captured invoke(java.util.function.Consumer<Context> handler, Object body, String id) throws Exception {
        Captured captured = new Captured(JSON.writeValueAsString(body), id);
        handler.accept(captured.context());
        return captured;
    }

    private static final class Captured {
        private final String body;
        private final String id;
        int status = 200;
        Object result;
        Captured(String body, String id) { this.body = body; this.id = id; }
        Context context() {
            return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "body" -> body;
                        case "pathParam" -> id;
                        case "status" -> { if (args.length > 0) { status = (Integer) args[0]; yield proxy; } yield status; }
                        case "json" -> { result = args[0]; yield proxy; }
                        case "attribute" -> null;
                        default -> { if (method.getReturnType() == boolean.class) yield false; yield null; }
                    });
        }
    }
}
