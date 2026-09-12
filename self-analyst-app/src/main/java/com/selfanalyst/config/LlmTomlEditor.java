package com.selfanalyst.config;

import org.tomlj.Toml;
import java.util.*;

/** 只编辑可证明安全的 LLM 单行赋值，所有候选均重新解析验证。 */
public final class LlmTomlEditor {
    private LlmTomlEditor() {}

    public static String edit(String text, Map<String, String> changes) {
        var expected = new LinkedHashMap<>(TomlSupport.parseAndFlatten(text));
        String result = text;
        for (var change : changes.entrySet()) {
            String key = change.getKey();
            if (!ConfigPolicy.LLM.contains(key)) throw new IllegalArgumentException("unsupported_field");
            String value = change.getValue();
            if (value == null) expected.remove(key);
            else expected.put(key, TomlSupport.parseAndFlatten(assignment(key, value)).get(key));
            result = editOne(result, key, value);
        }
        if (!TomlSupport.parseAndFlatten(result).equals(expected)) throw unsupported();
        return result;
    }

    private static String assignment(String key, String value) {
        return TomlSupport.emitAssignment(key, value, SupportedKeys.types().get(key));
    }

    private static String editOne(String text, String key, String value) {
        var parsed = Toml.parse(text);
        var path = List.of("llm", key.substring(4));
        var position = parsed.inputPositionOf(path);
        if (position == null) {
            if (value == null) return text;
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            String line = assignment(key, value).stripTrailing();
            String prefix = line + newline + text;
            if (matches(text, prefix, key, value)) return prefix;
            // 已有显式 llm 表不能由顶层点分赋值重复定义。
            int offset = 0;
            for (String raw : text.split("(?<=\n)", -1)) {
                String header = raw.strip();
                if (header.matches("\\[\\s*(?:llm|\"llm\"|'llm')\\s*]\\s*(?:#.*)?")) {
                    int insertion = offset + raw.length();
                    // 短键需要沿用完整键的类型。
                    String typed = line.substring(line.indexOf(".") + 1);
                    String candidate = text.substring(0, insertion) + (raw.endsWith("\n") ? "" : newline)
                            + typed + newline + text.substring(insertion);
                    if (matches(text, candidate, key, value)) return candidate;
                }
                offset += raw.length();
            }
            throw unsupported();
        }
        int start = 0;
        for (int n = 1; n < position.line(); n++) start = text.indexOf('\n', start) + 1;
        int keyStart = start + position.column() - 1;
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        int eq = outside(text, keyStart, end, '=');
        if (eq < 0) throw unsupported();
        int valueStart = eq + 1;
        while (valueStart < end && Character.isWhitespace(text.charAt(valueStart))) valueStart++;
        if (text.startsWith("\"\"\"", valueStart) || text.startsWith("'''", valueStart)) throw unsupported();
        int comment = outside(text, valueStart, end, '#');
        int valueEnd = comment < 0 ? end : comment;
        while (valueEnd > valueStart && Character.isWhitespace(text.charAt(valueEnd - 1))) valueEnd--;
        String original = text.substring(valueStart, valueEnd);
        var scalar = Toml.parse("v=" + original);
        if (scalar.hasErrors() || scalar.get("v") instanceof org.tomlj.TomlArray
                || scalar.get("v") instanceof org.tomlj.TomlTable) throw unsupported();
        String replacement = value == null ? "" : assignment(key, value).stripTrailing();
        if (value != null) replacement = replacement.substring(replacement.indexOf('=') + 1).strip();
        return value == null
                ? text.substring(0, keyStart) + text.substring(valueEnd)
                : text.substring(0, valueStart) + replacement + text.substring(valueEnd);
    }

    private static int outside(String text, int start, int end, char wanted) {
        char quote = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (quote == '"' && c == '\\') { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == wanted) return i;
            else if (c == '\'' || c == '"') quote = c;
        }
        return -1;
    }

    private static boolean matches(String before, String after, String key, String value) {
        try {
            var expected = TomlSupport.parseAndFlatten(before);
            expected.put(key, TomlSupport.parseAndFlatten(assignment(key, value)).get(key));
            return expected.equals(TomlSupport.parseAndFlatten(after));
        } catch (RuntimeException invalid) { return false; }
    }

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("unsupported_toml_edit");
    }
}
