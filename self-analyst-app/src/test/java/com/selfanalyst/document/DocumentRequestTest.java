package com.selfanalyst.document;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentRequestTest {
    private static final String BODY = "{\"schemaVersion\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"中文\"}]}";
    @Test void validatesSchemaAndFormatWithoutDroppingContent() {
        assertEquals("中文", DocumentRequest.parse("pdf", "报告", BODY).blocks().getFirst().text());
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("csv", "报告", BODY));
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("xlsx", "报告", BODY));
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("exe", "报告", BODY));
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("pdf", "报告", BODY.replace(":1", ":2")));
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("pdf", "报告", BODY.replace("\"text\":\"中文\"", "\"text\":3")));
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("pdf", "报告", BODY.replace("\"text\"", "\"script\"")));
    }
    @Test void limitsSizeDepthAndRowShape() {
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("json", "报告", " ".repeat(1024 * 1024 + 1)));
        String deep = "{\"schemaVersion\":1,\"metadata\":{\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}}";
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("json", "报告", deep));
        assertThrows(IllegalArgumentException.class, () -> DocumentRequest.parse("xlsx", "报告",
                "{\"schemaVersion\":1,\"sheets\":[{\"name\":\"数据\",\"columns\":[\"标题\"],\"rows\":[[1,2]]}]}"));
    }
}
