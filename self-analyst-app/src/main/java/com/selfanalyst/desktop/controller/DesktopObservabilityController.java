package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.selfanalyst.observability.*;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.util.*;

/** Developer-only settings still require the authenticated managed desktop transport. */
public final class DesktopObservabilityController {
    private static final ObjectMapper JSON =
            DesktopChatJson.MAPPER
                    .copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final ObservabilityService service;

    public DesktopObservabilityController(ObservabilityService service) {
        this.service = service;
    }

    public void register(Javalin app) {
        String base = "/desktop/observability";
        app.get(base, ctx -> run(ctx, () -> ctx.json(service.snapshot())));
        app.put(
                base,
                ctx ->
                        run(
                                ctx,
                                () -> {
                                    var body = body(ctx, "expectedRevision", "config");
                                    service.save(
                                            revision(body),
                                            JSON.treeToValue(
                                                    required(body, "config"),
                                                    ObservabilityConfig.class));
                                    ctx.json(service.snapshot());
                                }));
        app.post(
                base + "/credential",
                ctx ->
                        run(
                                ctx,
                                () -> {
                                    var body = body(ctx, "expectedRevision", "secret");
                                    service.credential(
                                            revision(body), text(body, "secret").toCharArray());
                                    ctx.json(service.snapshot());
                                }));
        app.post(
                base + "/preview",
                ctx ->
                        run(
                                ctx,
                                () -> {
                                    var body = body(ctx, "expectedRevision");
                                    ctx.json(service.preview(revision(body)));
                                }));
        app.post(
                base + "/enable",
                ctx ->
                        run(
                                ctx,
                                () -> {
                                    var body =
                                            body(
                                                    ctx,
                                                    "expectedRevision",
                                                    "disclosureVersion",
                                                    "confirmationToken");
                                    var v = required(body, "disclosureVersion");
                                    if (!v.isInt()) throw new IllegalArgumentException();
                                    service.enable(
                                            revision(body),
                                            v.intValue(),
                                            text(body, "confirmationToken"));
                                    ctx.json(service.snapshot());
                                }));
        app.post(
                base + "/disable",
                ctx ->
                        run(
                                ctx,
                                () -> {
                                    var body = body(ctx, "expectedRevision");
                                    service.disable(revision(body));
                                    ctx.json(service.snapshot());
                                }));
        app.post(
                base + "/disconnect",
                ctx ->
                        run(
                                ctx,
                                () -> {
                                    var body = body(ctx, "expectedRevision");
                                    service.disconnect(revision(body));
                                    ctx.json(service.snapshot());
                                }));
        app.post(
                base + "/test",
                ctx ->
                        run(
                                ctx,
                                () -> {
                                    var body = body(ctx, "expectedRevision", "confirmationToken");
                                    boolean accepted =
                                            service.test(
                                                    revision(body),
                                                    text(body, "confirmationToken"));
                                    var result = new LinkedHashMap<>(service.snapshot());
                                    result.put("testAccepted", accepted);
                                    ctx.json(result);
                                }));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws IOException;
    }

    private static void run(Context ctx, Action action) {
        ctx.header("Cache-Control", "no-store");
        if (!Boolean.TRUE.equals(ctx.attribute("selfanalyst.managedDesktop"))) {
            ctx.status(403).json(Map.of("error", "managed_desktop_required"));
            return;
        }
        try {
            action.run();
        } catch (IOException | RuntimeException failure) {
            String message = Objects.toString(failure.getMessage(), "");
            Set<String> safe =
                    Set.of(
                            "revision_conflict",
                            "disclosure_required",
                            "persistent_credentials_unavailable",
                            "authentication_required",
                            "incomplete_config",
                            "config_unavailable",
                            "outcome_unknown",
                            "invalid_endpoint",
                            "invalid_ca_certificate",
                            "endpoint_changed",
                            "endpoint_resolution_failed",
                            "endpoint_busy",
                            "timeout",
                            "tls_failure",
                            "redirect_rejected",
                            "receiver_rejected",
                            "receiver_partial_rejection",
                            "destination_confirmation_required",
                            "cancelled");
            String code = safe.contains(message) ? message : "observability_operation_failed";
            ctx.status(code.equals("revision_conflict") ? 409 : 400).json(Map.of("error", code));
        }
    }

    private static JsonNode body(Context ctx, String... fields) throws IOException {
        var node = JSON.readTree(DesktopChatJson.readBoundedBody(ctx));
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        var allowed = Set.of(fields);
        node.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!allowed.contains(k)) throw new IllegalArgumentException();
                        });
        return node;
    }

    private static JsonNode required(JsonNode node, String key) {
        if (!node.hasNonNull(key)) throw new IllegalArgumentException();
        return node.get(key);
    }

    private static String text(JsonNode node, String key) {
        var v = required(node, key);
        if (!v.isTextual()) throw new IllegalArgumentException();
        return v.textValue();
    }

    private static long revision(JsonNode node) {
        var v = required(node, "expectedRevision");
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0)
            throw new IllegalArgumentException();
        return v.longValue();
    }
}
