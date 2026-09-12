package com.selfanalyst.config;

import com.selfanalyst.desktop.store.UserConfigStore;
import com.selfanalyst.events.raw.RawPartitionCatalog;
import com.selfanalyst.file.FileFilterConfig;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** 应用内配置的校验、串行持久化和运行时发布入口。 */
public final class ConfigApplicationService implements AutoCloseable {
    private final Object lock = new Object();
    private final UserConfigStore store;
    private final Map<String, String> environment;
    private final Map<String, String> running = new LinkedHashMap<>();
    private final String processId = UUID.randomUUID().toString();
    private final Config startup;
    private long revision;
    private volatile boolean closed;
    private Function<LlmSettings, Pending> prepareRuntime;
    private Supplier<Map<String, Object>> runtimeStatus;
    private Supplier<LlmSettings> runtimeSettings;

    private interface Pending extends AutoCloseable {
        void publish();
        void close();
    }

    public record SaveResult(long revision, long llmRevision, List<String> restartRequired,
                             List<String> unknownKeys, Map<String, Object> application) {
        public Map<String, Object> payload() {
            return Map.of("saved", true, "revision", revision, "llmRevision", llmRevision,
                    "restartRequired", restartRequired, "unknownKeys", unknownKeys, "application", application);
        }
    }

