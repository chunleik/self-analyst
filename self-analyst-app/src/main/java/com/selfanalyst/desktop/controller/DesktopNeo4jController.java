package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.ontology.Neo4jSyncService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Map;
import java.util.function.Supplier;

/** 桌面专用手工同步入口，不注册 Agent 工具，也不启动定时任务。 */
public final class DesktopNeo4jController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> ERRORS = Map.ofEntries(
            Map.entry("neo4j.invalidConfig", "invalid"),
            Map.entry("neo4j.disabled", "disabled"),
            Map.entry("neo4j.confirmationRequired", "confirmation"),
            Map.entry("neo4j.ontologyUnavailable", "unavailable"),
            Map.entry("neo4j.busy", "running"),
            Map.entry("neo4j.missingPassword", "credentials"),
            Map.entry("neo4j.snapshotTooLarge", "capacity"),
            Map.entry("neo4j.invalidSnapshot", "invalid"),
            Map.entry("neo4j.authenticationFailed", "authentication"),
            Map.entry("neo4j.connectionFailed", "connection"),
            Map.entry("neo4j.timeout", "timeout"),
            Map.entry("neo4j.syncFailed", "failed"));
    private final Neo4jSyncService service;

    public DesktopNeo4jController(Neo4jSyncService service) { this.service = service; }

    public void register(Javalin app) {
        app.get("/desktop/ontology/neo4j/status", ctx -> respond(ctx, service::status));
        app.post("/desktop/ontology/neo4j/sync", ctx -> respond(ctx, () -> {
            service.sync(confirmedTarget(ctx));
            return service.status();
        }));
    }
    private static String confirmedTarget(Context ctx) {
        if (ctx.body().length() > 2048) throw new IllegalArgumentException("neo4j.confirmationRequired");
        try {
            JsonNode body = JSON.readTree(ctx.body());
            if (body == null || !body.isObject() || body.size() != 1) throw new IllegalArgumentException();
            JsonNode fingerprint = body.get("confirmedTarget");
            if (fingerprint == null || !fingerprint.isTextual() || fingerprint.asText().isBlank()
                    || fingerprint.asText().length() > 256) throw new IllegalArgumentException();
            return fingerprint.asText();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("neo4j.confirmationRequired");
        }
    }
    private void respond(Context ctx, Supplier<Map<String, Object>> action) {
        ctx.header("Cache-Control", "no-store");
        try { ctx.json(action.get()); }
        catch (RuntimeException failure) {
            String code = ERRORS.getOrDefault(failure.getMessage() == null ? "" : failure.getMessage(), "unavailable");
            int status = switch (code) {
                case "running", "confirmation", "disabled" -> 409;
                case "invalid", "credentials", "capacity" -> 400;
                default -> 503;
            };
            // Never return/log driver exception messages, query values, credentials or user data.
            ctx.status(status).json(Map.of("errorCode", "neo4j.error." + code, "error", "Neo4j sync unavailable"));
        }
    }
}
