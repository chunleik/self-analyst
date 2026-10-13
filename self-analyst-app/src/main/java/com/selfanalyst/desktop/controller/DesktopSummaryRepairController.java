package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.wiki.WikiRepairService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local desktop repair mutations. Model/chat text never reaches the commit endpoint. */
public final class DesktopSummaryRepairController {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = DesktopChatJson.MAPPER.copy()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final WikiRepairService repairs;
    private final ChatSessionStore sessions;

    public DesktopSummaryRepairController(WikiRepairService repairs, ChatSessionStore sessions) {
        this.repairs = repairs;
        this.sessions = sessions;
    }

    public void register(Javalin app) {
        app.post("/desktop/summary-repairs", this::create);
        app.get("/desktop/summary-repairs/session/{sessionId}", this::bySession);
        app.get("/desktop/summary-repairs/{id}", this::read);
        app.put("/desktop/summary-repairs/{id}/draft", this::edit);
        app.post("/desktop/summary-repairs/{id}/confirm", this::confirm);
        app.post("/desktop/summary-repairs/{id}/validate", this::validate);
        app.post("/desktop/summary-repairs/{id}/refresh", this::refresh);
        app.post("/desktop/summary-repairs/{id}/commit", this::commit);
    }

    public void create(Context ctx) {
        handle(ctx, () -> {
            JsonNode body = body(ctx, "sessionId", "targetId", "expectedTargetRevision");
            String sessionId = session(text(body, "sessionId"));
            String targetId = text(body, "targetId");
            var existing = repairs.bySession(sessionId);
            if (existing == null && !sessions.getSession(sessionId).messages.isEmpty())
                throw new com.selfanalyst.wiki.WikiRepairException("WIKI_REPAIR_SESSION_NOT_EMPTY");
            var view = body.hasNonNull("expectedTargetRevision")
                    ? repairs.open(sessionId, targetId, revision(body, "expectedTargetRevision"))
                    : repairs.open(sessionId, targetId);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("type", "summary_repair");
            context.put("repairId", view.draft().id());
            context.put("target", DesktopChatJson.MAPPER.convertValue(view.target(), Object.class));
            sessions.updateMeta(sessionId, null, view.target().periodStart() + " – " + view.target().periodEnd(), context);
            return view;
        });
    }

    public void bySession(Context ctx) {
        handle(ctx, () -> {
            var view = repairs.bySession(session(ctx.pathParam("sessionId")));
            if (view == null) throw new MissingRepair();
            return view;
        });
    }

    public void read(Context ctx) {
        handle(ctx, () -> repairs.read(session(ctx.queryParam("sessionId")), ctx.pathParam("id")));
    }

    public void edit(Context ctx) {
        handle(ctx, () -> {
            JsonNode body = body(ctx, "sessionId", "draftRevision", "candidate");
            return repairs.edit(session(text(body, "sessionId")), ctx.pathParam("id"),
                    revision(body, "draftRevision"),
                    candidate(body.get("candidate")));
        });
    }

    /** Explicit reload after a conflict preserves edits but never preserves stale confirmations. */
    public void refresh(Context ctx) {
        handle(ctx, () -> {
            JsonNode body = body(ctx, "sessionId", "draftRevision", "candidate");
            return repairs.rebase(session(text(body, "sessionId")), ctx.pathParam("id"),
                    revision(body, "draftRevision"), body.hasNonNull("candidate") ? candidate(body.get("candidate")) : null);
        });
    }

    private static WikiRepairService.Candidate candidate(JsonNode input) throws Exception {
        JsonNode candidate = object(input);
        fields(candidate, Set.of("summary", "primaryTask", "taskSegments"));
        text(candidate, "summary");
        text(candidate, "primaryTask");
        JsonNode tasks = candidate.get("taskSegments");
        if (tasks == null || !tasks.isArray() || tasks.size() > 24) throw new InvalidRequest();
        for (JsonNode task : tasks) {
            fields(object(task), Set.of("title", "summary", "evidence", "apps", "confidence", "evidenceFactIds", "claimType"));
            text(task, "title");
            text(task, "summary");
            text(task, "confidence");
            text(task, "claimType");
            for (String key : List.of("evidence", "apps", "evidenceFactIds")) {
                JsonNode array = task.get(key);
                if (array != null && !array.isNull()) {
                    if (!array.isArray() || array.size() > 64) throw new InvalidRequest();
                    for (JsonNode value : array) if (!value.isTextual()) throw new InvalidRequest();
                }
            }
        }
        return JSON.treeToValue(candidate, WikiRepairService.Candidate.class);
    }

