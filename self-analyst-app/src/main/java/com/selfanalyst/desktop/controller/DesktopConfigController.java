package com.selfanalyst.desktop.controller;

import com.selfanalyst.events.raw.RawPartitionCatalog;
import com.selfanalyst.config.Config;
import com.selfanalyst.config.ConfigApplicationService;
import com.selfanalyst.config.ConfigPolicy;
import com.selfanalyst.config.DeprecatedKeys;
import com.selfanalyst.config.RawConfigValidator;
import com.selfanalyst.config.SupportedKeys;
import com.selfanalyst.config.TomlSupport;
import com.selfanalyst.config.TomlValidationException;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.file.FileFilterConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Config tab endpoints: read, write, and test configuration.
 *
 * <pre>
 *   GET  /desktop/config           → all config sections with metadata
 *   PUT  /desktop/config           → save user overrides
 *   POST /desktop/config/test-llm  → test LLM connectivity
 * </pre>
 */
public class DesktopConfigController {

    private static final Logger log = LoggerFactory.getLogger(DesktopConfigController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE =
            new TypeReference<>() {};



    // Supported config keys, defaults and declared types live in the shared
    // com.selfanalyst.config.SupportedKeys (single source of truth for the raw-edit
    // template, unknown-key detection, type validation, and TOML generation).

    private final Config config;
    private final ConfigApplicationService configuration;
    private final UserConfigStore userStore;

    public DesktopConfigController(Config config, UserConfigStore userStore) {
        this.config = config;
        this.configuration = userStore.application(config);
        this.userStore = userStore;
    }

    /**
     * GET /desktop/config
     */
    public void getConfig(Context ctx) {
        ctx.json(configPayload());
    }

    Map<String, Object> configPayload() {
        synchronized (configuration.commitLock()) {
        var snapshot = configuration.saved();
        Properties defaults = snapshot.properties();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("llm", buildLlmSection(defaults));
        response.put("aw", buildAwSection(defaults));
        response.put("collection", buildCollectionSection(defaults));
        response.put("agent", buildAgentSection(defaults));
        response.put("desktop", buildDesktopSection(defaults));
        response.put("embedding", buildEmbeddingSection(defaults));
        response.put("websearch", buildWebSearchSection(defaults));
        Properties user = userStore.loadUser();
        response.forEach((section, fields) -> enrichFields(section, "", fields, snapshot, user));
        return response;
        }
    }

    @SuppressWarnings("unchecked")
    private void enrichFields(String section, String path, Object node,
                              com.selfanalyst.config.ConfigResolver.Snapshot snapshot, Properties user) {
        if (!(node instanceof Map<?, ?> raw)) return;
        Map<String, Object> fields = (Map<String, Object>) raw;
        if (!fields.containsKey("effectiveValue")) {
            fields.forEach((name, child) -> enrichFields(section,
                    path.isEmpty() ? name : path + "." + name, child, snapshot, user));
            return;
        }
        String key = mapStructuredKey(section, path, sectionToPrefix().get(section));
        var value = snapshot.values().get(key);
        if (value == null) return;
        boolean sensitive = com.selfanalyst.config.ConfigResolver.sensitive(key);
        String saved = user.getProperty(key);
        fields.put("savedValue", saved == null ? null : sensitive ? maskKey(saved) : saved);
        fields.put("source", value.source().equals("toml") ? "user_config" : value.source());
        fields.put("overridden", saved != null && !Objects.equals(saved, value.value()));
        fields.put("restartRequiredOnChange", ConfigPolicy.requiresRestart(key));
    }

    /**
     * PUT /desktop/config
     * Body: { "llm": {...}, "aw": {...}, ... }
     */
    public void putConfig(Context ctx) {
        try {
            Map<String, Object> body = MAPPER.readValue(ctx.body(), OBJECT_MAP_TYPE);
            StructuredSaveResult result = applyStructuredSave(body);
            ctx.json(result.result().payload());
        } catch (Throwable t) {
            log.error("配置保存失败 ({})", t.getClass().getSimpleName());
            try {
                String msg = "配置保存或应用失败";
                if (msg == null) msg = t.getClass().getName();
                ctx.status(500).result("{\"error\":\"Save config failed: " + escapeJson(msg) + "\"}").contentType("application/json");
            } catch (Throwable suppressed) {
                log.error("Error handler also failed", suppressed);
                try {
                    ctx.status(500).result("{\"error\":\"Internal error\"}").contentType("application/json");
                } catch (Throwable ignored) {}
            }
        }
    }

    record StructuredSaveResult(ConfigApplicationService.SaveResult result) {
        List<String> restartRequired() { return result.restartRequired(); }
    }

    StructuredSaveResult applyStructuredSave(Map<String, Object> body) throws IOException {
        Map<String, String> changes = new LinkedHashMap<>();
        for (var entry : sectionToPrefix().entrySet()) {
            Object sectionData = body.get(entry.getKey());
            if (!(sectionData instanceof Map<?, ?> sectionMap)) continue;
            Map<String, Object> flat = new LinkedHashMap<>();
            flattenStructuredSection("", sectionMap, flat);
            for (var kv : flat.entrySet()) {
                String key = mapStructuredKey(entry.getKey(), kv.getKey(), entry.getValue());
                if (DeprecatedKeys.contains(key) || !SupportedKeys.contains(key)) continue;
                changes.put(key, kv.getValue() == null ? "" : kv.getValue().toString());
            }
        }
        return new StructuredSaveResult(configuration.update(changes));
    }

    public void getEffectiveConfig(Context ctx) {
        ctx.json(configuration.effectivePayload());
    }

    private static Map<String, String> sectionToPrefix() {
        Map<String, String> sectionToPrefix = new LinkedHashMap<>();
        sectionToPrefix.put("llm", "llm.");
        sectionToPrefix.put("aw", "aw.");
        sectionToPrefix.put("collection", "aw.collection.");
        sectionToPrefix.put("agent", "agent.");
        sectionToPrefix.put("desktop", "desktop.");
        sectionToPrefix.put("embedding", "embedding.");
        sectionToPrefix.put("websearch", "websearch.");
        return sectionToPrefix;
    }

    private static void flattenStructuredSection(String prefix, Map<?, ?> input,
                                                 Map<String, Object> output) {
        for (var entry : input.entrySet()) {
            String entryKey = String.valueOf(entry.getKey());
            String key = prefix.isBlank() ? entryKey : prefix + "." + entryKey;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenStructuredSection(key, nested, output);
            } else {
                output.put(key, value);
            }
        }
    }

