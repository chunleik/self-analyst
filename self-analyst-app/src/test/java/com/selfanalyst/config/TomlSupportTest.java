package com.selfanalyst.config;

import com.selfanalyst.config.TomlSupport.KeyType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TOML parse/flatten/normalize/type/generation unit tests (SPEC-TOML-TST-001/002/003/012). */
class TomlSupportTest {

    private static Map<String, KeyType> declared() {
        Map<String, KeyType> m = new LinkedHashMap<>();
        m.put("llm.api-key", KeyType.STRING);
        m.put("llm.base-url", KeyType.STRING);
        m.put("llm.model", KeyType.STRING);
        m.put("llm.temperature", KeyType.FLOAT);
        m.put("aw.port", KeyType.INTEGER);
        m.put("aw.collection.window", KeyType.BOOLEAN);
        m.put("memory.dir", KeyType.STRING);
        m.put("file.watch.extensions", KeyType.LIST);
        return m;
    }

    // SPEC-TOML-TST-001: table / sub-table / top-level dotted all flatten equally.
    @Test
    void flattenEquivalenceAcrossTableForms() {
        String toml = ""
                + "aw.port = 5600\n" // top-level dotted (must precede its tables)
                + "[llm]\n"
                + "api-key = \"sk-1\"\n"
                + "[aw.collection]\n"
                + "window = true\n";

        LinkedHashMap<String, String> flat = TomlSupport.parseAndFlatten(toml);
        assertEquals("sk-1", flat.get("llm.api-key"));
        assertEquals("true", flat.get("aw.collection.window"));
        assertEquals("5600", flat.get("aw.port"));
    }

    // SPEC-TOML-TST-002: literal string preserves backslashes verbatim.
    @Test
    void literalStringPreservesBackslashPath() {
        LinkedHashMap<String, String> flat =
                TomlSupport.parseAndFlatten("memory.dir = 'D:\\docs'\n");
        assertEquals("D:\\docs", flat.get("memory.dir"));
    }

    // SPEC-TOML-TST-003: boolean/number/array normalization.
    @Test
    void scalarAndArrayNormalization() {
        String toml = ""
                + "b = true\n"
                + "i = 42\n"
                + "f = 0.7\n"
                + "arr = [\"md\", \"txt\"]\n";
        LinkedHashMap<String, String> flat = TomlSupport.parseAndFlatten(toml);
        assertEquals("true", flat.get("b"));
        assertEquals("42", flat.get("i"));
        assertEquals("0.7", flat.get("f"));
        assertEquals("md,txt", flat.get("arr"));
    }

    @Test
    void utf8ChineseValueRoundTrips() {
        LinkedHashMap<String, String> flat =
                TomlSupport.parseAndFlatten("llm.model = \"智谱-glm\"\n");
        assertEquals("智谱-glm", flat.get("llm.model"));
    }

    // SPEC-TOML-FMT-003c: date-time rejected.
    @Test
    void dateTimeValueRejected() {
        assertThrows(TomlValidationException.class,
                () -> TomlSupport.parseAndFlatten("when = 1979-05-27T07:32:00Z\n"));
    }

    // SPEC-TOML-FMT-003c: mixed-type array rejected (TOML 1.0 allows them, we don't).
    @Test
    void mixedTypeArrayRejected() {
        assertThrows(TomlValidationException.class,
                () -> TomlSupport.parseAndFlatten("arr = [1, \"a\"]\n"));
    }

    // SPEC-TOML-API-001b: syntax error carries line/column.
    @Test
    void syntaxErrorReportsLineAndColumn() {
        TomlValidationException ex = assertThrows(TomlValidationException.class,
                () -> TomlSupport.parseAndFlatten("llm.model = \n"));
        assertFalse(ex.messages().isEmpty());
        assertTrue(ex.messages().get(0).contains("行"), ex.getMessage());
        assertTrue(ex.messages().get(0).contains("列"), ex.getMessage());
    }

    // SPEC-TOML-FMT-002b: duplicate key surfaces as parse error.
    @Test
    void duplicateKeyRejected() {
        assertThrows(TomlValidationException.class,
                () -> TomlSupport.parseAndFlatten("a = 1\na = 2\n"));
    }

