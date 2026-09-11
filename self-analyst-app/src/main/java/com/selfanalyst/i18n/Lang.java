package com.selfanalyst.i18n;

import java.util.Locale;
import java.util.Objects;

/** 语言元数据；正式语言由共享清单注册，业务代码不依赖语言枚举。 */
public record Lang(String code, String displayName, String dateLocale, String resource) {
    public static Lang chinese() { return LanguageRegistry.bundled().find("zh"); }
    public static Lang english() { return LanguageRegistry.bundled().find("en"); }

    public Lang {
        Objects.requireNonNull(code);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(dateLocale);
        Objects.requireNonNull(resource);
        if (!code.matches("[a-z]{2,8}(?:-[a-z0-9]{2,8})*")
                || !resource.matches("[a-z]{2,8}(?:-[a-z0-9]{2,8})*")) {
            throw new IllegalArgumentException("Invalid language resource identifier: " + code);
        }
        if (Locale.forLanguageTag(dateLocale).getLanguage().isEmpty()) {
            throw new IllegalArgumentException("Invalid date locale: " + dateLocale);
        }
    }

    public Locale locale() { return Locale.forLanguageTag(dateLocale); }

    public static Lang fromCode(String code) { return LanguageRegistry.bundled().find(code); }
}
