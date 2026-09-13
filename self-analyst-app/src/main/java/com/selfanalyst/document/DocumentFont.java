package com.selfanalyst.document;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class DocumentFont {
    static final String RESOURCE = "/document-fonts/NotoSansSC-Regular.ttf";
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);
    private static final Font FONT = load();
    private DocumentFont() {}
    static InputStream stream() throws IOException {
        var stream = DocumentFont.class.getResourceAsStream(RESOURCE);
        if (stream == null) throw new IOException("缺少文档中文字体");
        return stream;
    }
    private static Font load() {
        try (var stream = stream()) { return Font.createFont(Font.TRUETYPE_FONT, stream); }
        catch (Exception e) { throw new IllegalStateException("文档字体不可用", e); }
    }
    static Font awt(float size) { return FONT.deriveFont(size); }
    static List<String> wrap(String text, float size, double width) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            double used = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int cp = paragraph.codePointAt(offset);
                String ch = new String(Character.toChars(cp));
                if (!FONT.canDisplay(cp)) throw new IllegalArgumentException("文档字体不支持字符 U+" + Integer.toHexString(cp));
                double charWidth = FONT.deriveFont(size).getStringBounds(ch, CONTEXT).getWidth();
                if (charWidth > width) throw new IllegalArgumentException("文档列宽不足，请减少列数");
                if (used + charWidth > width && !line.isEmpty()) {
                    result.add(line.toString()); line.setLength(0); used = 0;
                }
                line.append(ch); used += charWidth; offset += Character.charCount(cp);
            }
            result.add(line.toString());
        }
        return result;
    }
}
