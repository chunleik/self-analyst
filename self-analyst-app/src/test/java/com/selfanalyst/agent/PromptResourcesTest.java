package com.selfanalyst.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromptResourcesTest {

    private static final List<String> PROMPT_FILES = List.of(
            "system.zh.md", "system.en.md",
            "memory-context.zh.md", "memory-context.en.md",
            "memory-empty.zh.md", "memory-empty.en.md",
            "plain-completion.zh.md", "plain-completion.en.md",
            "wiki-enabled.zh.md", "wiki-enabled.en.md",
            "wiki-disabled.zh.md", "wiki-disabled.en.md",
            "wiki-semantic.zh.md", "wiki-semantic.en.md",
            "file-tools.zh.md", "file-tools.en.md",
            "web-search.zh.md", "web-search.en.md",
            "config-tools.zh.md", "config-tools.en.md");

    @Test
    void allDocumentedPromptResourcesArePackagedAsUtf8() {
        for (String name : PROMPT_FILES) {
            String content = PromptResources.load(name);
            assertFalse(content.isBlank(), name + " must not be blank");
            assertFalse(content.startsWith("\uFEFF"), name + " must not expose a UTF-8 BOM");
        }
    }

    @Test
    void rendererRequiresAnExactPlaceholderSet() {
        assertEquals("Hello SelfAnalyst", PromptResources.renderTemplate(
                "Hello {{name}}", Map.of("name", "SelfAnalyst")));
        assertEquals("same/same", PromptResources.renderTemplate(
                "{{value}}/{{value}}", Map.of("value", "same")));

        assertThrows(IllegalArgumentException.class,
                () -> PromptResources.renderTemplate("Hello {{name}}", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PromptResources.renderTemplate("Hello", Map.of("name", "SelfAnalyst")));
    }

    @Test
    void rendererRejectsMalformedSyntaxWithoutRescanningReplacementValues() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptResources.renderTemplate("Hello {{Name}}", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PromptResources.renderTemplate("Hello {{name", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PromptResources.renderTemplate("Hello {{name}}}", Map.of("name", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> PromptResources.renderTemplate("Hello {{}}", Map.of()));

        assertEquals("Value $5\\{{user_text}}", PromptResources.renderTemplate(
                "Value {{value}}", Map.of("value", "$5\\{{user_text}}")));
    }

    @Test
    void utf8DecoderRejectsMalformedBytesAndBom() {
        assertThrows(IllegalStateException.class,
                () -> PromptResources.decodeUtf8("invalid", new byte[]{(byte) 0xC3, 0x28}));
        assertThrows(IllegalStateException.class,
                () -> PromptResources.decodeUtf8("bom",
                        new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x'}));
    }

    @Test
    void missingResourceFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> PromptResources.load("does-not-exist.md"));
        assertThrows(IllegalArgumentException.class,
                () -> PromptResources.load("../system.en.md"));
    }
}
