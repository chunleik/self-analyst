package com.selfanalyst.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import com.selfanalyst.wiki.WikiPrivacyPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EventQueryToolsTest {

    @Test
    void listsHiddenBucketsForAgentDiscovery() throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/0/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            EventQueryTools tools = new EventQueryTools(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/0", 5000);

            tools.listBuckets();

            assertEquals("/api/0/buckets/?include_hidden=true", paths.getFirst());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsEventLimitAndLargeToolResults() throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        String large = "[{\"data\":{\"app\":\"visible.exe\",\"title\":\"" + "A".repeat(50_000) + "B".repeat(50_000) + "\"}}]";
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
            EventQueryTools tools = new EventQueryTools(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/0", 5000);

            String result = tools.queryEvents("bucket", 10_000, null, null);
            assertTrue(paths.getFirst().contains("limit=500"));
            assertTrue(result.length() <= EventQueryTools.MAX_TOOL_RESULT_CHARS);
            assertTrue(result.contains("事件查询工具结果已截断"));
            assertTrue(result.startsWith("[{\"data\""));
            assertTrue(result.endsWith("\"}}]"));

            tools.queryEvents("bucket", 0, null, null);
            assertTrue(paths.getLast().contains("limit=100"));
            assertEquals(2, paths.size());
        } finally {
            server.stop(0);
        }
    }

    @Test void queryAndAqlUseFreshPoliciesAndFilterBeforeTheCharacterBudget() throws Exception {
        var privacy = new AtomicReference<>(WikiPrivacyPolicy.of("private.exe", ""));
        var reads = new AtomicInteger();
        var requestBodies = new CopyOnWriteArrayList<String>();
        String response = "[{\"data\":{\"app\":\"private.exe\",\"title\":\""
                + "PRIVATE_DETAIL".repeat(10_000) + "\"}},{\"data\":{\"app\":\"visible.exe\",\"title\":\"retained\"}}]";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/0/", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var tools = new EventQueryTools("http://127.0.0.1:" + server.getAddress().getPort() + "/api/0", 5000,
                    () -> { reads.incrementAndGet(); return privacy.get(); });
            String result = tools.queryEvents("bucket", 100, null, null);
            assertFalse(result.contains("private.exe"));
            assertFalse(result.contains("PRIVATE_DETAIL"));
            assertFalse(result.contains("截断"));
            assertTrue(result.contains("retained"));
            assertEquals(1, new ObjectMapper().readTree(result).size());
            result = tools.executeAQL("events = query_bucket(\"bucket\"); RETURN = events;", "2026-01-01/2026-01-02");
            assertFalse(result.contains("private.exe"));
            assertTrue(requestBodies.getLast().contains("query_bucket"));
            assertEquals(2, reads.get());
            privacy.set(WikiPrivacyPolicy.of("visible.exe", ""));
            result = tools.queryEvents("bucket", 100, null, null);
            assertTrue(result.contains("private.exe"));
            assertFalse(result.contains("retained"));
            assertTrue(result.length() <= EventQueryTools.MAX_TOOL_RESULT_CHARS);
            tools.listBuckets(); tools.getServerInfo(); tools.getSettings();
            assertEquals(3, reads.get(), "Discovery and settings routes are not privacy-filtered");
            assertTrue(tools.getServerInfo().contains("private.exe"));
        } finally { server.stop(0); }
    }

    @Test void nonJsonSuccessAndHttpFailureNeverEchoUntrustedBodies() throws Exception {
        var status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/0/", exchange -> {
            byte[] bytes = "private.exe PRIVATE_DETAIL".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var tools = new EventQueryTools("http://127.0.0.1:" + server.getAddress().getPort() + "/api/0", 5000);
            for (int code : new int[] {200, 503}) {
                status.set(code);
                for (String result : new String[] {tools.queryEvents("bucket", 100, null, null),
                        tools.executeAQL("RETURN = [];", "2026-01-01/2026-01-02")}) {
                    assertTrue(new ObjectMapper().readTree(result).has("error"));
                    assertFalse(result.contains("private.exe"));
                    assertFalse(result.contains("PRIVATE_DETAIL"));
                }
            }
        } finally { server.stop(0); }
    }
    @Test void missingPolicyFailsClosedBeforeSendingEitherEventRequest() throws Exception {
        var tools = new EventQueryTools("http://127.0.0.1:1/api/0", 1000, () -> null);
        for (String result : new String[] {tools.queryEvents("bucket", 1, null, null),
                tools.executeAQL("RETURN = [];", "2026-01-01/2026-01-02")}) {
            assertEquals("EVENT_PRIVACY_UNAVAILABLE", new ObjectMapper().readTree(result).path("error").asText());
        }
    }

}
