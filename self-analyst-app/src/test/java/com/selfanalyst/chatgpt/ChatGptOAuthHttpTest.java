package com.selfanalyst.chatgpt;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatGptOAuthHttpTest {
    @Test void disallowsRedirectingClientsAndDifferentDestinations() {
        assertThrows(IllegalStateException.class, () -> new ChatGptOAuthHttp(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS).build(), URI.create("https://auth.openai.com")));
        ChatGptOAuthHttp http = new ChatGptOAuthHttp(HttpClient.newHttpClient(), URI.create("https://auth.openai.com"));
        for (String target : new String[]{"http://auth.openai.com/token", "https://auth.openai.com.evil.test/token",
                "https://auth.openai.com:444/token", "https://user@auth.openai.com/token", "https://auth.openai.com/token?secret=x"})
            assertThrows(IllegalStateException.class, () -> http.trusted(target));
    }

    @Test void redirectNeverReceivesPostedSecrets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger destinationCalls = new AtomicInteger();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", x -> { x.getResponseHeaders().set("Location", origin + "/capture"); x.sendResponseHeaders(307, -1); x.close(); });
        server.createContext("/capture", x -> { destinationCalls.incrementAndGet(); x.sendResponseHeaders(200, -1); x.close(); });
        server.start();
        try {
            ChatGptOAuthHttp http = new ChatGptOAuthHttp(HttpClient.newHttpClient(), URI.create(origin));
            assertEquals(307, http.post(URI.create(origin + "/token"), Map.of("refresh_token", "secret")).status);
            assertEquals(0, destinationCalls.get());
        } finally { server.stop(0); }
    }

    @Test void oversizedAndStalledResponseBodiesAreBounded() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/huge", x -> {
            byte[] body = new byte[ChatGptOAuthHttp.MAX_BODY + 1];
            x.sendResponseHeaders(200, body.length);
            try { x.getResponseBody().write(body); } finally { x.close(); }
        });
        server.createContext("/slow", x -> {
            x.sendResponseHeaders(200, 0); x.getResponseBody().write('{'); x.getResponseBody().flush();
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            x.close();
        });
        server.start();
        try {
            ChatGptOAuthHttp http = new ChatGptOAuthHttp(HttpClient.newHttpClient(), URI.create(origin), Duration.ofMillis(500));
            assertThrows(IllegalStateException.class, () -> http.get(URI.create(origin + "/huge")));
            long before = System.nanoTime();
            assertEquals("chatgpt_request_timeout", assertThrows(IllegalStateException.class,
                    () -> http.get(URI.create(origin + "/slow"))).getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - before).compareTo(Duration.ofSeconds(2)) < 0);
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
