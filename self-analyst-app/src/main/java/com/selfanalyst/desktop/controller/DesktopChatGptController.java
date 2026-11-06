package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.chatgpt.ChatGptModelCatalog;
import com.selfanalyst.chatgpt.ChatGptResponsesModel;
import com.selfanalyst.chatgpt.ChatGptSessionService;
import com.selfanalyst.llm.settings.LlmSettingsService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 这些路由复用 /desktop/* 的认证、来源和回环边界；回调由临时独立监听器处理。 */
public final class DesktopChatGptController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> AUTH_ERRORS = Set.of(
            "chatgpt_storage_unavailable", "chatgpt_unknown_account", "chatgpt_login_start_failed",
            "chatgpt_sign_in_required", "chatgpt_plan_permission_required", "chatgpt_refresh_failed",
            "chatgpt_refresh_not_yet_allowed", "chatgpt_network_error", "chatgpt_request_interrupted", "chatgpt_request_timeout",
            "chatgpt_invalid_identity", "chatgpt_identity_unavailable", "chatgpt_identity_mismatch", "chatgpt_client_mismatch",
            "chatgpt_authorization_declined", "chatgpt_code_exchange_failed", "chatgpt_invalid_callback",
            "chatgpt_invalid_response", "chatgpt_login_cancelled", "chatgpt_login_expired",
            "chatgpt_discovery_failed", "chatgpt_service_closed", "chatgpt_response_too_large");
    private final ChatGptSessionService sessions;
    private final com.selfanalyst.chatgpt.ChatGptInferenceGate gate;
    public DesktopChatGptController(ChatGptSessionService sessions, com.selfanalyst.chatgpt.ChatGptInferenceGate gate) {
        this.sessions = sessions; this.gate = gate;
    }

    public void status(Context ctx) { respond(ctx, this::statusPayload); }
    private Map<String, Object> statusPayload() {
        var result = new LinkedHashMap<String, Object>(sessions.status());
        var accounts = new ArrayList<Map<String, Object>>();
        if (result.get("accounts") instanceof List<?> saved) {
            for (Object value : saved) {
                var entry = new LinkedHashMap<String, Object>(LlmSettingsService.object(value));
                entry.put("usagePaused", !gate.available(String.valueOf(entry.get("id"))));
                accounts.add(entry);
            }
        }
        result.put("accounts", accounts);
        result.put("usagePaused", !gate.available(String.valueOf(result.get("activeAccountId"))));
        return result;
    }
    public void login(Context ctx) {
        respond(ctx, () -> { var request = body(ctx, Set.of("accountId")); return sessions.startLogin(optional(request, "accountId")); });
    }
    public void cancel(Context ctx) {
        respond(ctx, () -> {
            var request = body(ctx, Set.of("attemptId"));
            sessions.cancelLogin(required(request, "attemptId"));
            return statusPayload();
        });
    }
    public void logout(Context ctx) {
        respond(ctx, () -> { var request = body(ctx, Set.of("accountId")); return sessions.signOut(required(request, "accountId")); });
    }
    public void models(Context ctx) {
        respond(ctx, () -> {
            var request = body(ctx, Set.of("accountId"));
            String account = required(request, "accountId");
            if (!sessions.isConnected(account)) return Map.of("ok", false, "code", "authentication", "models", List.of());
            return new ChatGptModelCatalog().fetch(() -> sessions.accessToken(account));
        });
    }
    public void test(Context ctx) {
        respond(ctx, () -> {
            var request = body(ctx, Set.of("accountId", "model"));
            return testConnection(required(request, "accountId"), required(request, "model"));
        });
    }

    Map<String, Object> testConnection(String account, String model) {
        long start = System.nanoTime();
        if (!sessions.isConnected(account)) return result(start, "authentication");
        try (var adapter = new ChatGptResponsesModel(model, () -> sessions.accessToken(account))) {
            var pending = adapter.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("Reply OK.").build()), List.of(), null)
                    .collectList().toFuture();
            try {
                var responses = pending.get(45, TimeUnit.SECONDS);
                boolean generated = responses.stream().filter(Objects::nonNull)
                        .filter(response -> response.getContent() != null)
                        .flatMap(response -> response.getContent().stream())
                        .anyMatch(block -> block instanceof TextBlock text && text.getText() != null && !text.getText().isBlank());
                if (generated) gate.resume(account);
                return result(start, generated ? "success" : "protocol");
            } catch (TimeoutException failure) { pending.cancel(true); return result(start, "timeout"); }
            catch (InterruptedException failure) {
                pending.cancel(true); Thread.currentThread().interrupt(); return result(start, "network");
            } catch (ExecutionException failure) { gate.observe(account, failure); return result(start, classify(failure)); }
        } catch (RuntimeException failure) { gate.observe(account, failure); return result(start, classify(failure)); }
    }
    private static String classify(Throwable failure) {
        for (int n = 0; failure != null && n < 12; n++, failure = failure.getCause()) {
            if (failure instanceof ChatGptResponsesModel.ChatGptResponsesException response) {
                return switch (response.code()) {
                    case AUTHENTICATION -> "authentication"; case ACCESS_DENIED -> "permission";
                    case RATE_LIMIT -> "rate_limit"; case TIMEOUT -> "timeout";
                    case INVALID_REQUEST -> "parameters"; case PROTOCOL -> "protocol";
                    case HTTP -> "http"; case TRANSPORT, CANCELLED -> "network";
                };
            }
        }
        return "network";
    }
    private static Map<String, Object> result(long start, String code) {
        return Map.of("ok", code.equals("success"), "code", code,
                "latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
    private static Map<String, Object> body(Context ctx, Set<String> allowed) {
        try {
            var value = LlmSettingsService.object(JSON.readValue(ctx.body(), Object.class));
            if (!allowed.containsAll(value.keySet())) throw new IllegalArgumentException();
            return value;
        } catch (Exception invalid) { throw new IllegalArgumentException("invalid_request"); }
    }
    private static String optional(Map<String, Object> request, String name) {
        if (!request.containsKey(name) || request.get(name) == null) return null;
        return required(request, name);
    }
    private static String required(Map<String, Object> request, String name) {
        if (!(request.get(name) instanceof String text) || text.isBlank() || text.length() > 256
                || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid_request");
        return text;
    }
    @FunctionalInterface private interface Action { Object run() throws Exception; }
    private void respond(Context ctx, Action action) {
        ctx.header("Cache-Control", "no-store");
        try { ctx.json(action.run()); }
        catch (IllegalArgumentException invalid) { ctx.status(400).json(Map.of("ok", false, "code", "invalid_request")); }
        catch (Exception unavailable) {
            // Even a local filesystem/network exception can contain credential material. Never echo it.
            String code = AUTH_ERRORS.contains(String.valueOf(unavailable.getMessage()))
                    ? unavailable.getMessage() : "chatgpt_unavailable";
            ctx.status(400).json(Map.of("ok", false, "code", code, "errorCode", code));
        }
    }
}
