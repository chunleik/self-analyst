package com.selfanalyst.llm.settings;

import com.selfanalyst.config.LlmSettings;
import java.io.IOException;
import java.net.URI;
import java.util.*;

public final class LlmSettingsService {
    private static final Map<String, String> FIELDS = Map.of(
            "baseUrl", "llm.base-url", "model", "llm.model",
            "temperature", "llm.temperature", "maxTokens", "llm.max-tokens");
    private final LlmSettingsRepository repository;
    public LlmSettingsService(LlmSettingsRepository repository) { this.repository = repository; }

    public record FieldState(Object effectiveValue, String savedValue, String source) {}
    public record CredentialState(boolean configured, String source, boolean hasUserOverride) {}
    public record Snapshot(String protocol, Map<String, FieldState> fields,
                           CredentialState credential, Map<String, Object> runtime) {}
    public record Credential(String action, String value) {
        @Override public String toString() { return "Credential[redacted]"; }
        void apply(Properties user) {
            switch (action) {
                case "replace" -> user.setProperty("llm.api-key", value);
                case "clear" -> user.setProperty("llm.api-key", "");
                case "reset" -> user.remove("llm.api-key");
                default -> { }
            }
        }
    }

    public Snapshot read() throws IOException {
        synchronized (repository.commitLock()) {
            Properties user = repository.read();
            var resolved = repository.resolve(user);
            var settings = LlmSettings.from(resolved.config());
            var fields = new LinkedHashMap<String, FieldState>();
            FIELDS.forEach((name, key) -> {
                Object value = switch (name) {
                    case "temperature" -> settings.temperature();
                    case "maxTokens" -> settings.maxTokens();
                    default -> resolved.values().get(key).value();
                };
                fields.put(name, new FieldState(value, user.getProperty(key), resolved.values().get(key).source()));
            });
            return new Snapshot("openai-completions", fields,
                    new CredentialState(settings.available(), resolved.values().get("llm.api-key").source(),
                            user.containsKey("llm.api-key")), repository.runtime());
        }
    }

    public Map<String, Object> save(Map<String, Object> request) throws IOException {
        only(request, Set.of("updates", "reset", "credential"));
        Map<String, Object> updates = request.containsKey("updates") ? object(request.get("updates")) : Map.of();
        only(updates, FIELDS.keySet());
        var changes = new LinkedHashMap<String, String>();
        updates.forEach((name, value) -> changes.put(FIELDS.get(name), normalize(name, value)));
        if (request.containsKey("reset")) {
            if (!(request.get("reset") instanceof List<?> reset)) throw invalid("invalid_request");
            for (Object name : reset) {
                if (!(name instanceof String field) || !FIELDS.containsKey(field)
                        || changes.containsKey(FIELDS.get(field))) throw invalid("invalid_request");
                changes.put(FIELDS.get(field), null);
            }
        }
        Credential credential = credential(request);
        if (!credential.action().equals("keep")) changes.put("llm.api-key", switch (credential.action()) {
            case "replace" -> credential.value(); case "clear" -> ""; default -> null;
        });
        synchronized (repository.commitLock()) {
            Properties proposed = repository.read();
            changes.forEach((key, value) -> { if (value == null) proposed.remove(key); else proposed.setProperty(key, value); });
            validate(LlmSettings.from(repository.resolve(proposed).config()));
            var result = new LinkedHashMap<String, Object>(repository.save(changes).payload());
            result.put("settings", read());
            return result;
        }
    }

    public LlmSettings connection(Map<String, Object> request, boolean discovery) throws IOException {
        only(request, discovery ? Set.of("baseUrl", "credential", "reset") : Set.of("baseUrl", "model", "credential", "reset"));
        synchronized (repository.commitLock()) {
            Properties user = repository.read();
            var saved = LlmSettings.from(repository.resolve(user).config());
            if (request.containsKey("reset")) {
                if (!(request.get("reset") instanceof List<?> reset)) throw invalid("invalid_request");
                for (var field : reset) {
                    if (!(field instanceof String name) || !FIELDS.containsKey(name) || request.containsKey(name))
                        throw invalid("invalid_request");
                    user.remove(FIELDS.get(name));
                }
            }
            var draft = LlmSettings.from(repository.resolve(user).config());
            String base = request.containsKey("baseUrl") ? baseUrl(request.get("baseUrl")) : baseUrl(draft.baseUrl());
            String model = discovery ? draft.model() : request.containsKey("model")
                    ? string(request.get("model"), "model") : draft.model();
            Credential credential = credential(request);
            if (!credential.action().equals("replace") && !base.equals(baseUrl(saved.baseUrl())))
                throw invalid("credential_required");
            credential.apply(user);
            return new LlmSettings(repository.resolve(user).config().llmApiKey(), base, model, 0, 16);
        }
    }

    public static List<Map<String, String>> presets() {
        return List.of(Map.of("id", "openai", "name", "OpenAI", "baseUrl", "https://api.openai.com/v1"),
                Map.of("id", "deepseek", "name", "DeepSeek", "baseUrl", "https://api.deepseek.com/v1"),
                Map.of("id", "openrouter", "name", "OpenRouter", "baseUrl", "https://openrouter.ai/api/v1"),
                Map.of("id", "custom", "name", "custom", "baseUrl", ""));
    }

    private static String normalize(String name, Object value) {
        if (name.equals("baseUrl")) return baseUrl(value);
        if (name.equals("model")) return string(value, name);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw invalid(name);
        double number = n.doubleValue();
        if (name.equals("temperature")) {
            if (number < 0 || number > 2) throw invalid(name);
            return Double.toString(number);
        }
        if (number < 0 || number > Integer.MAX_VALUE || number != Math.rint(number)) throw invalid(name);
        return Integer.toString(n.intValue());
    }
    private static void validate(LlmSettings settings) {
        baseUrl(settings.baseUrl());
        string(settings.model(), "model");
        Properties properties = new Properties(); properties.putAll(settings.properties());
        try { LlmSettings.validate(properties); } catch (IllegalArgumentException ignored) { throw invalid("invalid_request"); }
    }
    public static String baseUrl(Object value) {
        String input = string(value, "baseUrl");
        try {
            URI uri = URI.create(input);
            if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw invalid("baseUrl");
            return input.replaceAll("/+$", "");
        } catch (IllegalArgumentException ignored) { throw invalid("baseUrl"); }
    }
    private static String string(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > 2048) throw invalid(field);
        return s.trim();
    }
    static Credential credential(Map<String, Object> request) {
        if (!request.containsKey("credential")) return new Credential("keep", null);
        var input = object(request.get("credential"));
        only(input, Set.of("action", "value"));
        Object action = input.getOrDefault("action", "keep");
        if (!(action instanceof String a) || !Set.of("keep", "replace", "clear", "reset").contains(a))
            throw invalid("invalid_request");
        if (!a.equals("replace")) {
            if (input.containsKey("value")) throw invalid("invalid_request");
            return new Credential(a, null);
        }
        String value = string(input.get("value"), "credential");
        if (!value.chars().allMatch(c -> c >= 33 && c <= 126)) throw invalid("credential");
        return new Credential(a, value);
    }
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> m) || m.keySet().stream().anyMatch(k -> !(k instanceof String)))
            throw invalid("invalid_request");
        var result = new LinkedHashMap<String, Object>(); m.forEach((k, v) -> result.put((String) k, v)); return result;
    }
    private static void only(Map<String, ?> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) throw invalid("unsupported_field");
    }
    static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
