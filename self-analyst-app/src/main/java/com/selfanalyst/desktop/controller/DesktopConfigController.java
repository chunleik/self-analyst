package com.selfanalyst.desktop.controller;

import com.selfanalyst.config.Config;
import com.selfanalyst.config.SupportedKeys;
import com.selfanalyst.config.TomlSupport;
import com.selfanalyst.config.TomlValidationException;
import com.selfanalyst.desktop.store.ConfigHistoryStore;
import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.headroom.HeadroomService;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** Keys that require a backend restart when changed. */
    private static final Set<String> RESTART_REQUIRED = Set.of(
            "llm.model", "aw.mode", "aw.port", "aw.data-dir",
            "memory.dir", "agent.summaryRefreshMinutes", "desktop.autoStartBackend",
            "websearch.enabled", "websearch.mcp-url", "websearch.api-key",
            "headroom.enabled", "headroom.proxy-url", "headroom.stats.enabled", "headroom.output-shaper"
    );

    // Supported config keys, defaults and declared types live in the shared
    // com.selfanalyst.config.SupportedKeys (single source of truth for the raw-edit
    // template, unknown-key detection, type validation, and TOML generation).

    /** Version-name timestamp format (local zone). SPEC-CFGUI-VER-DEC-002. */
    private static final DateTimeFormatter VERSION_NAME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Summary used when a save changed no key values (only comments/formatting). */
    static final String NO_CHANGE_SUMMARY = "无键值变化（仅注释或格式）";

    private final Config config;
    private final UserConfigStore userStore;
    private final HeadroomService headroomService;
    private final ConfigHistoryStore historyStore;
    /** Single daemon thread for best-effort, non-blocking LLM summary refinement. */
    private final ExecutorService summaryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "config-version-summary");
        t.setDaemon(true);
        return t;
    });

    public DesktopConfigController(Config config, UserConfigStore userStore) {
        this(config, userStore, null);
    }

    public DesktopConfigController(Config config, UserConfigStore userStore, HeadroomService headroomService) {
        this.config = config;
        this.userStore = userStore;
        this.headroomService = headroomService;
        this.historyStore = new ConfigHistoryStore(userStore.filePath().getParent());
    }

    /**
     * GET /desktop/config
     */
    public void getConfig(Context ctx) {
        Properties defaults = userStore.load(); // merged classpath + user

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("llm", buildLlmSection(defaults));
        response.put("aw", buildAwSection(defaults));
        response.put("collection", buildCollectionSection(defaults));
        response.put("audio", buildAudioSection(defaults));
        response.put("agent", buildAgentSection(defaults));
        response.put("desktop", buildDesktopSection(defaults));
        response.put("embedding", buildEmbeddingSection(defaults));
        response.put("websearch", buildWebSearchSection(defaults));
        response.put("headroom", buildHeadroomSection(defaults));
        ctx.json(response);
    }

    /**
     * PUT /desktop/config
     * Body: { "llm": {...}, "aw": {...}, ... }
     */
    @SuppressWarnings("unchecked")
    public void putConfig(Context ctx) {
        try {
            Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
            Properties user = userStore.loadUser();
            List<String> restartKeys = new ArrayList<>();

            // Map frontend camelCase keys to actual config property keys
            Map<String, String> keyMapping = new LinkedHashMap<>();
            keyMapping.put("apiKey", "llm.api-key");
            keyMapping.put("baseUrl", "llm.base-url");
            keyMapping.put("model", "llm.model");
            keyMapping.put("temperature", "llm.temperature");
            keyMapping.put("mode", "aw.mode");
            keyMapping.put("port", "aw.port");
            keyMapping.put("dataDir", "aw.data-dir");
            keyMapping.put("window", "aw.collection.window");
            keyMapping.put("afk", "aw.collection.afk");
            keyMapping.put("content", "aw.collection.content");
            keyMapping.put("ocrEngine", "aw.ocr.engine");
            keyMapping.put("enabled", "aw.audio.enabled");
            keyMapping.put("whisperPath", "aw.audio.whisperPath");
            keyMapping.put("whisper_path", "aw.audio.whisperPath");
            keyMapping.put("vadThreshold", "aw.audio.vadThreshold");
            keyMapping.put("vad_threshold", "aw.audio.vadThreshold");
            keyMapping.put("source", "aw.audio.source");
            keyMapping.put("engine", "aw.audio.engine");
            keyMapping.put("model", "aw.audio.model");
            keyMapping.put("chunkSeconds", "aw.audio.chunkSeconds");
            keyMapping.put("chunk_seconds", "aw.audio.chunkSeconds");
            keyMapping.put("summaryRefreshMinutes", "agent.summaryRefreshMinutes");
            keyMapping.put("refresh_interval", "agent.summaryRefreshMinutes");
            keyMapping.put("allowAgentTasks", "agent.allowAgentTasks");
            keyMapping.put("allow_agent_tasks", "agent.allowAgentTasks");
            keyMapping.put("cacheSummaries", "agent.cacheSummaries");
            keyMapping.put("cache", "agent.cacheSummaries");
            keyMapping.put("hideToTray", "desktop.hideToTray");
            keyMapping.put("hide_to_tray", "desktop.hideToTray");
            keyMapping.put("autoOpenWindow", "desktop.autoOpenWindow");
            keyMapping.put("auto_open", "desktop.autoOpenWindow");
            keyMapping.put("autoStartBackend", "desktop.autoStartBackend");
            keyMapping.put("auto_start", "desktop.autoStartBackend");
            keyMapping.put("embeddingEnabled", "embedding.enabled");
            keyMapping.put("embeddingBaseUrl", "embedding.base-url");
            keyMapping.put("embeddingBase_url", "embedding.base-url");
            keyMapping.put("embeddingApiKey", "embedding.api-key");
            keyMapping.put("embeddingApi_key", "embedding.api-key");
            keyMapping.put("embeddingModel", "embedding.model");
            keyMapping.put("embeddingDimensions", "embedding.dimensions");
            keyMapping.put("embeddingSendEncodingFormat", "embedding.send-encoding-format");
            keyMapping.put("webSearchEnabled", "websearch.enabled");
            keyMapping.put("webSearchMcpUrl", "websearch.mcp-url");
            keyMapping.put("webSearchApiKey", "websearch.api-key");
            keyMapping.put("headroomEnabled", "headroom.enabled");
            keyMapping.put("headroomProxyUrl", "headroom.proxy-url");
            keyMapping.put("headroomStatsEnabled", "headroom.stats.enabled");
            keyMapping.put("headroomOutputShaper", "headroom.output-shaper");

            // Flatten sections into dotted keys
            Map<String, String> sectionToPrefix = new LinkedHashMap<>();
            sectionToPrefix.put("llm", "llm.");
            sectionToPrefix.put("aw", "aw.");
            sectionToPrefix.put("collection", "aw.collection.");
            sectionToPrefix.put("audio", "aw.audio.");
            sectionToPrefix.put("agent", "agent.");
            sectionToPrefix.put("desktop", "desktop.");
            sectionToPrefix.put("embedding", "embedding.");
            sectionToPrefix.put("websearch", "websearch.");
            sectionToPrefix.put("headroom", "headroom.");

            for (var entry : sectionToPrefix.entrySet()) {
                String section = entry.getKey();
                String prefix = entry.getValue();
                Object sectionData = body.get(section);
                if (sectionData instanceof Map<?, ?> sectionMap) {
                    for (var kv : ((Map<String, Object>) sectionMap).entrySet()) {
                        String rawKey = kv.getKey();
                        String mappedKey = keyMapping.getOrDefault(rawKey, prefix + rawKey);
                        String value = kv.getValue() != null ? kv.getValue().toString() : "";
                        String old = user.getProperty(mappedKey, "");
                        if (!value.equals(old)) {
                            if (RESTART_REQUIRED.contains(mappedKey)) {
                                restartKeys.add(mappedKey);
                            }
                        }
                        if (value.isBlank()) {
                            user.remove(mappedKey);
                        } else {
                            user.setProperty(mappedKey, value);
                        }
                    }
                }
            }

            // Resolve full restart-required set
            Set<String> fullRestart = new HashSet<>();
            for (String k : restartKeys) {
                fullRestart.add(k);
            }
            // Also check RESTART_REQUIRED directly
            for (var entry : sectionToPrefix.entrySet()) {
                String section = entry.getKey();
                Object sectionData = body.get(section);
                if (sectionData instanceof Map<?, ?> sectionMap) {
                    for (var kv : ((Map<String, Object>) sectionMap).entrySet()) {
                        String key = section + "." + kv.getKey();
                        if (RESTART_REQUIRED.contains(key)) {
                            fullRestart.add(key);
                        }
                    }
                }
            }

            userStore.save(user);
            ctx.json(Map.of("saved", true, "restartRequired", new ArrayList<>(fullRestart)));
        } catch (Throwable t) {
            log.error("Failed to save config", t);
            try {
                String msg = t.getMessage();
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
            if (!RESTART_REQUIRED.contains(key)) continue;
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
            if (!SupportedKeys.contains(key)) {
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
        return TomlSupport.buildTemplate(SupportedKeys.defaults(), SupportedKeys.types());
    }

    /** One supported key's reference info for the raw response. SPEC-TOML-API-001e. */
    record SupportedKeyInfo(String key, String type, String assignment) {}

    /** GET /desktop/config/raw response payload. */
    record RawConfigResponse(String text, String path, boolean exists,
                             List<SupportedKeyInfo> supportedKeys) {}

    /**
     * All supported keys as {@code (key, type, defaultAssignment)}, in
     * {@link SupportedKeys} declaration order, for the "all configurable keys"
     * reference panel (SPEC-TOML-UI-004 / SPEC-TOML-API-001e). {@code assignment} is
     * the top-level dotted TOML form the panel inserts verbatim.
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
    record RawSaveResult(List<String> restartRequired, List<String> unknownKeys) {}

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
        Properties oldP = userStore.loadUser();
        List<String> restart = computeRestartRequired(oldP, newP);
        List<String> unknown = computeUnknownKeys(newP);
        userStore.saveRaw(text);
        recordVersion(oldP, newP, text); // best-effort, never blocks/aborts the save
        return new RawSaveResult(restart, unknown);
    }

    // ── Version history (SPEC-CFGUI-VER) ─────────────────────────

    /** Format a version's auto-name from its save time. SPEC-CFGUI-VER-DEC-002. */
    static String formatVersionName(long epochMillis, ZoneId zone) {
        return VERSION_NAME_FMT.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }

    /**
     * Deterministic key-level diff summary, used as the immediate version hint
     * and as the fallback when LLM refinement is unavailable.
     * SPEC-CFGUI-VER-DEC-002.
     */
    static String computeDiffSummary(Properties oldP, Properties newP) {
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String k : newP.stringPropertyNames()) {
            if (!oldP.containsKey(k)) added.add(k);
            else if (!Objects.equals(oldP.getProperty(k), newP.getProperty(k))) changed.add(k);
        }
        for (String k : oldP.stringPropertyNames()) {
            if (!newP.containsKey(k)) removed.add(k);
        }
        if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()) {
            return NO_CHANGE_SUMMARY;
        }
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) parts.add("新增 " + summarizeKeys(added));
        if (!changed.isEmpty()) parts.add("修改 " + summarizeKeys(changed));
        if (!removed.isEmpty()) parts.add("删除 " + summarizeKeys(removed));
        return String.join("；", parts);
    }

    private static String summarizeKeys(List<String> keys) {
        int shown = Math.min(keys.size(), 3);
        String joined = String.join(", ", keys.subList(0, shown));
        String more = keys.size() > shown ? " 等" + keys.size() + " 项" : "";
        return keys.size() + " 项(" + joined + more + ")";
    }

    /** Snapshot the just-saved text; refine the summary via LLM asynchronously. */
    private void recordVersion(Properties oldP, Properties newP, String newText) {
        try {
            String name = formatVersionName(System.currentTimeMillis(), ZoneId.systemDefault());
            String summary = computeDiffSummary(oldP, newP);
            ConfigHistoryStore.ConfigVersion v = historyStore.add(
                    name, summary, newText, ConfigHistoryStore.FORMAT_TOML);
            // Only key NAMES (never values/raw text) are eligible for LLM refinement,
            // and only when something actually changed. SPEC-CFGUI-VER-DEC-004.
            if (!summary.startsWith(NO_CHANGE_SUMMARY)) {
                summaryExecutor.submit(() -> refineSummaryAsync(v.id(), summary));
            }
        } catch (Exception e) {
            // A history failure must not affect the (already successful) save.
            log.warn("Failed to record config version: {}", e.getMessage());
        }
    }

    /**
     * Best-effort LLM call to phrase a nicer change summary; degrades silently.
     * <p>
     * Privacy contract (SPEC-CFGUI-VER-DEC-004): the prompt contains ONLY the
     * deterministic key-name diff — never config values or raw file text — so
     * secrets (api-keys) are never transmitted to the model endpoint.
     */
    private void refineSummaryAsync(String versionId, String diffSummary) {
        try {
            Properties eff = userStore.load();
            String apiKey = firstNonBlank(
                    System.getenv("OPENAI_API_KEY"),
                    eff.getProperty("llm.api-key"),
                    config.llmApiKey());
            if (apiKey == null || apiKey.isBlank() || apiKey.contains("CHANGE_ME")) {
                return; // no key → keep deterministic summary
            }
            String baseUrl = firstNonBlank(eff.getProperty("llm.base-url"), config.llmBaseUrl());
            String model = firstNonBlank(eff.getProperty("llm.model"), config.llmModel());

            String prompt = "下面是某应用配置的本次改动要点（仅包含配置项名称，不含任何取值）：\n"
                    + diffSummary + "\n\n"
                    + "请用不超过 20 个汉字、一句话自然地概括本次改动"
                    + "（只依据上述要点，不要编造、不要解释、不要标点结尾）。";

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("model", model);
            reqBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            reqBody.put("temperature", 0.2);
            reqBody.put("max_tokens", 60);

            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions"
                    : baseUrl + "/chat/completions";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(reqBody)))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode()) return;
            String summary = content.asText("").trim();
            if (!summary.isBlank()) {
                historyStore.updateSummary(versionId, truncate(summary, 60));
            }
        } catch (Throwable t) {
            log.debug("LLM version-summary refinement skipped: {}", t.getMessage());
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * GET /desktop/config/history — version metadata, newest-first, no text.
     * SPEC-CFGUI-VER-API-001.
     */
    public void getConfigHistory(Context ctx) {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (ConfigHistoryStore.ConfigVersion v : historyStore.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id());
            m.put("name", v.name());
            m.put("summary", v.summary());
            m.put("savedAt", v.savedAt());
            m.put("format", v.format()); // SPEC-TOML-VER-001
            versions.add(m);
        }
        ctx.json(Map.of("versions", versions));
    }

    /**
     * GET /desktop/config/history/{id} — full version including text; 404 if absent.
     * SPEC-CFGUI-VER-API-002.
     */
    public void getConfigVersion(Context ctx) {
        String id = ctx.pathParam("id");
        Optional<ConfigHistoryStore.ConfigVersion> found = historyStore.get(id);
        if (found.isEmpty()) {
            ctx.status(404).result("{\"error\":\"版本不存在\"}").contentType("application/json");
            return;
        }
        ConfigHistoryStore.ConfigVersion v = found.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("name", v.name());
        m.put("summary", v.summary());
        m.put("savedAt", v.savedAt());
        m.put("format", v.format()); // SPEC-TOML-VER-001
        m.put("text", v.text());
        ctx.json(m);
    }

    /**
     * GET /desktop/config/raw
     */
    public void getRawConfig(Context ctx) {
        try {
            ctx.json(buildRawResponse());
        } catch (Throwable t) {
            log.error("Failed to read raw config", t);
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            ctx.status(500).result("{\"error\":\"Read raw config failed: " + escapeJson(msg) + "\"}")
                    .contentType("application/json");
        }
    }

    /**
     * PUT /desktop/config/raw
     * Body: { "text": <string> }
     */
    @SuppressWarnings("unchecked")
    public void putRawConfig(Context ctx) {
        String text;
        try {
            Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
            Object raw = body.get("text");
            text = raw != null ? raw.toString() : "";
        } catch (Exception e) {
            ctx.status(400).result("{\"error\":\"请求体无效: " + escapeJson(e.getMessage()) + "\"}")
                    .contentType("application/json");
            return;
        }
        try {
            RawSaveResult r = applyRawSave(text);
            ctx.json(Map.of("saved", true,
                    "restartRequired", r.restartRequired(),
                    "unknownKeys", r.unknownKeys()));
        } catch (TomlValidationException e) {
            // TOML syntax/structure/type failure → 400 with line/col + violating keys,
            // nothing written to disk. SPEC-TOML-API-001b/c, SPEC-TOML-GOAL-005.
            String msg = "配置文本无效: " + String.join("; ", e.messages());
            ctx.status(400).result("{\"error\":\"" + escapeJson(msg) + "\"}")
                    .contentType("application/json");
        } catch (Throwable t) {
            // IO/other failures → 500 (disk write happens only after validation passes).
            log.error("Failed to save raw config", t);
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
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
                body = MAPPER.readValue(ctx.body(), Map.class);
            } catch (Exception e) {
                body = Map.of();
            }

            String userProvidedKey = stringOr(body.get("apiKey"), null);
            // If frontend sends a masked key (user didn't type a new one), use stored key
            if (userProvidedKey == null || userProvidedKey.contains("****")) {
                userProvidedKey = null;
            }
            String apiKey;
            if (userProvidedKey != null) {
                apiKey = userProvidedKey;
            } else {
                // Try env var first, then user config file, then config record
                apiKey = System.getenv().getOrDefault("OPENAI_API_KEY", null);
                if (apiKey == null || apiKey.isBlank()) {
                    apiKey = userStore.load().getProperty("llm.api-key");
                }
                if (apiKey == null || apiKey.isBlank()) {
                    apiKey = config.llmApiKey();
                }
            }
            String baseUrl = stringOr(body.get("baseUrl"), config.llmBaseUrl());
            String model = stringOr(body.get("model"), config.llmModel());

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
                        "error", "HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200)));
            }
        } catch (Throwable e) {
            ctx.status(200).json(Map.of("ok", false, "error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * POST /desktop/config/test-embedding
     */
    public void testEmbedding(Context ctx) {
        try {
            Map<String, Object> body;
            try {
                body = MAPPER.readValue(ctx.body(), Map.class);
            } catch (Exception e) {
                body = Map.of();
            }

            String userProvidedKey = stringOr(body.get("embeddingApiKey"), null);
            if (userProvidedKey == null || userProvidedKey.contains("****")) {
                userProvidedKey = null;
            }
            String apiKey;
            if (userProvidedKey != null) {
                apiKey = userProvidedKey;
            } else {
                apiKey = System.getenv().getOrDefault("EMBEDDING_API_KEY", null);
                if (apiKey == null || apiKey.isBlank()) {
                    apiKey = System.getenv().getOrDefault("OPENAI_API_KEY", null);
                }
                if (apiKey == null || apiKey.isBlank()) {
                    apiKey = userStore.load().getProperty("embedding.api-key");
                }
                if (apiKey == null || apiKey.isBlank()) {
                    apiKey = config.llmApiKey();
                }
            }
            String baseUrl = stringOr(body.get("embeddingBaseUrl"),
                    config.embeddingBaseUrl());
            String model = stringOr(body.get("embeddingModel"), config.embeddingModel());
            boolean sendEncodingFormat = body.containsKey("embeddingSendEncodingFormat")
                    ? Boolean.parseBoolean(stringOr(body.get("embeddingSendEncodingFormat"), "true"))
                    : true;

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
                        "error", "HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200)));
            }
        } catch (Throwable e) {
            ctx.status(200).json(Map.of("ok", false, "error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
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
                System.getProperty("user.home") + "/.self-analyst/aw-data")));
        m.put("webUrl", field("webUrl", "http://localhost:" + eff.getProperty("aw.port", "5700") + "/"));
        return m;
    }

    private Map<String, Map<String, Object>> buildCollectionSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("window", field("window", eff.getProperty("aw.collection.window", "true")));
        m.put("afk", field("afk", eff.getProperty("aw.collection.afk", "true")));
        m.put("content", field("content", eff.getProperty("aw.collection.content", "true")));
        m.put("ocrEngine", field("ocrEngine", eff.getProperty("aw.ocr.engine", "auto")));
        return m;
    }

    private Map<String, Map<String, Object>> buildAudioSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("enabled", field("enabled", eff.getProperty("aw.audio.enabled", "false")));
        m.put("whisperPath", field("whisperPath", eff.getProperty("aw.audio.whisperPath", "tools/whisper")));
        m.put("vadThreshold", field("vadThreshold", eff.getProperty("aw.audio.vadThreshold", "0.0001")));
        m.put("source", field("source", eff.getProperty("aw.audio.source", "mic")));
        m.put("engine", field("engine", eff.getProperty("aw.audio.engine", "auto")));
        m.put("model", field("model", eff.getProperty("aw.audio.model", "gpt-4o-transcribe")));
        m.put("chunkSeconds", field("chunkSeconds", eff.getProperty("aw.audio.chunkSeconds", "10")));
        return m;
    }

    private Map<String, Map<String, Object>> buildAgentSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("summaryRefreshMinutes", field("summaryRefreshMinutes", eff.getProperty("agent.summaryRefreshMinutes", "5")));
        m.put("allowAgentTasks", field("allowAgentTasks", eff.getProperty("agent.allowAgentTasks", "false")));
        m.put("cacheSummaries", field("cacheSummaries", eff.getProperty("agent.cacheSummaries", "true")));
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

    private Map<String, Map<String, Object>> buildHeadroomSection(Properties eff) {
        var m = new LinkedHashMap<String, Map<String, Object>>();
        m.put("headroomEnabled", field("headroom.enabled", eff.getProperty("headroom.enabled", "false")));
        m.put("headroomProxyUrl", field("headroom.proxy-url", eff.getProperty("headroom.proxy-url", "http://127.0.0.1:8787/v1")));
        m.put("headroomStatsEnabled", field("headroom.stats.enabled", eff.getProperty("headroom.stats.enabled", "true")));
        m.put("headroomOutputShaper", field("headroom.output-shaper", eff.getProperty("headroom.output-shaper", "false")));
        if (headroomService != null) {
            m.put("headroomStatus", field("headroom.runtimeStatus", headroomService.runtimeStatusLine()));
        }
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
        Properties user = userStore.loadUser();

        // Determine the dotted key for this field
        // (section builders use the visual name; we need to map to actual keys)
        // We store user keys with prefixes, but effective comes from merged.
        // For simplicity, source is "default" unless key exists in user file.

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("effectiveValue", sensitive ? maskKey(effectiveValue) : effectiveValue);

        // Attempt to find saved value — search common key patterns
        String savedRaw = findUserValue(user, name);
        f.put("savedValue", savedRaw == null ? null : (sensitive ? maskKey(savedRaw) : savedRaw));

        boolean hasUserOverride = savedRaw != null;
        f.put("overridden", hasUserOverride && !Objects.equals(savedRaw, effectiveValue));
        f.put("source", hasUserOverride ? "user_config" : "default");
        f.put("restartRequiredOnChange",
                RESTART_REQUIRED.contains("llm." + name)
                        || RESTART_REQUIRED.contains("aw." + name)
                        || RESTART_REQUIRED.contains("agent." + name)
                        || RESTART_REQUIRED.contains("desktop." + name)
                        || RESTART_REQUIRED.contains("embedding." + name)
                        || RESTART_REQUIRED.contains("headroom." + name)
                        || RESTART_REQUIRED.contains(name));
        return f;
    }

    /**
     * Try to find user-saved value by searching common key prefixes.
     */
    private String findUserValue(Properties user, String name) {
        String[] prefixes = {"llm.", "aw.", "aw.collection.", "aw.audio.", "agent.", "desktop.", "embedding.", "websearch.", "headroom."};
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
