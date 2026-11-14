package com.selfanalyst.mcp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class McpTelemetryMediaTest {
    private static boolean media(String json) throws Exception {
        var method=McpManager.class.getDeclaredMethod("containsMedia", com.fasterxml.jackson.databind.JsonNode.class,int.class);
        method.setAccessible(true);return (boolean)method.invoke(null,new ObjectMapper().readTree(json),0);
    }
    @Test void tinyImagesAudioAndResourceAttachmentsAreIneligibleRegardlessOfEncodingLength() throws Exception {
        assertTrue(media("{\"untrustedExternalResult\":{\"content\":[{\"type\":\"image\",\"data\":\"aA==\",\"mimeType\":\"image/png\"}]}}"));
        assertTrue(media("{\"content\":[{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///x\",\"blob\":\"aA==\"}}]}"));
        assertTrue(media("{\"content\":[{\"type\":\"audio\",\"data\":\"aA==\"}]}"));
        assertFalse(media("{\"content\":[{\"type\":\"text\",\"text\":\"ordinary result\"}]}"));
    }
}