    private static String mapStructuredKey(String section, String rawKey, String prefix) {
        if (rawKey.startsWith(prefix)) {
            return rawKey;
        }
        return switch (section) {
            case "llm" -> mapKey(rawKey, prefix,
                    "apiKey", "api-key",
                    "baseUrl", "base-url",
                    "model", "model",
                    "temperature", "temperature");
            case "aw" -> mapKey(rawKey, prefix,
                    "mode", "mode",
                    "port", "port",
                    "dataDir", "data-dir");
            case "collection" -> "ocrEngine".equals(rawKey)
                    ? "aw.ocr.engine"
                    : mapKey(rawKey, prefix,
                            "window", "window",
                            "afk", "afk",
                            "content", "content");
            case "agent" -> mapKey(rawKey, prefix,
                    "summaryRefreshMinutes", "summaryRefreshMinutes",
                    "refresh_interval", "summaryRefreshMinutes",
                    "allowAgentTasks", "allowAgentTasks",
                    "allow_agent_tasks", "allowAgentTasks",
                    "cacheSummaries", "cacheSummaries",
                    "cache", "cacheSummaries");
            case "desktop" -> mapKey(rawKey, prefix,
                    "hideToTray", "hideToTray",
                    "hide_to_tray", "hideToTray",
                    "autoOpenWindow", "autoOpenWindow",
                    "auto_open", "autoOpenWindow",
                    "autoStartBackend", "autoStartBackend",
                    "auto_start", "autoStartBackend");
            case "embedding" -> mapKey(rawKey, prefix,
                    "embeddingEnabled", "enabled",
                    "embeddingBaseUrl", "base-url",
                    "embeddingBase_url", "base-url",
                    "embeddingApiKey", "api-key",
                    "embeddingApi_key", "api-key",
                    "embeddingModel", "model",
                    "embeddingDimensions", "dimensions",
                    "embeddingSendEncodingFormat", "send-encoding-format");
            case "websearch" -> mapKey(rawKey, prefix,
                    "webSearchEnabled", "enabled",
                    "webSearchMcpUrl", "mcp-url",
                    "webSearchApiKey", "api-key");
            default -> prefix + rawKey;
        };
    }

