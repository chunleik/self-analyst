package com.selfanalyst.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Language resolution contract (SPEC-I18N-RES-001/002, TST-001..005, 015). */
class LangResolverTest {

    @Test
    void explicitZh() { // TST-001
        assertEquals(Lang.ZH, LangResolver.resolve("zh", Locale.US));
    }

    @Test
    void explicitEn() { // TST-002
        assertEquals(Lang.EN, LangResolver.resolve("en", Locale.CHINA));
    }

    @Test
    void autoWithChineseLocale() { // TST-003
        assertEquals(Lang.ZH, LangResolver.resolve("auto", Locale.CHINA));
    }

    @Test
    void autoWithNonChineseLocale() { // TST-004
        assertEquals(Lang.EN, LangResolver.resolve("auto", Locale.US));
    }

    @Test
    void illegalValueFallsBackToAuto() { // TST-005
        assertEquals(Lang.EN, LangResolver.resolve("bogus", Locale.US));
        assertEquals(Lang.ZH, LangResolver.resolve("bogus", Locale.CHINA));
    }

    @Test
    void blankAndNullEquivalentToAuto() { // TST-015
        assertEquals(Lang.ZH, LangResolver.resolve(null, Locale.CHINA));
        assertEquals(Lang.EN, LangResolver.resolve("", Locale.US));
        assertEquals(Lang.ZH, LangResolver.resolve("   ", Locale.CHINA));
    }

    @Test
    void caseAndWhitespaceInsensitive() {
        assertEquals(Lang.EN, LangResolver.resolve(" EN ", Locale.CHINA));
        assertEquals(Lang.ZH, LangResolver.resolve("ZH", Locale.US));
    }
}