    public void confirm(Context ctx) {
        handle(ctx, () -> {
            JsonNode body = body(ctx, "sessionId", "draftRevision", "taskIndexes");
            JsonNode indexes = body.get("taskIndexes");
            if (indexes == null || !indexes.isArray() || indexes.isEmpty() || indexes.size() > 24)
                throw new InvalidRequest();
            List<Integer> tasks = new ArrayList<>();
            for (JsonNode index : indexes) {
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0)
                    throw new InvalidRequest();
                if (tasks.contains(index.intValue())) throw new InvalidRequest();
                tasks.add(index.intValue());
            }
            return repairs.confirm(session(text(body, "sessionId")), ctx.pathParam("id"),
                    revision(body, "draftRevision"), tasks);
        });
    }

    public void validate(Context ctx) {
        handle(ctx, () -> {
            JsonNode body = body(ctx, "sessionId", "draftRevision");
            return repairs.validate(session(text(body, "sessionId")), ctx.pathParam("id"), revision(body, "draftRevision"));
        });
    }

    public void commit(Context ctx) {
        handle(ctx, () -> {
            JsonNode body = body(ctx, "sessionId", "draftRevision", "idempotencyKey");
            return repairs.commit(session(text(body, "sessionId")), ctx.pathParam("id"),
                    revision(body, "draftRevision"), text(body, "idempotencyKey"));
        });
    }

    private String session(String id) {
        if (!ChatSessionStore.isGeneratedSessionId(id)) throw new InvalidRequest();
        if (sessions.pendingDeletionIds().contains(id) || sessions.getSession(id) == null) throw new MissingRepair();
        return id;
    }

    private static JsonNode body(Context ctx, String... allowed) throws Exception {
        JsonNode body = object(JSON.readTree(DesktopChatJson.readBoundedBody(ctx)));
        fields(body, Set.of(allowed));
        return body;
    }

    private static JsonNode object(JsonNode value) {
        if (value == null || !value.isObject()) throw new InvalidRequest();
        return value;
    }

    private static void fields(JsonNode body, Set<String> allowed) {
        body.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new InvalidRequest(); });
    }

    private static String text(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual()) throw new InvalidRequest();
        return value.textValue();
    }

    private static long revision(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new InvalidRequest();
        return value.longValue();
    }

    private void handle(Context ctx, Operation operation) {
        if (repairs == null) {
            ctx.status(503).json(Map.of("code", "WIKI_REPAIR_UNAVAILABLE", "error", "WIKI_REPAIR_UNAVAILABLE"));
            return;
        }
        try {
            // Session deletion and repair mutations use the same lock order: chat then Wiki.
            synchronized (sessions) { ctx.json(operation.run()); }
        } catch (DesktopChatJson.PayloadTooLargeException failure) {
            error(ctx, 413, "WIKI_REPAIR_REQUEST_TOO_LARGE");
        } catch (MissingRepair failure) {
            error(ctx, 404, "WIKI_REPAIR_NOT_FOUND");
        } catch (IllegalArgumentException failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("WIKI_REPAIR_[A-Z_]+")) code = "WIKI_REPAIR_INVALID_REQUEST";
            error(ctx, code.endsWith("CONFLICT") ? 409 : code.equals("WIKI_REPAIR_NOT_FOUND") ? 404 : 400, code);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            error(ctx, 400, "WIKI_REPAIR_INVALID_REQUEST");
        } catch (Exception failure) {
            error(ctx, 500, "WIKI_REPAIR_UNAVAILABLE");
        }
    }

    private static void error(Context ctx, int status, String code) {
        ctx.status(status).json(Map.of("code", code, "error", code));
    }

    @FunctionalInterface private interface Operation { Object run() throws Exception; }
    private static final class InvalidRequest extends IllegalArgumentException { }
    private static final class MissingRepair extends IllegalArgumentException { }
}
