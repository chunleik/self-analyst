package com.selfanalyst.wiki.semantic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleEmbeddingClientTest {

    @Test
    void shouldConstructCorrectly() {
        var client = new OpenAiCompatibleEmbeddingClient(
                "https://api.openai.com/v1", "sk-test", "text-embedding-3-small",
                1536, Duration.ofSeconds(10), true);
        assertNotNull(client);
    }

    @Test
    void shouldFailOnNonExistentServer() {
        var client = new OpenAiCompatibleEmbeddingClient(
                "http://127.0.0.1:19999", "sk-test", "text-embedding-3-small",
                1536, Duration.ofSeconds(2), true);

        assertThrows(RuntimeException.class, () -> client.embed(List.of("test")));
    }

    @Test
    void shouldEmbedSingleViaInterface() {
        // EmbeddingClient interface method should delegate
        EmbeddingClient client = texts -> List.of(new float[]{0.1f, 0.2f, 0.3f});
        float[] result = client.embedSingle("hello");
        assertEquals(3, result.length);
        assertEquals(0.1f, result[0], 0.001f);
    }

    @Test
    void shouldFailOnEmptyResultFromInterface() {
        EmbeddingClient client = texts -> List.of();
        assertThrows(RuntimeException.class, () -> client.embedSingle("test"));
    }
}