    private static String mapKey(String rawKey, String prefix, String... aliases) {
        for (int i = 0; i + 1 < aliases.length; i += 2) {
            if (aliases[i].equals(rawKey)) {
                return prefix + aliases[i + 1];
            }
        }
        return prefix + rawKey;
    }

    private static void addRestartIfNeeded(Set<String> restartKeys, String mappedKey) {
        if (ConfigPolicy.requiresRestart(mappedKey)) {
            restartKeys.add(mappedKey);
        }
    }

    private static boolean effectiveWouldChangeAfterRemoval(String mappedKey, String oldEffective) {
        String defaultValue = SupportedKeys.defaults().getOrDefault(mappedKey, "");
        return !Objects.equals(defaultValue, oldEffective);
    }

    // ── Raw text config (SPEC-TOML / SPEC-CFGUI) ─────────────────
    // The desktop "配置" modal edits the user config.toml file as plain text.
    // Logic lives in pure/package methods so it is unit-testable without mocking
    // Javalin Context; the endpoint methods only marshal ctx ↔ helpers.

    /** Wrap a flattened dotted-key map as {@code Properties} for the diff helpers. */
    private static Properties toProperties(Map<String, String> flat) {
        Properties p = new Properties();
        flat.forEach(p::setProperty);
        return p;
    }

