package com.selfanalyst.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.integrations.PinnedHttpsClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class PinnedMcpTransportTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void actualSdkInitializesListsAndCallsThroughBoundedPostJsonAndSse() throws Exception {
        var methods=new CopyOnWriteArrayList<String>();
        var requests=new CopyOnWriteArrayList<Map<String,String>>();
        PinnedMcpTransport.Exchange exchange=(uri,method,headers,bytes,timeout,max)->{
            assertEquals("POST",method); assertEquals(65536,max); requests.add(Map.copyOf(headers));
            var request=JSON.readTree(bytes); String rpc=request.path("method").asText(); methods.add(rpc);
            if (!request.has("id")) return new PinnedHttpsClient.Response(202,Map.of(),new byte[0]);
            Object result=switch(rpc) {
                case "initialize" -> Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","synthetic","version","1"));
                case "tools/list" -> Map.of("tools",List.of(Map.of("name","search","description","Synthetic test","inputSchema",Map.of("type","object","properties",Map.of("q",Map.of("type","string"))))));
                case "tools/call" -> Map.of("content",List.of(Map.of("type","text","text","synthetic result")),"isError",false,"structuredContent",Map.of("echo","synthetic-sensitive-echo"));
                default -> throw new AssertionError(rpc);
            };
            String response=JSON.writeValueAsString(Map.of("jsonrpc","2.0","id",JSON.convertValue(request.get("id"),Object.class),"result",result));
            boolean sse=rpc.equals("tools/call");
            if (sse) response="event: message\ndata: "+response+"\n\n";
            return new PinnedHttpsClient.Response(200,Map.of("content-type",List.of(sse?"text/event-stream":"application/json"),"mcp-session-id",List.of("synthetic-session")),response.getBytes(StandardCharsets.UTF_8));
        };
        var transport=new PinnedMcpTransport(exchange,URI.create("https://example.com/mcp"),Duration.ofSeconds(2),65536,()->"synthetic-token");
        try (var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(2)).initializationTimeout(Duration.ofSeconds(2)).build()) {
            client.initialize(); assertEquals("search",client.listTools().tools().getFirst().name());
            assertFalse(client.callTool(new McpSchema.CallToolRequest("search",Map.of("q","hello"))).isError());
            assertEquals(1,methods.stream().filter("tools/call"::equals).count());
            assertTrue(requests.stream().allMatch(h->h.get("Authorization").equals("Bearer synthetic-token")));
            assertTrue(requests.stream().skip(1).allMatch(h->"synthetic-session".equals(h.get("Mcp-Session-Id"))));
        }
    }
    @Test void sdkCannotLogUntrustedPayloads() {
        assertFalse(org.slf4j.LoggerFactory.getLogger("io.modelcontextprotocol.client.McpAsyncClient").isWarnEnabled());
        assertFalse(org.slf4j.LoggerFactory.getLogger("io.modelcontextprotocol.client.LifecycleInitializer").isErrorEnabled());
    }
    @Test void redirectsAndUnsupportedMimeFailWithoutRetry() {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var transport=new PinnedMcpTransport((u,m,h,b,t,max)->{count.incrementAndGet();return new PinnedHttpsClient.Response(302,Map.of("location",List.of("http://127.0.0.1")),new byte[0]);},
                URI.create("https://example.com/mcp"),Duration.ofSeconds(1),65536,()->null);
        transport.connect(message->message).block();
        assertThrows(RuntimeException.class,()->transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/initialized",null)).block());
        assertEquals(1,count.get());
        transport.close();
        assertThrows(RuntimeException.class,()->transport.sendMessage(new McpSchema.JSONRPCNotification("2.0","notifications/initialized",null)).block());
        assertEquals(1,count.get());
    }
}
