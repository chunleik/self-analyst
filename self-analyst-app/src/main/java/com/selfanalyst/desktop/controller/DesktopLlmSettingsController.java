package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.ConfigApplicationService;
import com.selfanalyst.llm.settings.*;
import io.javalin.http.Context;
import java.util.Map;
import java.util.Set;

public final class DesktopLlmSettingsController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CODES = Set.of("baseUrl", "model", "temperature", "maxTokens", "credential",
            "invalid_request", "unsupported_field", "unsupported_toml_edit", "credential_required");
    private final LlmSettingsService service;
    private final LlmConnectionProbe probe;
    public DesktopLlmSettingsController(ConfigApplicationService configuration) {
        service = new LlmSettingsService(new TomlLlmSettingsRepository(configuration));
        probe = new LlmConnectionProbe();
    }
    public void get(Context ctx) { respond(ctx, service::read); }
    public void presets(Context ctx) { ctx.json(Map.of("presets", LlmSettingsService.presets())); }
    public void put(Context ctx) { respond(ctx, () -> service.save(body(ctx))); }
    public void test(Context ctx) { respond(ctx, () -> probe.run(service.connection(body(ctx), false), false)); }
    public void discover(Context ctx) { respond(ctx, () -> probe.run(service.connection(body(ctx), true), true)); }
    private Map<String, Object> body(Context ctx) {
        try { return LlmSettingsService.object(JSON.readValue(ctx.body(), Object.class)); }
        catch (Exception ignored) { throw new IllegalArgumentException("invalid_request"); }
    }
    @FunctionalInterface private interface Action { Object run() throws Exception; }
    private void respond(Context ctx, Action action) {
        try { ctx.json(action.run()); }
        catch (IllegalArgumentException invalid) {
            String code = invalid.getMessage() != null && CODES.contains(invalid.getMessage()) ? invalid.getMessage() : "invalid_request";
            ctx.status(400).json(DesktopErrors.payload(ctx, "error.llmSettings." + code, Map.of()));
        } catch (Exception failure) {
            ctx.status(500).json(DesktopErrors.payload(ctx, "error.llmSettings.save", Map.of()));
        }
    }
}