    /** Keys whose value was added/removed/changed and that require a restart. */
    static List<String> computeRestartRequired(Properties oldP, Properties newP) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(oldP.stringPropertyNames());
        keys.addAll(newP.stringPropertyNames());
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (!ConfigPolicy.requiresRestart(key)) continue;
            if (!Objects.equals(oldP.getProperty(key), newP.getProperty(key))) {
                result.add(key);
            }
        }
        return result;
    }

    /** Keys present in the new text that are not in the supported whitelist. */
    static List<String> computeUnknownKeys(Properties newP) {
        List<String> result = new ArrayList<>();
        for (String key : newP.stringPropertyNames()) {
            if (!SupportedKeys.contains(key) && !DeprecatedKeys.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * A commented TOML template with section table headers listing supported keys
     * and their defaults, served when the user config file is empty/missing.
     * Delegates to the shared {@link TomlSupport#buildTemplate}. SPEC-TOML-API-001a,
     * SPEC-TOML-FMT-004.
     */
    static String buildTemplate() {
        return TomlSupport.buildTemplate(
                SupportedKeys.defaults(), SupportedKeys.types(), SupportedKeys.descriptions());
    }

    /** One supported key's reference info for the raw response. SPEC-TOML-API-001e. */
    record SupportedKeyInfo(String key, String type, String assignment) {}

    /** GET /desktop/config/raw response payload. */
    record RawConfigResponse(String text, String path, boolean exists,
                             List<SupportedKeyInfo> supportedKeys) {}

    /**
     * Compatibility-only list of supported keys as
     * {@code (key, type, defaultAssignment)}, in {@link SupportedKeys} declaration
     * order (SPEC-TOML-API-001e). Current desktop UI reads the complete template
     * directly; older clients may still consume this field.
     */
    static List<SupportedKeyInfo> supportedKeyInfos() {
        LinkedHashMap<String, TomlSupport.KeyType> types = SupportedKeys.types();
        List<SupportedKeyInfo> list = new ArrayList<>();
        for (var e : SupportedKeys.defaults().entrySet()) {
            TomlSupport.KeyType t = types.get(e.getKey());
            String typeName = (t == null ? "string" : t.name().toLowerCase());
            list.add(new SupportedKeyInfo(e.getKey(), typeName,
                    TomlSupport.emitAssignment(e.getKey(), e.getValue(), t)));
        }
        return list;
    }

    /**
     * Build the raw response: actual file text (not masked, not merged), or the
     * template when the file is empty/missing, plus the read-only supportedKeys list.
     * SPEC-CFGUI-API-001a, SPEC-CFGUI-DEC-002, SPEC-CFGUI-NON-004, SPEC-TOML-API-001e.
     */
    RawConfigResponse buildRawResponse() throws IOException {
        String raw = userStore.readRaw();
        boolean exists = !raw.isBlank();
        String text = exists ? raw : buildTemplate();
        return new RawConfigResponse(text, userStore.filePath().toString(), exists,
                supportedKeyInfos());
    }

    /** PUT /desktop/config/raw result payload. */
    record RawSaveResult(ConfigApplicationService.SaveResult result) {
        List<String> restartRequired() { return result.restartRequired(); }
        List<String> unknownKeys() { return result.unknownKeys(); }
    }

    /**
     * Validate as TOML v1.0 (syntax + structure + known-key types), then atomically
     * persist the submitted text verbatim. All validation happens before any disk
     * write, so invalid input never reaches the file. Throws
     * {@link TomlValidationException} on any validation failure.
     * SPEC-TOML-API-001b/c/d, SPEC-TOML-DEC-002.
     */
    RawSaveResult applyRawSave(String text) throws IOException {
        // Syntax + structure (throws with line/col); then lenient known-key types.
        LinkedHashMap<String, String> flat = TomlSupport.parseAndFlatten(text);
        List<String> typeViolations = TomlSupport.validateTypes(flat, SupportedKeys.types());
        if (!typeViolations.isEmpty()) {
            throw new TomlValidationException(typeViolations);
        }
        Properties newP = toProperties(flat);
        validateFileFilterSettings(newP);
        validateRawSettings(newP);
        return new RawSaveResult(configuration.saveRaw(text));
    }

    static void validateFileFilterSettings(Properties userProperties) {
        Properties effective = new Properties();
        SupportedKeys.defaults().forEach(effective::setProperty);
        if (userProperties != null) effective.putAll(userProperties);
        try {
            long maxKb = Long.parseLong(effective.getProperty("file.watch.maxFileSizeKb", "0"));
            FileFilterConfig.parse(maxKb,
                    FileFilterConfig.splitCsv(effective.getProperty("file.watch.excludeDirs", "")),
                    FileFilterConfig.splitCsv(effective.getProperty("file.watch.excludeGlobs", "")),
                    FileFilterConfig.splitCsv(effective.getProperty("file.watch.extensions", "")),
                    Boolean.parseBoolean(effective.getProperty(
                            "file.watch.respectGitIgnore", "true")));
        } catch (IllegalArgumentException invalidFilter) {
            throw new TomlValidationException(List.of(invalidFilter.getMessage()));
        }
    }

    static void validateRawSettings(Properties userProperties) {
        try {
            RawConfigValidator.validate(userProperties);
        } catch (IllegalArgumentException invalidRawConfig) {
            throw new TomlValidationException(List.of(invalidRawConfig.getMessage()));
        }
    }

    private void validateRawDirectoryChange(Properties newUserProperties) {
        Path current = config.awRawDir().toAbsolutePath().normalize();
        Path proposed = proposedRawDirectory(newUserProperties).toAbsolutePath().normalize();
        if (!current.equals(proposed) && RawPartitionCatalog.hasExistingPartitions(current)) {
            throw new TomlValidationException(List.of(
                    "aw.raw.dir 已有原始分区，普通配置保存不能修改；请使用独立的显式转存流程"));
        }
    }

    private Path proposedRawDirectory(Properties newUserProperties) {
        String envRawDir = System.getenv("AW_RAW_DIR");
        String configuredRawDir = newUserProperties.getProperty("aw.raw.dir");
        if (configuredRawDir != null && !configuredRawDir.isBlank()) {
            return Path.of(configuredRawDir.replace(
                    "${user.home}", System.getProperty("user.home")));
        }
        if (!newUserProperties.containsKey("aw.raw.dir")
                && envRawDir != null && !envRawDir.isBlank()) return Path.of(envRawDir);
        String envAwDataDir = System.getenv("AW_DATA_DIR");
        String awDataDir = newUserProperties.getProperty("aw.data-dir",
                envAwDataDir != null && !envAwDataDir.isBlank()
                        ? envAwDataDir : SupportedKeys.defaults().get("aw.data-dir"));
        return Path.of(awDataDir.replace(
                "${user.home}", System.getProperty("user.home"))).resolve("raw");
    }

    /**
     * GET /desktop/config/raw
     */
    public void getRawConfig(Context ctx) {
        try {
            ctx.json(buildRawResponse());
        } catch (Throwable t) {
            log.error("Failed to read raw config", t);
            String msg = "配置读写失败";
            ctx.status(500).result("{\"error\":\"Read raw config failed: " + escapeJson(msg) + "\"}")
                    .contentType("application/json");
        }
    }

    /**
     * PUT /desktop/config/raw
     * Body: { "text": <string> }
     */
    public void putRawConfig(Context ctx) {
        String text;
        try {
            Map<String, Object> body = MAPPER.readValue(ctx.body(), OBJECT_MAP_TYPE);
            Object raw = body.get("text");
            text = raw != null ? raw.toString() : "";
        } catch (Exception e) {
            ctx.status(400).result("{\"error\":\"请求体无效: " + escapeJson(e.getMessage()) + "\"}")
                    .contentType("application/json");
            return;
        }
        try {
            RawSaveResult r = applyRawSave(text);
            ctx.json(r.result().payload());
        } catch (TomlValidationException e) {
            // TOML syntax/structure/type failure → 400 with line/col + violating keys,
            // nothing written to disk. SPEC-TOML-API-001b/c, SPEC-TOML-GOAL-005.
            String msg = "配置文本无效: " + String.join("; ", e.messages());
            ctx.status(400).result("{\"error\":\"" + escapeJson(msg) + "\"}")
                    .contentType("application/json");
        } catch (Throwable t) {
            // IO/other failures → 500 (disk write happens only after validation passes).
            log.error("配置保存失败 ({})", t.getClass().getSimpleName());
            String msg = "配置读写失败";
            ctx.status(500).result("{\"error\":\"Save raw config failed: " + escapeJson(msg) + "\"}")
                    .contentType("application/json");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 20);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) { sb.append(String.format("\\u%04x", (int) c)); }
                    else { sb.append(c); }
            }
        }
        return sb.toString();
    }

    /**
     * POST /desktop/config/test-llm
     */
    public void testLlm(Context ctx) {
        try {
            Map<String, Object> body;
            try {
                body = MAPPER.readValue(ctx.body(), OBJECT_MAP_TYPE);
            } catch (Exception e) {
                body = Map.of();
            }

            Config effective = testConfiguration(body);
            String userProvidedKey = body.containsKey("text") ? null : stringOr(body.get("apiKey"), null);
            // If frontend sends a masked key (user didn't type a new one), use stored key
            if (userProvidedKey == null || userProvidedKey.contains("****")) {
                userProvidedKey = null;
            }
            String apiKey;
            if (userProvidedKey != null) {
                apiKey = userProvidedKey;
            } else {
                apiKey = effective.llmApiKey();
            }
            String baseUrl = stringOr(body.get("baseUrl"), effective.llmBaseUrl());
            String model = stringOr(body.get("model"), effective.llmModel());

            if (apiKey == null || apiKey.isBlank() || apiKey.contains("CHANGE_ME")) {
                ctx.json(Map.of("ok", false, "error", "API Key 未配置"));
                return;
            }

            // Call /models endpoint to verify connectivity
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                ctx.json(Map.of("ok", true, "model", model));
            } else {
                ctx.json(Map.of("ok", false,
                        "error", "HTTP " + resp.statusCode()));
            }
        } catch (Throwable e) {
            ctx.status(200).json(Map.of("ok", false, "error",
                    "连接测试失败 (" + e.getClass().getSimpleName() + ")"));
        }
    }

    /**
     * POST /desktop/config/test-embedding
     */
    public void testEmbedding(Context ctx) {
        try {
            Map<String, Object> body;
            try {
                body = MAPPER.readValue(ctx.body(), OBJECT_MAP_TYPE);
            } catch (Exception e) {
                body = Map.of();
            }

            Config effective = testConfiguration(body);
            String userProvidedKey = body.containsKey("text") ? null : stringOr(body.get("embeddingApiKey"), null);
            if (userProvidedKey == null || userProvidedKey.contains("****")) {
                userProvidedKey = null;
            }
            String apiKey;
            if (userProvidedKey != null) {
                apiKey = userProvidedKey;
            } else {
                apiKey = effective.embeddingApiKey();
            }
            String baseUrl = stringOr(body.get("embeddingBaseUrl"),
                    effective.embeddingBaseUrl());
            String model = stringOr(body.get("embeddingModel"), effective.embeddingModel());
            boolean sendEncodingFormat = body.containsKey("embeddingSendEncodingFormat")
                    ? Boolean.parseBoolean(stringOr(body.get("embeddingSendEncodingFormat"), "true"))
                    : effective.embeddingSendEncodingFormat();

            if (apiKey == null || apiKey.isBlank() || apiKey.contains("CHANGE_ME")) {
                ctx.json(Map.of("ok", false, "error", "API Key 未配置"));
                return;
            }

            // Test by sending a single embedding request with a simple text
            String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("model", model);
            reqBody.put("input", "test");
            if (sendEncodingFormat) {
                reqBody.put("encoding_format", "float");
            }

            String reqJson = MAPPER.writeValueAsString(reqBody);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqJson))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                ctx.json(Map.of("ok", true, "model", model));
            } else {
                ctx.json(Map.of("ok", false,
                        "error", "HTTP " + resp.statusCode()));
            }
        } catch (Throwable e) {
            ctx.status(200).json(Map.of("ok", false, "error",
                    "连接测试失败 (" + e.getClass().getSimpleName() + ")"));
        }
    }

    private Config testConfiguration(Map<String, Object> body) {
        if (!body.containsKey("text")) return configuration.saved().config();
        var flat = TomlSupport.parseAndFlatten(String.valueOf(body.get("text")));
        var violations = TomlSupport.validateTypes(flat, SupportedKeys.types());
        if (!violations.isEmpty()) throw new TomlValidationException(violations);
        // 兼容缺省参数沿用已保存配置；显式空值必须保留。
        Properties user = userStore.loadUser();
        flat.forEach(user::setProperty);
        return configuration.resolve(user).config();
    }

    // ── Section builders ─────────────────────────────────────────
    // Each returns a list of config fields with metadata.

    private Map<String, Map<String, Object>> buildLlmSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        String rawKey = eff.getProperty("llm.api-key", "");
        m.put("apiKey", field("apiKey", isPlaceholder(rawKey) ? "" : rawKey, true));
        m.put("baseUrl", field("baseUrl", eff.getProperty("llm.base-url", "https://api.openai.com/v1")));
        m.put("model", field("model", eff.getProperty("llm.model", "gpt-4o")));
        m.put("temperature", field("temperature", eff.getProperty("llm.temperature", "0.7")));
        return m;
    }

    private Map<String, Map<String, Object>> buildAwSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("mode", field("mode", eff.getProperty("aw.mode", "embedded")));
        m.put("port", field("port", eff.getProperty("aw.port", "5700")));
        m.put("dataDir", field("dataDir", eff.getProperty("aw.data-dir",
                System.getProperty("user.home") + "/.self-analyst/events")));
        m.put("webUrl", field("webUrl", "http://localhost:" + eff.getProperty("aw.port", "5700") + "/"));
        return m;
    }

    private Map<String, Map<String, Object>> buildCollectionSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("window", field("window", eff.getProperty("aw.collection.window", "true")));
        m.put("afk", field("afk", eff.getProperty("aw.collection.afk", "true")));
        m.put("content", field("content", eff.getProperty("aw.collection.content", "true")));
        return m;
    }

    private Map<String, Object> buildAgentSection(Properties eff) {
        var m = new LinkedHashMap<String, Object>();
        m.put("summaryRefreshMinutes", field("summaryRefreshMinutes", eff.getProperty("agent.summaryRefreshMinutes", "5")));
        m.put("allowAgentTasks", field("allowAgentTasks", eff.getProperty("agent.allowAgentTasks", "false")));
        m.put("cacheSummaries", field("cacheSummaries", eff.getProperty("agent.cacheSummaries", "true")));
        var compaction = new LinkedHashMap<String, Map<String, Object>>();
        compaction.put("enabled", field("agent.compaction.enabled",
                eff.getProperty("agent.compaction.enabled", "true")));
        compaction.put("triggerMessages", field("agent.compaction.triggerMessages",
                eff.getProperty("agent.compaction.triggerMessages", "30")));
        compaction.put("triggerTokens", field("agent.compaction.triggerTokens",
                eff.getProperty("agent.compaction.triggerTokens", "60000")));
        compaction.put("keepMessages", field("agent.compaction.keepMessages",
                eff.getProperty("agent.compaction.keepMessages", "10")));
        compaction.put("keepTokens", field("agent.compaction.keepTokens",
                eff.getProperty("agent.compaction.keepTokens", "12000")));
        m.put("compaction", compaction);
        return m;
    }

    private Map<String, Map<String, Object>> buildDesktopSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("hideToTray", field("hideToTray", eff.getProperty("desktop.hideToTray", "true")));
        m.put("autoOpenWindow", field("autoOpenWindow", eff.getProperty("desktop.autoOpenWindow", "true")));
        m.put("autoStartBackend", field("autoStartBackend", eff.getProperty("desktop.autoStartBackend", "true")));
        return m;
    }

    private Map<String, Map<String, Object>> buildEmbeddingSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("embeddingEnabled", field("embeddingEnabled", eff.getProperty("embedding.enabled", "true")));
        m.put("embeddingBaseUrl", field("embeddingBaseUrl", eff.getProperty("embedding.base-url", "https://api.openai.com/v1")));
        m.put("embeddingApiKey", field("embeddingApiKey", eff.getProperty("embedding.api-key", ""), true));
        m.put("embeddingModel", field("embeddingModel", eff.getProperty("embedding.model", "text-embedding-3-small")));
        m.put("embeddingDimensions", field("embeddingDimensions", eff.getProperty("embedding.dimensions", "1536")));
        m.put("embeddingSendEncodingFormat", field("embeddingSendEncodingFormat", eff.getProperty("embedding.send-encoding-format", "true")));
        return m;
    }

    private Map<String, Map<String, Object>> buildWebSearchSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("webSearchEnabled", field("webSearchEnabled", eff.getProperty("websearch.enabled", "false")));
        m.put("webSearchMcpUrl", field("webSearchMcpUrl", eff.getProperty("websearch.mcp-url", "https://search.parallel.ai/mcp")));
        m.put("webSearchApiKey", field("webSearchApiKey", eff.getProperty("websearch.api-key", ""), true));
        return m;
    }

    /**
     * Build a config field object with metadata:
     * effectiveValue, savedValue, source, overridden, restartRequiredOnChange.
     */
    private Map<String, Object> field(String name, String effectiveValue) {
        return field(name, effectiveValue, false);
    }

    private Map<String, Object> field(String name, String effectiveValue, boolean sensitive) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("effectiveValue", sensitive ? maskKey(effectiveValue) : effectiveValue);
        field.put("savedValue", null);
        field.put("source", "default");
        field.put("overridden", false);
        field.put("restartRequiredOnChange", false);
        // 完整点分键也供独立 section builder 的调用方使用。
        if (SupportedKeys.contains(name)) {
            String saved = userStore.loadUser().getProperty(name);
            var resolved = configuration.saved().values().get(name);
            field.put("savedValue", saved == null ? null : sensitive ? maskKey(saved) : saved);
            field.put("source", resolved.source().equals("toml") ? "user_config" : resolved.source());
            field.put("overridden", saved != null && !Objects.equals(saved, effectiveValue));
            field.put("restartRequiredOnChange", ConfigPolicy.requiresRestart(name));
        }
        return field;
    }

    /**
     * Try to find user-saved value by searching common key prefixes.
     */
    private String findUserValue(Properties user, String name) {
        String[] prefixes = {"llm.", "aw.", "aw.collection.", "agent.", "desktop.", "embedding.", "websearch."};
        for (String prefix : prefixes) {
            String val = user.getProperty(prefix + name);
            if (val != null) return val;
        }
        // Also try dotted forms
        if (name.contains(".")) {
            return user.getProperty(name);
        }
        return null;
    }

    private static boolean isPlaceholder(String value) {
        return value == null || value.startsWith("${") || value.equals("CHANGE_ME");
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return key.substring(0, Math.min(4, key.length())) + "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private static String stringOr(Object val, String fallback) {
        return val != null ? val.toString() : fallback;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
