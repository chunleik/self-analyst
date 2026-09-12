package com.selfanalyst.llm.settings;

import com.selfanalyst.config.ConfigApplicationService;
import com.selfanalyst.config.ConfigResolver;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public final class TomlLlmSettingsRepository implements LlmSettingsRepository {
    private final ConfigApplicationService configuration;
    public TomlLlmSettingsRepository(ConfigApplicationService configuration) { this.configuration = configuration; }
    public Object commitLock() { return configuration.commitLock(); }
    public Properties read() throws IOException { return configuration.readUserStrict(); }
    public ConfigResolver.Snapshot resolve(Properties user) { return configuration.resolve(user); }
    public ConfigApplicationService.SaveResult save(Map<String, String> changes) throws IOException {
        return configuration.updateLlm(changes);
    }
    public Map<String, Object> runtime() {
        var payload = configuration.effectivePayload();
        return Map.of("revision", payload.get("revision"), "application", payload.get("application"));
    }
}
