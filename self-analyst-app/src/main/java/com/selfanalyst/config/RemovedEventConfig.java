package com.selfanalyst.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** 已移除事件配置名的诊断表；只报告错误，不转换或读取旧值。 */
public final class RemovedEventConfig {
    private RemovedEventConfig() {}

    private static final Map<String, String> KEYS = Map.ofEntries(
            Map.entry("aw.mode", "events.mode"),
            Map.entry("aw.port", "events.port"),
            Map.entry("aw.base-url", "events.base-url"),
            Map.entry("aw.timeout", "events.timeout"),
            Map.entry("aw.data-dir", "events.data-dir"),
            Map.entry("aw.raw.dir", "events.raw.dir"),
            Map.entry("aw.raw.query.maxRangeDays", "events.raw.query.maxRangeDays"),
            Map.entry("aw.raw.query.maxPageSize", "events.raw.query.maxPageSize"),
            Map.entry("aw.raw.lowDisk.warnBytes", "events.raw.lowDisk.warnBytes"),
            Map.entry("aw.raw.lowDisk.blockBytes", "events.raw.lowDisk.blockBytes"),
            Map.entry("aw.raw.integrity.verifyOnStartup", "events.raw.integrity.startupScope"),
            Map.entry("aw.raw.projector.batchSize", "events.raw.projector.batchSize"),
            Map.entry("aw.collection.window", "events.collection.window"),
            Map.entry("aw.collection.afk", "events.collection.afk"),
            Map.entry("aw.collection.content", "events.collection.title.enabled"),
            Map.entry("aw.collection.content.pollMs", "events.collection.title.pollMs"));
    private static final Map<String, String> ENVIRONMENT = Map.ofEntries(
            Map.entry("AW_MODE", "EVENTS_MODE"),
            Map.entry("AW_BASE_URL", "EVENTS_BASE_URL"),
            Map.entry("AW_TIMEOUT", "EVENTS_TIMEOUT"),
            Map.entry("AW_DATA_DIR", "EVENTS_DATA_DIR"),
            Map.entry("AW_RAW_DIR", "EVENTS_RAW_DIR"),
            Map.entry("AW_RAW_QUERY_MAX_RANGE_DAYS", "EVENTS_RAW_QUERY_MAX_RANGE_DAYS"),
            Map.entry("AW_RAW_QUERY_MAX_PAGE_SIZE", "EVENTS_RAW_QUERY_MAX_PAGE_SIZE"),
            Map.entry("AW_RAW_LOW_DISK_WARN_BYTES", "EVENTS_RAW_LOW_DISK_WARN_BYTES"),
            Map.entry("AW_RAW_LOW_DISK_BLOCK_BYTES", "EVENTS_RAW_LOW_DISK_BLOCK_BYTES"),
            Map.entry("AW_RAW_INTEGRITY_VERIFY_ON_STARTUP", "EVENTS_RAW_INTEGRITY_STARTUP_SCOPE"),
            Map.entry("AW_RAW_PROJECTOR_BATCH_SIZE", "EVENTS_RAW_PROJECTOR_BATCH_SIZE"),
            Map.entry("AW_COLLECTION_WINDOW", "EVENTS_COLLECTION_WINDOW"),
            Map.entry("AW_COLLECTION_AFK", "EVENTS_COLLECTION_AFK"),
            Map.entry("AW_COLLECTION_CONTENT", "EVENTS_COLLECTION_TITLE_ENABLED"),
            Map.entry("AW_CONTENT_POLL_MS", "EVENTS_COLLECTION_TITLE_POLL_MS"));

    public static void validate(Properties user, Map<String, String> environment) {
        Map<String, String> violations = new LinkedHashMap<>();
        collect(user.keySet(), KEYS, "TOML", violations);
        collect(environment.keySet(), ENVIRONMENT, "环境变量", violations);
        if (!violations.isEmpty()) throw new TomlValidationException(new ArrayList<>(violations.values()));
    }

    public static void rejectKeys(Collection<?> keys) {
        Map<String, String> violations = new LinkedHashMap<>();
        collect(keys, KEYS, "配置项", violations);
        if (!violations.isEmpty()) throw new TomlValidationException(new ArrayList<>(violations.values()));
    }

    private static void collect(Collection<?> names, Map<String, String> replacements,
                                String source, Map<String, String> violations) {
        names.stream().map(String::valueOf).sorted().forEach(name -> {
            String replacement = replacements.get(name);
            if (replacement != null) violations.put(name, source + " " + name
                    + " 已移除，请手动改为 " + replacement + " 并移除旧名称");
        });
    }
}
