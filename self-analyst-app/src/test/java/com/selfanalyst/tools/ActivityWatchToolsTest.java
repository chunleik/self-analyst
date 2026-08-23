package com.selfanalyst.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityWatchToolsTest {

    @Test
    void boundsEventLimitAndLargeToolResults() throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        String large = "A".repeat(50_000) + "B".repeat(50_000);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/0/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            byte[] response = large.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ActivityWatchTools tools = new ActivityWatchTools(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/0", 5000);

            String result = tools.queryEvents("bucket", 10_000, null, null);
            assertTrue(paths.getFirst().contains("limit=500"));
            assertTrue(result.length() <= ActivityWatchTools.MAX_TOOL_RESULT_CHARS);
            assertTrue(result.contains("tool result truncated"));
            assertTrue(result.startsWith("A"));
            assertTrue(result.endsWith("B"));

            tools.queryEvents("bucket", 0, null, null);
            assertTrue(paths.getLast().contains("limit=100"));
            assertEquals(2, paths.size());
        } finally {
            server.stop(0);
        }
    }
}
