package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.integrations.IntegrationStore;
import com.selfanalyst.mcp.McpManager;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Sensitive setup is available only through the authenticated managed desktop, not legacy localhost. */
public final class DesktopIntegrationController {
    private static final ObjectMapper JSON = DesktopChatJson.MAPPER.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final McpManager manager;
    private final Supplier<String> modelTarget;
    public DesktopIntegrationController(McpManager manager, Supplier<String> modelTarget) {
        this.manager = manager; this.modelTarget = modelTarget;
    }
    public void register(Javalin app) {
        String base = "/desktop/integrations";
        app.get(base, ctx -> run(ctx, () -> snapshot(ctx)));
        app.put(base, ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "expectedRevision", "config");
            manager.save(revision(body), IntegrationStore.parse(required(body,"config").toString())); snapshot(ctx);
        }));
        app.post(base + "/credential", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "kind", "id", "secret", "persist", "expectedRevision");
            String secret = string(body,"secret");
            if (secret.isEmpty() || secret.length() > 2048) throw new IllegalArgumentException();
            manager.credential(revision(body), string(body,"id"), secret.toCharArray(), bool(body,"persist")); snapshot(ctx);
        }));
        app.post(base + "/disconnect", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "kind", "id", "expectedRevision");
            manager.disconnect(revision(body), string(body,"id")); snapshot(ctx);
        }));
        app.post(base + "/delete", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "kind", "id", "expectedRevision");
            synchronized (manager) {
                manager.delete(revision(body), string(body, "kind"), string(body, "id"));
                snapshot(ctx);
            }
        }));
        app.post(base + "/test", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "kind", "id"); manager.test(string(body,"kind"), string(body,"id")); snapshot(ctx);
        }));
        app.post(base + "/discover", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "serverId"); manager.discover(string(body,"serverId")); snapshot(ctx);
        }));
        app.post(base + "/tools/approve", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "serverId", "toolName", "digest", "expectedRevision");
            manager.approveTool(revision(body),string(body,"serverId"),string(body,"toolName"),string(body,"digest")); snapshot(ctx);
        }));
        app.get(base + "/approvals", ctx -> run(ctx, () -> ctx.json(Map.of("approvals",manager.approvals().pending(ctx.queryParam("sessionId"))))));
        app.post(base + "/approvals/{id}", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "decision", "sessionId", "turnId");
            String decision = string(body,"decision");
            if (!Set.of("approve", "reject").contains(decision)) throw new IllegalArgumentException();
            if (!manager.approvals().decide(ctx.pathParam("id"),string(body,"sessionId"),string(body,"turnId"),decision.equals("approve"))) {
                ctx.status(409).json(Map.of("error","approval_expired_or_changed")); return;
            }
            ctx.json(Map.of("accepted",true));
        }));
        app.get(base + "/export", ctx -> run(ctx, () -> ctx.contentType("application/json").result(manager.exportMetadata())));
        app.post(base + "/import", ctx -> run(ctx, () -> {
            JsonNode body = body(ctx, "expectedRevision", "config");
            manager.importMetadata(revision(body),required(body,"config").toString()); snapshot(ctx);
        }));
    }
    private void snapshot(Context ctx) {
        Map<String, Object> snapshot;
        synchronized (manager) {
            snapshot = Map.of("config", manager.config(), "statuses", manager.statuses(),
                    "persistentCredentialsSupported", com.sun.jna.Platform.isWindows(),
                    "credentialCleanupPending", manager.credentialCleanupPending(),
                    "modelTarget", Objects.toString(modelTarget.get(), ""), "legacyWebsearch", manager.legacyStatus());
        }
        ctx.json(snapshot);
    }
    @FunctionalInterface private interface Action { void run() throws IOException; }
    private static void run(Context ctx, Action action) {
        ctx.header("Cache-Control", "no-store");
        if (!Boolean.TRUE.equals(ctx.attribute("selfanalyst.managedDesktop"))) {
            ctx.status(403).json(Map.of("error","managed_desktop_required")); return;
        }
        try { action.run(); }
        catch (IOException failure) {
            ctx.status("revision_conflict".equals(failure.getMessage()) ? 409 : 400)
                    .json(Map.of("error", "revision_conflict".equals(failure.getMessage()) ? "revision_conflict" : "integration_operation_failed"));
        } catch (com.selfanalyst.mail.MailException failure) {
            ctx.status(400).json(Map.of("error", failure.code().name().toLowerCase(java.util.Locale.ROOT)));
        } catch (SecurityException failure) { ctx.status(403).json(Map.of("error", "integration_authorization_required")); }
        catch (RuntimeException failure) { ctx.status(400).json(Map.of("error", "integration_operation_failed")); }
    }
    private static JsonNode body(Context ctx, String... allowed) throws IOException {
        var node = JSON.readTree(DesktopChatJson.readBoundedBody(ctx));
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        Set<String> fields = Set.of(allowed);
        node.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw new IllegalArgumentException(); });
        return node;
    }
    private static JsonNode required(JsonNode node, String name) { if (!node.hasNonNull(name)) throw new IllegalArgumentException(); return node.get(name); }
    private static String string(JsonNode node, String name) { var value = required(node,name); if (!value.isTextual()) throw new IllegalArgumentException(); return value.textValue(); }
    private static boolean bool(JsonNode node, String name) { var value = required(node,name); if (!value.isBoolean()) throw new IllegalArgumentException(); return value.booleanValue(); }
    private static long revision(JsonNode node) { var value = required(node,"expectedRevision"); if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0) throw new IllegalArgumentException(); return value.longValue(); }
}
