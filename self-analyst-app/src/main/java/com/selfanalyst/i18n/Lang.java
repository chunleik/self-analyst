package com.selfanalyst.i18n;

/**
 * 有效语言（effective language）：SelfAnalyst 仅支持中文与英文两种（SPEC-I18N-DEC-002）。
 * 后端提示词与前端文案均按此枚举选用对应语言变体。
 */
public enum Lang {
    ZH("zh"),
    EN("en");

    private final String code;

    Lang(String code) {
        this.code = code;
    }

    /** 语言代码（{@code "zh"} / {@code "en"}）。 */
    public String code() {
        return code;
    }

    /**
     * 由语言代码解析枚举：去空白、小写后匹配 {@code "zh"}/{@code "en"}；
     * 未知（含 {@code "auto"}、null、空）返回 {@code null}（交由 {@link LangResolver} 兜底）。
     */
    public static Lang fromCode(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase();
        if (c.equals("zh")) return ZH;
        if (c.equals("en")) return EN;
        return null;
    }
}
