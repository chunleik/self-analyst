package com.selfanalyst.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Language resolution contract (SPEC-I18N-RES-001/002, TST-001..005, 015). */
class LangResolverTest {

    @Test
    void regionalAndUnsupportedSystemLanguages() {
        assertEquals(Lang.chinese(), LangResolver.resolve("auto", Locale.TAIWAN));
        assertEquals(Lang.english(), LangResolver.resolve("auto", Locale.JAPAN));
    }

    @Test
    void injectedLanguageDoesNotChangeProductionRegistry() {
        var french = new Lang("fr", "Français", "fr-FR", "fr");
        var registry = new LanguageRegistry(java.util.List.of(Lang.chinese(), Lang.english(), french));
        assertEquals(french, registry.resolve(" FR ", Locale.CHINA));
        assertEquals(french, registry.resolve("auto", Locale.CANADA_FRENCH));
        assertEquals(Locale.FRANCE, french.locale());
        assertEquals(Lang.english(), LangResolver.resolve("fr", Locale.FRANCE));
        assertEquals(2, LanguageRegistry.bundled().supported().size());
    }

    @Test
    void explicitZh() { // TST-001
        assertEquals(Lang.chinese(), LangResolver.resolve("zh", Locale.US));
    }

    @Test
    void explicitEn() { // TST-002
        assertEquals(Lang.english(), LangResolver.resolve("en", Locale.CHINA));
    }

    @Test
    void autoWithChineseLocale() { // TST-003
        assertEquals(Lang.chinese(), LangResolver.resolve("auto", Locale.CHINA));
    }

    @Test
    void autoWithNonChineseLocale() { // TST-004
        assertEquals(Lang.english(), LangResolver.resolve("auto", Locale.US));
    }

    @Test
    void illegalValueFallsBackToAuto() { // TST-005
        assertEquals(Lang.english(), LangResolver.resolve("bogus", Locale.US));
        assertEquals(Lang.chinese(), LangResolver.resolve("bogus", Locale.CHINA));
    }

    @Test
    void blankAndNullEquivalentToAuto() { // TST-015
        assertEquals(Lang.chinese(), LangResolver.resolve(null, Locale.CHINA));
        assertEquals(Lang.english(), LangResolver.resolve("", Locale.US));
        assertEquals(Lang.chinese(), LangResolver.resolve("   ", Locale.CHINA));
    }

    @Test
    void caseAndWhitespaceInsensitive() {
        assertEquals(Lang.english(), LangResolver.resolve(" EN ", Locale.CHINA));
        assertEquals(Lang.chinese(), LangResolver.resolve("ZH", Locale.US));
    }
}
