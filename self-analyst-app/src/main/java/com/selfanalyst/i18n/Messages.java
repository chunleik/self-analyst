package com.selfanalyst.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 后端固定文案的通用目录与单次参数替换。 */
public final class Messages {
    private static final Pattern PARAMETER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();
    private Messages() {}

    public static String text(Lang lang, String key) { return text(lang, key, Map.of()); }

    public static String text(Lang lang, String key, Map<String, ?> parameters) {
        String resource = lang != null ? lang.resource() : "en";
        String template = catalog(resource).get(key);
        if (template == null) template = catalog("en").getOrDefault(key, key);
        return render(template, parameters);
    }

    public static String render(String template, Map<String, ?> parameters) {
        var matcher = PARAMETER.matcher(template);
        var result = new StringBuilder();
        while (matcher.find()) {
            Object value = parameters.get(matcher.group(1));
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    value == null ? matcher.group() : value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Map<String, String> catalog(String resource) {
        return CACHE.computeIfAbsent(resource, code -> {
            try (InputStream input = Messages.class.getResourceAsStream("/i18n/messages/" + code + ".json")) {
                if (input == null) return Map.of();
                return Map.copyOf(new ObjectMapper().readValue(input, new TypeReference<Map<String, String>>() {}));
            } catch (Exception invalid) {
                return Map.of();
            }
        });
    }
}
