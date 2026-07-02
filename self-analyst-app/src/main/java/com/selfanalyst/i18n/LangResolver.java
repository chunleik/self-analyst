package com.selfanalyst.i18n;

import java.util.Locale;

/**
 * 有效语言的唯一解析入口（SPEC-I18N-DEC-003、SPEC-I18N-RES-001/002）。
 * 后端各提示词构建点与前端渲染点都从这里取结论，不各自重复判定。
 */
public final class LangResolver {

    private LangResolver() {}

    /**
     * 解析有效语言（SPEC-I18N-RES-001）：
     * <ul>
     *   <li>{@code app.language} 为 {@code "zh"}/{@code "en"} → 直接返回；</li>
     *   <li>{@code "auto"} 或 null/空/未知 → 看 {@code systemLocale} 的语言代码：
     *       以 {@code "zh"} 开头则 {@link Lang#ZH}，否则 {@link Lang#EN}。</li>
     * </ul>
     */
    public static Lang resolve(String appLanguage, Locale systemLocale) {
        Lang explicit = Lang.fromCode(appLanguage);
        if (explicit != null) return explicit;
        Locale locale = systemLocale != null ? systemLocale : Locale.getDefault();
        String lang = locale.getLanguage();
        return (lang != null && lang.toLowerCase().startsWith("zh")) ? Lang.ZH : Lang.EN;
    }

    /** 便捷重载：用 {@link Locale#getDefault()} 作为系统 Locale。 */
    public static Lang resolve(String appLanguage) {
        return resolve(appLanguage, Locale.getDefault());
    }
}
