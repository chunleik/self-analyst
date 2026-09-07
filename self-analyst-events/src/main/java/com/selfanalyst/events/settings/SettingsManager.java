package com.selfanalyst.events.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SettingsManager {

    private static final Logger log = LoggerFactory.getLogger(SettingsManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path settingsPath;
    private Map<String, Object> settings;

    public SettingsManager(Path settingsPath) {
        this.settingsPath = settingsPath;
        this.settings = loadDefaults();
        load();
    }

    public Map<String, Object> getAll() {
        return new HashMap<>(settings);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = settings.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    public void set(String key, Object value) {
        settings.put(key, value);
    }

    public void save() {
        try {
            MAPPER.writeValue(settingsPath.toFile(), settings);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save settings to " + settingsPath, e);
        }
    }

    public void save(Map<String, Object> newSettings) {
        this.settings = newSettings != null ? new HashMap<>(newSettings) : loadDefaults();
        save();
    }

    private void load() {
        File file = settingsPath.toFile();
        if (file.exists()) {
            try {
                Map<String, Object> loaded = MAPPER.readValue(file, new TypeReference<Map<String, Object>>() {});
                if (loaded != null) {
                    settings = new HashMap<>(loaded);
                }
            } catch (Exception e) {
                // If loading fails, keep defaults
                log.warn("Failed to load settings from {}, using defaults", settingsPath);
            }
        }
    }

    private static Map<String, Object> loadDefaults() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("categories", List.of());
        defaults.put("pulsetime", 120);
        return defaults;
    }
}
