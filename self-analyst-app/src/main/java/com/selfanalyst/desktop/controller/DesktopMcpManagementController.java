package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.selfanalyst.mcp.McpMailManagement;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Separate configuration and one-time decisions require the managed desktop credential. */
public final class DesktopMcpManagementController {
    private static final int MAX_BODY = 65536;
    private static final ObjectReader REQUEST = DesktopChatJson.MAPPER.readerFor(JsonNode.class)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> SAFE = Set.of("revision_conflict", "configuration_unavailable",
            "persistent_credentials_unavailable", "account_disabled", "account_not_found",
            "smtp_credential_required", "invalid_settings", "invalid_request", "approval_expired_or_changed",
            "credential_limit", "invalid_credential", "credential_write_failed", "credential_verification_failed");
    private final McpMailManagement manager;

    public DesktopMcpManagementController(McpMailManagement manager) {
        this.manager = manager;
    }

    public void register(Javalin app) {
        String base = "/desktop/mcp-management";
        app.get(base, ctx -> run(ctx, () -> ctx.json(manager.status(ctx.req().getLocalPort()))));
        app.put(base, ctx -> run(ctx, () -> {
            JsonNode request = body(ctx, Set.of("revision", "accounts"));
            JsonNode accounts = request.path("accounts");
            if (!accounts.isArray() || accounts.size() > 64) throw new IOException("invalid_request");
            var settings = new ArrayList<McpMailManagement.AccountSetting>();
            for (JsonNode account : accounts) {
                fields(account, Set.of("id", "expose", "allowDraft", "allowSend"));
                if (!account.path("id").isTextual()) throw new IOException("invalid_request");
                settings.add(new McpMailManagement.AccountSetting(account.path("id").textValue(),
                        flag(account, "expose"), flag(account, "allowDraft"), flag(account, "allowSend")));
            }
            manager.save(revision(request), settings);
            ctx.json(manager.status(ctx.req().getLocalPort()));
        }));
        app.post(base + "/token", ctx -> run(ctx, () -> {
            JsonNode request = body(ctx, Set.of("revision"));
            String token = manager.rotate(revision(request));
            ctx.json(Map.of("token", token, "status", manager.status(ctx.req().getLocalPort())));
        }));
        app.put(base + "/smtp/{id}", ctx -> run(ctx, () -> {
            JsonNode request = body(ctx, Set.of("revision", "secret"));
            JsonNode secret = request.path("secret");
            if (!secret.isTextual() || secret.textValue().isBlank() || secret.textValue().length() > 2048)
                throw new IOException("invalid_request");
            manager.smtp(revision(request), ctx.pathParam("id"), secret.textValue().toCharArray());
            ctx.json(manager.status(ctx.req().getLocalPort()));
        }));
        app.post(base + "/approval/{id}", ctx -> run(ctx, () -> {
            JsonNode request = body(ctx, Set.of("allowed"));
            if (!manager.decide(ctx.pathParam("id"), flag(request, "allowed")))
                throw new IOException("approval_expired_or_changed");
            ctx.json(Map.of("accepted", true));
        }));
    }

    private static void run(Context ctx, Action action) {
        ctx.header("Cache-Control", "no-store");
        String origin = ctx.header("Origin");
        String fetchSite = ctx.header("Sec-Fetch-Site");
        if (!Boolean.TRUE.equals(ctx.attribute("selfanalyst.managedDesktop"))
                || origin != null && !origin.equalsIgnoreCase("http://" + ctx.header("Host"))
                || fetchSite != null && !Set.of("same-origin", "none").contains(fetchSite)) {
            ctx.status(403).json(Map.of("error", "managed_desktop_required"));
            return;
        }
        try {
            action.run();
        } catch (IOException invalid) {
            String code = invalid.getMessage();
            int status = "revision_conflict".equals(code) || "approval_expired_or_changed".equals(code) ? 409 : 400;
            ctx.status(status).json(Map.of("error", code != null && SAFE.contains(code) ? code : "mcp_operation_failed"));
        } catch (RuntimeException unavailable) {
            ctx.status(503).json(Map.of("error", "mcp_operation_failed"));
        }
    }

    private static JsonNode body(Context ctx, Set<String> expected) throws IOException {
        if (ctx.contentType() == null || !ctx.contentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
            throw new IOException("invalid_request");
        byte[] bytes = ctx.req().getInputStream().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new IOException("invalid_request");
        try {
            JsonNode parsed = REQUEST.readValue(bytes);
            fields(parsed, expected);
            return parsed;
        } catch (IOException invalid) {
            throw new IOException("invalid_request");
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void fields(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject() || node.size() != expected.size())
            throw new IOException("invalid_request");
        for (String field : expected) if (!node.hasNonNull(field)) throw new IOException("invalid_request");
    }

    private static long revision(JsonNode request) throws IOException {
        JsonNode rev = request.path("revision");
        if (!rev.isIntegralNumber() || !rev.canConvertToLong() || rev.longValue() < 0)
            throw new IOException("invalid_request");
        return rev.longValue();
    }

    private static boolean flag(JsonNode request, String name) throws IOException {
        JsonNode flag = request.path(name);
        if (!flag.isBoolean()) throw new IOException("invalid_request");
        return flag.booleanValue();
    }

    @FunctionalInterface
    private interface Action { void run() throws IOException; }
}
