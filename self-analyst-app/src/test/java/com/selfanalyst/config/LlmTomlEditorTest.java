package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmTomlEditorTest {
    @Test void preservesUnrelatedTextAndQuotedValues() {
        String text = "# 中文\r\n[\"llm\"] # 连接\r\n\"model\" = 'old=#'  # 模型\r\n"
                + "[file]\r\nwatch.paths='D:\\资料'\r\n[other]\r\ntext=\"\"\"\nmodel='fake'\n\"\"\"\r\n";
        String edited = LlmTomlEditor.edit(text, Map.of("llm.model", "new"));
        assertEquals(text.replace("'old=#'", "\"new\""), edited);
        String added = LlmTomlEditor.edit(text, Map.of("llm.max-tokens", "64"));
        assertTrue(added.contains("max-tokens = 64\r\n"));
        assertEquals("64", TomlSupport.parseAndFlatten(added).get("llm.max-tokens"));
    }
    @Test void dottedKeysAndRemovalPreserveComment() {
        String text = "llm.\"model\"='old' # keep\nllm.temperature=0.3\n";
        var changes = new LinkedHashMap<String, String>(); changes.put("llm.model", null);
        String edited = LlmTomlEditor.edit(text, changes);
        assertEquals(" # keep\nllm.temperature=0.3\n", edited);
        assertEquals("x", TomlSupport.parseAndFlatten(LlmTomlEditor.edit(text, Map.of("llm.model", "x"))).get("llm.model"));
    }
    @Test void emptyExplicitClearAndUnsafeInput() {
        assertEquals("llm.api-key = \"\"\n", LlmTomlEditor.edit("", Map.of("llm.api-key", "")));
        assertThrows(IllegalArgumentException.class, () -> LlmTomlEditor.edit("[llm]\nmodel=\"\"\"long\nmodel\"\"\"", Map.of("llm.model", "x")));
        assertThrows(IllegalArgumentException.class, () -> LlmTomlEditor.edit("", Map.of("events.port", "1234")));
        assertThrows(RuntimeException.class, () -> LlmTomlEditor.edit("[invalid", Map.of("llm.model", "x")));
    }
    @Test void multipleEditsDoNotChangeOtherValues() {
        String text = "[llm]\nmodel='x'\ntemperature=0.7\n[unknown]\nvalue=['a','b']\n";
        String result = LlmTomlEditor.edit(text, Map.of("llm.model", "y", "llm.temperature", "1.0", "llm.max-tokens", "0"));
        assertTrue(result.endsWith("[unknown]\nvalue=['a','b']\n"));
        assertEquals("1", TomlSupport.parseAndFlatten(result).get("llm.temperature"));
    }
}
