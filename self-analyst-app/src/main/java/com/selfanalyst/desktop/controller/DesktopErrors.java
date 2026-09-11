package com.selfanalyst.desktop.controller;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.i18n.Messages;
import io.javalin.http.Context;
import java.util.LinkedHashMap;
import java.util.Map;

/** 桌面响应的兼容错误字段、稳定代码和参数。 */
public final class DesktopErrors {
    private DesktopErrors() {}

    public static Map<String, Object> payload(Context ctx, String code, Map<String, ?> parameters) {
        Lang language = ctx.attribute("selfanalyst.language");
        return payload(language != null ? language : Lang.english(), code, parameters);
    }

    public static Map<String, Object> payload(Lang language, String code, Map<String, ?> parameters) {
        var result = new LinkedHashMap<String, Object>();
        result.put("error", Messages.text(language, code, parameters));
        result.put("errorCode", code);
        result.put("errorParams", parameters);
        return result;
    }

    public static Map<String, Object> failure(Context ctx, Exception error) {
        if (error instanceof UserMessageException localized) {
            return payload(ctx, localized.code(), localized.parameters());
        }
        return payload(ctx, "error.invalidRequest", Map.of("detail", error.getMessage() == null ? "" : error.getMessage()));
    }

    public static final class UserMessageException extends IllegalArgumentException {
        private final String code;
        private final Map<String, ?> parameters;

        public UserMessageException(String code, Map<String, ?> parameters) {
            super(Messages.text(Lang.chinese(), code, parameters));
            this.code = code;
            this.parameters = Map.copyOf(parameters);
        }
        public String code() { return code; }
        public Map<String, ?> parameters() { return parameters; }
    }
}
