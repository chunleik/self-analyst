package com.selfanalyst.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads and renders the bundled, developer-editable Agent prompt documents. */
final class PromptResources {

    private static final String ROOT = "/prompts/agent/";
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9.-]+\\.md");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9_]+)}}");
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[a-z0-9_]+");
    private static final ConcurrentMap<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptResources() {}

    static String load(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid prompt resource name: " + name);
        }
        return CACHE.computeIfAbsent(name, PromptResources::read);
    }

    static String render(String name, Map<String, String> values) {
        return renderTemplate(load(name), values);
    }

    static String renderTemplate(String template, Map<String, String> values) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(values, "values");

        Set<String> expected = parsePlaceholders(template);
        if (!expected.equals(values.keySet())) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(values.keySet());
            Set<String> unexpected = new LinkedHashSet<>(values.keySet());
            unexpected.removeAll(expected);
            throw new IllegalArgumentException(
                    "Prompt placeholder mismatch; missing=" + missing + ", unexpected=" + unexpected);
        }

        Matcher renderer = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder(template.length() + 256);
        while (renderer.find()) {
            String value = Objects.requireNonNull(values.get(renderer.group(1)),
                    "Prompt placeholder value: " + renderer.group(1));
            renderer.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        renderer.appendTail(result);
        return result.toString();
    }

    private static String read(String name) {
        String path = ROOT + name;
        try (InputStream input = PromptResources.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Prompt resource not found: " + path);
            }
            return decodeUtf8(path, input.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + path, e);
        }
    }

    static String decodeUtf8(String source, byte[] bytes) {
        try {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (content.startsWith("\uFEFF")) {
                throw new IllegalStateException("Prompt resource must not contain a BOM: " + source);
            }
            content = content.replace("\r\n", "\n").replace('\r', '\n');
            return content.endsWith("\n")
                    ? content.substring(0, content.length() - 1) : content;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Prompt resource is not valid UTF-8: " + source, e);
        }
    }

    private static Set<String> parsePlaceholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            int strayClose = template.indexOf("}}", cursor);
            if (strayClose >= 0 && (open < 0 || strayClose < open)) {
                throw new IllegalArgumentException("Malformed prompt placeholder at index " + strayClose);
            }
            if (open < 0) {
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0 || close + 2 < template.length()
                    && template.charAt(close + 2) == '}') {
                throw new IllegalArgumentException("Malformed prompt placeholder at index " + open);
            }
            String name = template.substring(open + 2, close);
            if (!PLACEHOLDER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Malformed prompt placeholder at index " + open);
            }
            names.add(name);
            cursor = close + 2;
        }
        return names;
    }
}
