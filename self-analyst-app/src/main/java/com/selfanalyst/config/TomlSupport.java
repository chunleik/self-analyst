package com.selfanalyst.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single entry point for the user-level TOML config (SPEC-TOML-*). All static,
 * no state. Responsibilities:
 * <ul>
 *   <li>{@link #parseAndFlatten(String)} — TOML v1.0 parse → depth-first flatten
 *       into dotted keys → value normalization to strings (SPEC-TOML-FMT-002/003).</li>
 *   <li>{@link #validateTypes(Map, Map)} — lenient known-key type check
 *       (SPEC-TOML-FMT-003d).</li>
 *   <li>{@link #generateToml(Map, Map)} — regenerate section-grouped TOML for
 *       structured saves and startup migration (SPEC-TOML-DEC-004).</li>
 *   <li>{@link #buildTemplate(Map, Map)} — commented TOML template served when the
 *       file is empty/missing (SPEC-TOML-FMT-004).</li>
 * </ul>
 * The internal namespace stays flat dotted keys ({@code llm.api-key}); TOML tables
 * are flattened away so every downstream consumer is format-agnostic
 * (SPEC-TOML-DEC-002/003).
 */
public final class TomlSupport {

    private TomlSupport() {}

    /** Declared value type for a supported key, driving validation + emission. */
    public enum KeyType { STRING, BOOLEAN, INTEGER, FLOAT, LIST }

    /**
     * Section grouping order for generation/template — mirrors the section order
     * historically used by {@code UserConfigStore.save()}. Keys not matching any
     * prefix are emitted as top-level dotted assignments before the first table.
     */
    static final List<String> SECTION_ORDER = List.of(
            "llm.", "aw.", "wiki.", "embedding.", "agent.", "desktop.", "websearch.", "file.");

    // ── Parse + flatten + normalize (SPEC-TOML-FMT-002/003) ──────────────

    /**
     * Parse {@code text} as TOML v1.0 and flatten to dotted keys with normalized
     * string values. Throws {@link TomlValidationException} on syntax errors
     * (with line/column), unsupported structures, or duplicate keys (which tomlj
     * surfaces as parse errors per TOML semantics — SPEC-TOML-FMT-002b).
     */
    public static LinkedHashMap<String, String> parseAndFlatten(String text) {
        TomlParseResult result = Toml.parse(text == null ? "" : text);
        if (result.hasErrors()) {
            List<String> msgs = new ArrayList<>();
            result.errors().forEach(e -> msgs.add(
                    "第 " + e.position().line() + " 行第 " + e.position().column()
                            + " 列: " + e.getMessage()));
            throw new TomlValidationException(msgs);
        }
        LinkedHashMap<String, String> flat = new LinkedHashMap<>();
        List<String> structureErrors = new ArrayList<>();
        // keyPathSet(false): leaf value paths only, tables already descended into,
        // so [aw.collection] window ≡ aw.collection.window (SPEC-TOML-FMT-002a).
        for (List<String> path : result.keyPathSet(false)) {
            String dotted = String.join(".", path);
            flat.put(dotted, normalizeValue(dotted, result.get(path), structureErrors));
        }
        if (!structureErrors.isEmpty()) {
            throw new TomlValidationException(structureErrors);
        }
        return flat;
    }

    /** Normalize a scalar/array value to its string form; collect rejections. */
    private static String normalizeValue(String key, Object value, List<String> errors) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value instanceof Long l) return Long.toString(l);
        if (value instanceof Double d) return Double.toString(d);
        if (value instanceof TomlArray arr) return normalizeArray(key, arr, errors);
        if (value instanceof LocalDate || value instanceof LocalTime
                || value instanceof LocalDateTime || value instanceof OffsetDateTime) {
            errors.add(key + ": 不支持日期时间类型值");
            return "";
        }
        if (value instanceof TomlTable) {
            errors.add(key + ": 不支持将内联表作为值");
            return "";
        }
        errors.add(key + ": 不支持的值类型 " + value.getClass().getSimpleName());
        return "";
    }

    /**
     * Normalize a primitive array to a comma-joined string
     * (SPEC-TOML-FMT-003b). Rejects arrays of tables/nested arrays, date-time
     * elements, and mixed-type arrays (SPEC-TOML-FMT-003c) — TOML 1.0 permits
     * heterogeneous arrays, so the mixed-type check is explicit here.
     */
    private static String normalizeArray(String key, TomlArray arr, List<String> errors) {
        // tomlj's containsTables()/containsArrays() are deprecated (arrays are
        // heterogeneous since 0.5.0) and throw, so inspect each element directly.
        List<String> parts = new ArrayList<>();
        String kind = null;
        for (int i = 0; i < arr.size(); i++) {
            Object el = arr.get(i);
            String elKind;
            String norm;
            if (el instanceof String s) { elKind = "string"; norm = s; }
            else if (el instanceof Boolean b) { elKind = "boolean"; norm = b ? "true" : "false"; }
            else if (el instanceof Long l) { elKind = "integer"; norm = Long.toString(l); }
            else if (el instanceof Double d) { elKind = "float"; norm = Double.toString(d); }
            else if (el instanceof TomlArray) {
                errors.add(key + ": 数组不支持嵌套数组");
                return "";
            } else if (el instanceof TomlTable) {
                errors.add(key + ": 数组不支持包含表");
                return "";
            } else {
                errors.add(key + ": 数组元素类型不受支持（日期时间或复合类型）");
                return "";
            }
            if (kind == null) {
                kind = elKind;
            } else if (!kind.equals(elKind)) {
                errors.add(key + ": 不支持混合类型数组");
                return "";
            }
            parts.add(norm);
        }
        return String.join(",", parts);
    }

    // ── Known-key type validation (SPEC-TOML-FMT-003d) ───────────────────

    /**
     * Lenient type check: a value already normalized to a string passes when it
     * <em>is</em> the declared type or losslessly parses to it ({@code port = "5600"}
     * passes). LIST/STRING always pass; unknown keys are skipped. Returns one
     * message per violation, empty list on success.
     */
    public static List<String> validateTypes(Map<String, String> flat, Map<String, KeyType> declared) {
        List<String> violations = new ArrayList<>();
        for (var e : flat.entrySet()) {
            KeyType type = declared.get(e.getKey());
            if (type == null) continue; // unknown keys → not type-checked
            String v = e.getValue();
            switch (type) {
                case BOOLEAN -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                        violations.add(e.getKey() + ": 期望布尔值, 实际 \"" + v + "\"");
                    }
                }
                case INTEGER -> {
                    try {
                        Long.parseLong(v.trim());
                    } catch (NumberFormatException ex) {
                        violations.add(e.getKey() + ": 期望整数, 实际 \"" + v + "\"");
                    }
                }
                case FLOAT -> {
                    try {
                        Double.parseDouble(v.trim());
                    } catch (NumberFormatException ex) {
                        violations.add(e.getKey() + ": 期望浮点数, 实际 \"" + v + "\"");
                    }
                }
                case STRING, LIST -> { /* always pass */ }
            }
        }
        return violations;
    }

    // ── Generation (SPEC-TOML-DEC-004) ───────────────────────────────────

    /**
     * Regenerate the whole file as section-grouped TOML for structured saves and
     * migration. Keys are bucketed by {@link #SECTION_ORDER}; unmatched keys are
     * emitted as top-level dotted assignments <em>before</em> the first table
     * (TOML requires that ordering). User comments are not preserved
     * (SPEC-TOML-NON-004).
     */
    public static String generateToml(Map<String, String> flat, Map<String, KeyType> declared) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SelfAnalyst 用户配置（config.toml）\n");

        Partition p = partition(flat);
        if (!p.topLevel.isEmpty()) {
            sb.append('\n');
            for (var e : p.topLevel.entrySet()) {
                sb.append(emitKey(e.getKey())).append(" = ")
                        .append(emitValue(e.getValue(), declared.get(e.getKey()))).append('\n');
            }
        }
        for (var sec : p.sections.entrySet()) {
            LinkedHashMap<String, String> keys = sec.getValue();
            if (keys.isEmpty()) continue;
            String table = sec.getKey().substring(0, sec.getKey().length() - 1);
            sb.append('\n').append('[').append(table).append("]\n");
            for (var e : keys.entrySet()) {
                String inTableKey = e.getKey().substring(sec.getKey().length());
                sb.append(emitKey(inTableKey)).append(" = ")
                        .append(emitValue(e.getValue(), declared.get(e.getKey()))).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Commented TOML template served when the user file is empty/missing
     * (SPEC-TOML-FMT-004): section table headers with every supported key on a
     * {@code #}-commented line showing its default value and a type hint, plus a
     * Windows-path guidance comment (SPEC-TOML-FMT-004b).
     */
    public static String buildTemplate(Map<String, String> defaults, Map<String, KeyType> declared) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SelfAnalyst 用户配置（config.toml）\n");
        sb.append("# 仅填写需要覆盖的项，取消对应行注释后修改即可；未列出的项使用默认值。\n");
        sb.append("# 路径值请用单引号字面量字符串：'D:\\docs'，或改用正斜杠。\n");

        Partition p = partition(defaults);
        if (!p.topLevel.isEmpty()) {
            sb.append('\n');
            for (var e : p.topLevel.entrySet()) {
                appendTemplateLine(sb, e.getKey(), e.getValue(), declared.get(e.getKey()));
            }
        }
        for (var sec : p.sections.entrySet()) {
            LinkedHashMap<String, String> keys = sec.getValue();
            if (keys.isEmpty()) continue;
            String table = sec.getKey().substring(0, sec.getKey().length() - 1);
            sb.append('\n').append('[').append(table).append("]\n");
            for (var e : keys.entrySet()) {
                String inTableKey = e.getKey().substring(sec.getKey().length());
                appendTemplateLine(sb, inTableKey, e.getValue(), declared.get(e.getKey()));
            }
        }
        return sb.toString();
    }

    private static void appendTemplateLine(StringBuilder sb, String key, String value, KeyType type) {
        sb.append("# ").append(emitKey(key)).append(" = ")
                .append(emitValue(value, type))
                .append("  # ").append(typeHint(type)).append('\n');
    }

    // ── Partitioning + emission helpers ──────────────────────────────────

    /** Grouped keys: matched-by-prefix sections (order preserved) + top-level rest. */
    private record Partition(LinkedHashMap<String, LinkedHashMap<String, String>> sections,
                             LinkedHashMap<String, String> topLevel) {}

    private static Partition partition(Map<String, String> flat) {
        LinkedHashMap<String, LinkedHashMap<String, String>> sections = new LinkedHashMap<>();
        for (String prefix : SECTION_ORDER) {
            sections.put(prefix, new LinkedHashMap<>());
        }
        LinkedHashMap<String, String> topLevel = new LinkedHashMap<>();
        for (var e : flat.entrySet()) {
            String matched = null;
            for (String prefix : SECTION_ORDER) {
                if (e.getKey().startsWith(prefix)) {
                    matched = prefix;
                    break;
                }
            }
            if (matched == null) {
                topLevel.put(e.getKey(), e.getValue());
            } else {
                sections.get(matched).put(e.getKey(), e.getValue());
            }
        }
        return new Partition(sections, topLevel);
    }

    private static String emitKey(String dotted) {
        String[] segments = dotted.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(emitKeySegment(segments[i]));
        }
        return sb.toString();
    }

    private static String emitKeySegment(String seg) {
        if (seg.matches("[A-Za-z0-9_-]+")) return seg;
        return "\"" + seg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Emit a value: typed scalars ({@code true}/{@code 5700}/{@code 0.7}) bare when
     * they parse; otherwise a string — literal {@code '...'} when it contains a
     * backslash and no single quote (Windows paths stay readable, SPEC-TOML-GOAL-002),
     * else a basic {@code "..."} with standard escapes.
     */
    private static String emitValue(String value, KeyType type) {
        if (type == KeyType.BOOLEAN
                && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
            return value.toLowerCase();
        }
        if (type == KeyType.INTEGER) {
            try {
                return Long.toString(Long.parseLong(value.trim()));
            } catch (NumberFormatException ignored) { /* fall through to string */ }
        }
        if (type == KeyType.FLOAT) {
            try {
                return Double.toString(Double.parseDouble(value.trim()));
            } catch (NumberFormatException ignored) { /* fall through to string */ }
        }
        if (value.indexOf('\\') >= 0 && value.indexOf('\'') < 0) {
            return "'" + value + "'"; // literal string — backslashes preserved verbatim
        }
        return "\"" + escapeBasic(value) + "\"";
    }

    private static String escapeBasic(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String typeHint(KeyType type) {
        if (type == null) return "string";
        return switch (type) {
            case STRING -> "string";
            case BOOLEAN -> "boolean";
            case INTEGER -> "integer";
            case FLOAT -> "float";
            case LIST -> "list";
        };
    }
}
