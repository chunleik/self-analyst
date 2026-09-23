package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.config.ConfigResolver;
import com.selfanalyst.usage.UsageMeter;
import com.selfanalyst.wiki.WikiSummaryPipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Test-only bridge loaded by the Wiki evaluation CLI without a Wiki-to-app dependency. */
public final class WikiFormalEvaluationSession implements WikiSummaryPipeline.Session {
    private final SelfAnalystAgent agent;
    private final SelfAnalystAgent.PlainTask task;
    private final UsageMeter meter;
    private final Config config;
    private boolean closed;

    public WikiFormalEvaluationSession(String key, String baseUrl, String model, Path isolatedRoot) throws IOException {
        this(key, baseUrl, model, isolatedRoot, 100_000_000L);
    }

    WikiFormalEvaluationSession(String key, String baseUrl, String model, Path isolatedRoot, long dailyTokens) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("llm.api-key", key);
        properties.setProperty("llm.base-url", baseUrl);
        properties.setProperty("llm.model", model);
        properties.setProperty("app.language", "zh");
        properties.setProperty("memory.dir", isolatedRoot.resolve("memory").toAbsolutePath().toString());
        properties.setProperty("events.mode", "external");
        properties.setProperty("wiki.enabled", "false");
        properties.setProperty("websearch.enabled", "false");
        properties.setProperty("agent.compaction.enabled", "false");
        properties.setProperty("llm.budget.mode", "block");
        properties.setProperty("llm.budget.dailyTokens", Long.toString(dailyTokens));
        // Explicit empty environment: never discover the user's config, credentials or data roots.
        config = ConfigResolver.resolve(properties, Map.of()).config();
        meter = new UsageMeter(config, config.memoryDir());
        try {
            agent = new SelfAnalystAgent(config, null, null, null, null, meter);
            task = agent.plainTask();
        } catch (IOException | RuntimeException error) {
            meter.flush();
            throw error;
        }
    }

    @Override public void preflight() { task.preflight(); }
    @Override public String identity() { return task.cacheIdentity(); }
    @Override public String configurationRevision() { return task.configurationRevision(); }
    @Override public int overheadChars() { return task.requestOverheadChars(); }
    @Override public long estimateInputTokens(String prompt) { return task.estimateInputTokens(prompt); }
    @Override public String complete(String prompt, Duration timeout) { return task.complete(prompt, timeout); }
    @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
        return task.completeDetailed(prompt, timeout);
    }

    /** Only safe reproducibility metadata; never return endpoints, credentials or local paths. */
    public Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transport", "formal-plain-task");
        result.put("model", config.llmModel());
        result.put("modelFingerprint", identity());
        result.put("language", config.effectiveLanguage().code());
        result.put("temperature", 0.2);
        result.put("sdkMaxAttempts", 1);
        result.put("systemPromptFingerprint", hash(AgentPrompts.plainCompletionPrompt(config.effectiveLanguage())));
        result.put("outputTokenLimit", null);
        result.put("responseFormat", null);
        result.put("budgetMode", config.budgetMode());
        result.put("dailyTokens", config.budgetDailyTokens());
        result.put("usagePolicy", "provider-usage-first; missing receipt remains unknown; isolated global meter estimates");
        return result;
    }

    public Map<String, Object> usageSnapshot() { return meter.snapshot(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        task.close();
        try { agent.close(); }
        finally { meter.flush(); }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("EVAL_FINGERPRINT");
        }
    }
}
