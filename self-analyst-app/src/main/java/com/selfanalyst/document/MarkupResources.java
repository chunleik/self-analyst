package com.selfanalyst.document;

import java.util.Locale;

/** 只读取 CSS token 与资源引用，不执行 CSS，也不改写调用方源码。 */
final class MarkupResources {
    private MarkupResources() {}

    static void reference(String value, boolean embeddedImages) {
        String reference = value.strip();
        if (reference.startsWith("#") && reference.length() > 1) return;
        if (embeddedImages && reference.matches("(?is)data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=\\s]+")) return;
        throw new IllegalArgumentException("文档必须自包含：资源只允许内部 #引用" + (embeddedImages ? "或内嵌位图" : ""));
    }

    static void css(String css, boolean embeddedImages, DocumentBudget budget) {
        new CssTokens(css, embeddedImages, budget).scan();
    }

    /** 解码标识符和字符串中的 CSS 转义，避免 url/import 被转义或注释隐藏。 */
    private static final class CssTokens {
        private final String text;
        private final boolean embeddedImages;
        private final DocumentBudget budget;
        private int position;

        CssTokens(String text, boolean embeddedImages, DocumentBudget budget) {
            this.text = text; this.embeddedImages = embeddedImages; this.budget = budget;
        }

        void scan() {
            while (position < text.length()) {
                budget.check();
                skipSpace();
                if (position == text.length()) break;
                char c = text.charAt(position);
                if (c == '\'' || c == '"') { string(); continue; }
                if (c == '@') {
                    position++;
                    String rule = identifier();
                    if (rule.equals("import") || rule.equals("namespace") || rule.equals("charset"))
                        throw new IllegalArgumentException("文档 CSS 不支持外部导入或编码声明");
                    if (rule.equals("font-face")) throw new IllegalArgumentException("离线文档请使用系统字体，不支持字体资源声明");
                    continue;
                }
                if (name(c)) {
                    String name = identifier();
                    skipSpace();
                    if (position < text.length() && text.charAt(position) == '(') {
                        position++;
                        if (name.equals("url")) url();
                        else if (name.equals("image-set") || name.equals("-webkit-image-set") || name.equals("src")
                                || name.equals("image") || name.equals("expression"))
                            throw new IllegalArgumentException("文档 CSS 不支持该资源函数，请使用内部 url(#id)");
                    }
                } else position++;
            }
        }

        private void url() {
            skipSpace();
            if (position == text.length()) throw invalid();
            String value;
            if (text.charAt(position) == '\'' || text.charAt(position) == '"') value = string();
            else {
                StringBuilder result = new StringBuilder();
                while (position < text.length() && text.charAt(position) != ')' && !Character.isWhitespace(text.charAt(position))) {
                    char c = text.charAt(position++);
                    if (c == '\\') result.appendCodePoint(escape());
                    else if (c == '(' || c == '\'' || c == '"' || c < 32) throw invalid();
                    else result.append(c);
                }
                value = result.toString();
            }
            skipSpace();
            if (position == text.length() || text.charAt(position++) != ')') throw invalid();
            reference(value, embeddedImages);
        }

        private String identifier() {
            StringBuilder result = new StringBuilder();
            while (position < text.length() && name(text.charAt(position))) {
                char c = text.charAt(position++);
                if (c == '\\') result.appendCodePoint(escape()); else result.append(c);
            }
            return result.toString().toLowerCase(Locale.ROOT);
        }

        private String string() {
            char quote = text.charAt(position++);
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == quote) return result.toString();
                if (c == '\\') result.appendCodePoint(escape());
                else if (c == '\n' || c == '\r' || c == '\f') throw invalid();
                else result.append(c);
            }
            throw invalid();
        }

        private int escape() {
            if (position == text.length()) throw invalid();
            int value = 0, count = 0;
            while (position < text.length() && count < 6) {
                int digit = Character.digit(text.charAt(position), 16);
                if (digit < 0) break;
                value = value * 16 + digit; count++; position++;
            }
            if (count == 0) {
                char c = text.charAt(position++);
                if (c == '\n' || c == '\r' || c == '\f') throw invalid();
                return c;
            }
            if (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                if (text.charAt(position++) == '\r' && position < text.length() && text.charAt(position) == '\n') position++;
            }
            return value == 0 || value > 0x10ffff || value >= 0xd800 && value <= 0xdfff ? 0xfffd : value;
        }

        private void skipSpace() {
            while (position < text.length()) {
                if (Character.isWhitespace(text.charAt(position))) position++;
                else if (text.startsWith("/*", position)) {
                    int end = text.indexOf("*/", position + 2);
                    if (end < 0) throw invalid();
                    position = end + 2;
                } else break;
            }
        }

        private static boolean name(char c) {
            return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '\\' || c >= 128;
        }
        private static IllegalArgumentException invalid() { return new IllegalArgumentException("文档 CSS 资源表达式不完整"); }
    }
}