    public ConfigApplicationService(UserConfigStore store, Config startup) {
        this(store, startup, System.getenv());
    }
    public ConfigApplicationService(UserConfigStore store, Config startup, Map<String, String> environment) {
        this.store = store;
        this.startup = startup;
        this.environment = Map.copyOf(environment);
        ConfigResolver.resolve(store.loadUser(), environment).values()
                .forEach((key, value) -> running.put(key, value.value()));
        ConfigResolver.runtimeProperties(startup).forEach((key, value) -> running.put(key.toString(), value.toString()));
    }
    public Object commitLock() { return lock; }
    public <T extends AutoCloseable> void attach(LlmRuntimeManager<T> runtime) {
        synchronized (lock) {
            prepareRuntime = settings -> {
                var candidate = runtime.prepare(settings);
                return new Pending() {
                    public void publish() { runtime.publish(candidate); }
                    public void close() { candidate.close(); }
                };
            };
            runtimeSettings = runtime::settings;
            runtimeStatus = runtime::status;
        }
    }
    public ConfigResolver.Snapshot resolve(Properties user) { return ConfigResolver.resolve(user, environment); }
    public ConfigResolver.Snapshot saved() {
        synchronized (lock) { return resolve(store.loadUser()); }
    }
    public SaveResult saveRaw(String text) throws IOException {
        synchronized (lock) { return commit(text); }
    }
    /** 专用模型设置读取必须严格解析，禁止容错读取覆盖损坏文件。 */
    public Properties readUserStrict() throws IOException {
        synchronized (lock) {
            Properties user = new Properties();
            TomlSupport.parseAndFlatten(store.readRaw()).forEach(user::setProperty);
            return user;
        }
    }
    public SaveResult updateLlm(Map<String, String> changes) throws IOException {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("应用正在关闭，无法保存配置");
            return commit(LlmTomlEditor.edit(store.readRaw(), changes));
        }
    }
    public SaveResult update(Map<String, String> changes) throws IOException {
        synchronized (lock) {
            RemovedEventConfig.rejectKeys(changes.keySet());
            Properties user = store.loadUser();
            changes.forEach((key, value) -> {
                if (!SupportedKeys.contains(key)) throw new IllegalArgumentException("不支持的配置键：" + key);
                if (value == null || value.isBlank()) user.remove(key);
                else user.setProperty(key, value);
            });
            Map<String, String> flat = new TreeMap<>();
            user.stringPropertyNames().forEach(key -> flat.put(key, user.getProperty(key)));
            return commit(TomlSupport.generateToml(flat, SupportedKeys.types()));
        }
    }
    /** 专用文件接口已经应用的配置键，必须保持与保存相同的锁顺序。 */
    public void markApplied(Collection<String> keys) {
        synchronized (lock) {
            var values = saved().values();
            for (String key : keys) {
                var value = values.get(key);
                if (value != null) running.put(key, value.value());
            }
        }
    }
    private SaveResult commit(String text) throws IOException {
        if (closed) throw new IllegalStateException("应用正在关闭，无法保存配置");
        var flat = TomlSupport.parseAndFlatten(text);
        RemovedEventConfig.rejectKeys(flat.keySet());
        var violations = TomlSupport.validateTypes(flat, SupportedKeys.types());
        if (!violations.isEmpty()) throw new TomlValidationException(violations);
        Properties user = new Properties();
        flat.forEach(user::setProperty);
        Properties input = new ConfigResolver(user, environment).inputProperties();
        try {
            LlmSettings.validate(input);
            RawConfigValidator.validate(user);
            FileFilterConfig.parse(Long.parseLong(input.getProperty("file.watch.maxFileSizeKb", "0")),
                    FileFilterConfig.splitCsv(input.getProperty("file.watch.excludeDirs", "")),
                    FileFilterConfig.splitCsv(input.getProperty("file.watch.excludeGlobs", "")),
                    FileFilterConfig.splitCsv(input.getProperty("file.watch.extensions", "")),
                    Boolean.parseBoolean(input.getProperty("file.watch.respectGitIgnore", "true")));
        } catch (IllegalArgumentException invalid) {
            throw new TomlValidationException(List.of("配置语义无效，请检查模型参数、原始事件及文件过滤设置"));
        }
        ConfigResolver.Snapshot proposed = resolve(user);
        if (!startup.eventsRawDir().toAbsolutePath().normalize()
                .equals(proposed.config().eventsRawDir().toAbsolutePath().normalize())
                && RawPartitionCatalog.hasExistingPartitions(startup.eventsRawDir())) {
            throw new TomlValidationException(List.of("events.raw.dir 已有原始分区，请使用独立的显式转存流程"));
        }
        LlmSettings llm = LlmSettings.from(proposed.config());
        Pending pending = null;
        try {
            if (prepareRuntime != null && !llm.equals(runtimeSettings.get())) pending = prepareRuntime.apply(llm);
            store.saveRaw(text);
            if (pending != null) pending.publish();
            revision++;
            if (runtimeSettings != null) running.putAll(runtimeSettings.get().properties());
            return result(proposed, flat.keySet());
        } catch (IOException failure) {
            throw new IOException("配置文件写入失败，原配置保持不变");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("模型配置应用失败，原运行配置保持不变");
        } finally {
            if (pending != null) pending.close();
        }
    }
    private Map<String, Object> application(ConfigResolver.Snapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> llm = new LinkedHashMap<>(runtimeStatus == null
                ? Map.of("status", "unavailable", "activeWorkCount", 0, "revision", 0L) : runtimeStatus.get());
        llm.put("changedKeys", ConfigPolicy.LLM.stream().filter(key -> differs(snapshot, key)).sorted().toList());
        result.put("llm", llm);
        Map<String, List<String>> groups = new LinkedHashMap<>();
        snapshot.values().keySet().stream().filter(ConfigPolicy::requiresRestart).sorted()
                .filter(key -> differs(snapshot, key)).forEach(key ->
                        groups.computeIfAbsent(ConfigPolicy.component(key), ignored -> new ArrayList<>()).add(key));
        groups.forEach((component, keys) -> result.put(component,
                Map.of("status", "restart_required", "changedKeys", List.copyOf(keys))));
        return result;
    }
    private boolean differs(ConfigResolver.Snapshot snapshot, String key) {
        var value = snapshot.values().get(key);
        return value != null && !Objects.equals(value.value(), running.get(key));
    }
    private SaveResult result(ConfigResolver.Snapshot snapshot, Set<String> keys) {
        List<String> restart = snapshot.values().keySet().stream().filter(ConfigPolicy::requiresRestart)
                .filter(key -> differs(snapshot, key)).sorted().toList();
        List<String> unknown = keys.stream().filter(key -> !SupportedKeys.contains(key) && !DeprecatedKeys.contains(key))
                .sorted().toList();
        long modelRevision = runtimeStatus == null ? 0 : ((Number) runtimeStatus.get().get("revision")).longValue();
        return new SaveResult(revision, modelRevision, restart, unknown, application(snapshot));
    }
    public Map<String, Object> effectivePayload() {
        synchronized (lock) {
            var snapshot = saved();
            Map<String, Object> actual = new LinkedHashMap<>();
            running.forEach((key, value) -> {
                if (SupportedKeys.contains(key)) actual.put(key,
                        ConfigResolver.sensitive(key) ? (value.isBlank() ? "" : "****") : value);
            });
            return Map.of("processId", processId, "revision", revision, "configured", snapshot.publicValues(),
                    "running", actual, "application", application(snapshot));
        }
    }
    public String sourceSummary() {
        var snapshot = saved();
        return String.join("、", ConfigPolicy.LLM.stream().sorted()
                .map(key -> key + " (" + snapshot.values().get(key).source() + ")").toList());
    }
    @Override public void close() { closed = true; }
}