    // SPEC-TOML-FMT-003d: lenient type validation.
    @Test
    void typeValidationIsLenient() {
        Map<String, String> ok = Map.of("aw.port", "5600", "aw.collection.window", "true");
        assertTrue(TomlSupport.validateTypes(ok, declared()).isEmpty());

        List<String> bad = TomlSupport.validateTypes(Map.of("aw.port", "abc"), declared());
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).contains("aw.port"), bad.get(0));
        assertTrue(bad.get(0).contains("整数"), bad.get(0));

        // temperature = true is not a float.
        List<String> badFloat = TomlSupport.validateTypes(Map.of("llm.temperature", "true"), declared());
        assertEquals(1, badFloat.size());

        // Unknown keys are never type-checked.
        assertTrue(TomlSupport.validateTypes(Map.of("foo.bar", "whatever"), declared()).isEmpty());
    }

    // generateToml → parseAndFlatten round-trips to an equal flat map.
    @Test
    void generateThenParseRoundTrips() {
        LinkedHashMap<String, String> flat = new LinkedHashMap<>();
        flat.put("llm.api-key", "sk-1");
        flat.put("llm.temperature", "0.7");
        flat.put("aw.port", "5700");
        flat.put("aw.collection.window", "true");
        flat.put("memory.dir", "D:\\docs\\中文");
        flat.put("file.watch.extensions", "md,txt");

        String toml = TomlSupport.generateToml(flat, declared());
        LinkedHashMap<String, String> back = TomlSupport.parseAndFlatten(toml);
        assertEquals(flat, back);
    }

    @Test
    void generateEmitsWindowsPathAsLiteralString() {
        String toml = TomlSupport.generateToml(
                Map.of("memory.dir", "D:\\docs"), declared());
        assertTrue(toml.contains("'D:\\docs'"), toml);
    }

    @Test
    void generateEmitsTopLevelKeysBeforeTables() {
        LinkedHashMap<String, String> flat = new LinkedHashMap<>();
        flat.put("memory.dir", "D:/x");
        flat.put("llm.model", "gpt-4o");
        String toml = TomlSupport.generateToml(flat, declared());
        int topIdx = toml.indexOf("memory.dir");
        int tableIdx = toml.indexOf("[llm]");
        assertTrue(topIdx >= 0 && tableIdx >= 0 && topIdx < tableIdx, toml);
    }

    // SPEC-TOML-TST-012 shape: template has table headers + path guidance and is valid TOML.
    @Test
    void templateHasTableHeadersAndParsesAsValidToml() {
        LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
        defaults.put("llm.api-key", "");
        defaults.put("llm.temperature", "0.7");
        defaults.put("aw.port", "5700");

        String template = TomlSupport.buildTemplate(defaults, declared(), Map.of());
        assertTrue(template.contains("[llm]"), template);
        assertTrue(template.contains("[aw]"), template);
        assertTrue(template.contains("D:\\docs"), template); // path guidance comment
        assertTrue(template.contains("# api-key ="), template);

        // With every key commented out the template must still parse (empty tables).
        assertTrue(TomlSupport.parseAndFlatten(template).isEmpty());
    }

    @Test
    void completeTemplateCommentsEverySupportedKeyWithBilingualHelp() {
        var defaults = SupportedKeys.defaults();
        var descriptions = SupportedKeys.descriptions();
        String template = TomlSupport.buildTemplate(
                defaults, SupportedKeys.types(), descriptions);

        assertEquals(defaults.keySet(), descriptions.keySet());
        assertEquals(defaults.size(), template.lines()
                .filter(line -> line.matches("# .+ = .*  # (string|boolean|integer|float|list)"))
                .count());
        for (var description : descriptions.values()) {
            assertFalse(description.zh().isBlank());
            assertFalse(description.en().isBlank());
            assertTrue(template.contains("# 中文：" + description.zh()), description.zh());
            assertTrue(template.contains("# English: " + description.en()), description.en());
        }
        assertTrue(TomlSupport.parseAndFlatten(template).isEmpty());
    }
}
