package com.selfanalyst.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 不可变语言清单，测试可注入独立清单，不影响生产支持列表。 */
public final class LanguageRegistry {
    private final Map<String, Lang> languages;

    public LanguageRegistry(List<Lang> entries) {
        Map<String, Lang> result = new LinkedHashMap<>();
        for (Lang entry : entries) {
            if (result.putIfAbsent(entry.code(), entry) != null) {
                throw new IllegalArgumentException("Duplicate language: " + entry.code());
            }
        }
        if (!result.containsKey("en")) throw new IllegalArgumentException("English fallback is required");
        languages = java.util.Collections.unmodifiableMap(result);
    }

    private static final class Bundled {
        private static final LanguageRegistry INSTANCE = readBundled();
    }

    public static LanguageRegistry bundled() { return Bundled.INSTANCE; }

    private static LanguageRegistry readBundled() {
        try (InputStream input = LanguageRegistry.class.getResourceAsStream("/i18n/languages.json")) {
            if (input == null) throw new IllegalStateException("Missing language registry");
            var tree = new ObjectMapper().readTree(input);
            var entries = new java.util.ArrayList<Lang>();
            for (var item : tree) {
                entries.add(new Lang(item.required("code").asText(), item.required("displayName").asText(),
                        item.required("dateLocale").asText(), item.required("resource").asText()));
            }
            return new LanguageRegistry(entries);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load language registry", error);
        }
    }

    public List<Lang> supported() { return List.copyOf(languages.values()); }

    public Lang find(String code) {
        return code == null ? null : languages.get(code.strip().toLowerCase(Locale.ROOT));
    }

    public Lang resolve(String requested, Locale systemLocale) {
        Lang explicit = find(requested);
        if (explicit != null) return explicit;
        Locale system = systemLocale != null ? systemLocale : Locale.getDefault();
        Lang regional = find(system.toLanguageTag());
        if (regional != null) return regional;
        Lang base = find(system.getLanguage());
        return base != null ? base : languages.get("en");
    }
}
