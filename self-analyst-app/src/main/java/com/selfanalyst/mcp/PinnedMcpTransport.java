package com.selfanalyst.mcp;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.integrations.PinnedHttpsClient;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** SDK transport over a pinned, bounded HTTPS exchange. No redirects, GET subscriptions or retries. */
public final class PinnedMcpTransport implements McpClientTransport {
    private final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(1048576).build()).build()));
    @FunctionalInterface interface Exchange {
        PinnedHttpsClient.Response send(URI uri, String method, Map<String,String> headers, byte[] body,
                                       Duration timeout, int maximumBytes) throws java.io.IOException;
    }
    private final Exchange exchange;
    private final URI endpoint;
    private final Duration timeout;
    private final int maxBytes;
    private final Supplier<String> bearer;
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
    private volatile String session;
    private volatile String protocol = "2025-03-26";
    private volatile boolean closed;
    private final Set<Thread> active = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PinnedMcpTransport(PinnedHttpsClient http, URI endpoint, Duration timeout, int maxBytes, Supplier<String> bearer) {
        this(http::exchange, endpoint, timeout, maxBytes, bearer);
    }
    PinnedMcpTransport(Exchange exchange, URI endpoint, Duration timeout, int maxBytes, Supplier<String> bearer) {
        this.exchange = exchange; this.endpoint = endpoint; this.timeout = timeout; this.maxBytes = maxBytes; this.bearer = bearer;
    }
    @Override public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        this.handler = handler; return Mono.empty();
    }
    @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.<Void>fromRunnable(() -> {
            if (closed || handler == null) throw new IllegalStateException("mcp_closed");
            active.add(Thread.currentThread());
            try {
                var headers = new LinkedHashMap<String, String>();
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "application/json, text/event-stream");
                headers.put("MCP-Protocol-Version", protocol);
                if (session != null) headers.put("Mcp-Session-Id", session);
                String secret = bearer.get();
                if (secret != null && !secret.isEmpty()) headers.put("Authorization", "Bearer " + secret);
                byte[] body = mapper.writeValueAsBytes(message);
                if (body.length > 65536) throw new IllegalArgumentException("mcp_request_limit");
                var response = exchange.send(endpoint, "POST", headers, body, timeout, maxBytes);
                if (closed) throw new IllegalStateException("mcp_closed");
                if (response.status() < 200 || response.status() >= 300) throw new IllegalStateException("mcp_http_" + response.status());
                String next = header(response.headers(), "mcp-session-id");
                if (next != null) {
                    if (next.length() > 256 || !next.chars().allMatch(c -> c >= 33 && c <= 126))
                        throw new IllegalArgumentException("mcp_invalid_session");
                    if (session != null && !session.equals(next)) throw new IllegalStateException("mcp_session_changed");
                    session = next;
                }
                if (response.body().length == 0) return;
                String contentType = Objects.toString(header(response.headers(), "content-type"), "");
                String text = new String(response.body(), StandardCharsets.UTF_8);
                if (contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                    StringBuilder data = new StringBuilder(); int events = 0;
                    for (String line : (text + "\n\n").split("\\r?\\n", -1)) {
                        if (line.isEmpty() && !data.isEmpty()) {
                            if (++events > 128) throw new IllegalStateException("mcp_event_limit");
                            accept(data.toString()); data.setLength(0);
                        } else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) data.append('\n');
                            data.append(line.substring(5).stripLeading());
                        }
                    }
                } else if (contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) accept(text);
                else throw new IllegalStateException("mcp_invalid_content_type");
            } catch (java.io.IOException e) { throw new IllegalStateException("mcp_transport_failed"); }
            finally { active.remove(Thread.currentThread()); }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private void accept(String text) throws java.io.IOException {
        var message = McpSchema.deserializeJsonRpcMessage(mapper, text);
        if (message instanceof McpSchema.JSONRPCResponse response && response.result() instanceof Map<?,?> map
                && map.get("protocolVersion") instanceof String version) protocol = version;
        // Server callbacks (sampling, elicitation, roots) are not enabled by this client.
        if (message instanceof McpSchema.JSONRPCRequest) throw new IllegalStateException("mcp_server_request_unsupported");
        handler.apply(Mono.just(message)).block(timeout);
    }
    private static String header(Map<String,List<String>> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
    }
    @Override public <T> T unmarshalFrom(Object data, TypeRef<T> type) { return mapper.convertValue(data, type); }
    @Override public List<String> protocolVersions() { return List.of("2025-06-18", "2025-03-26"); }
    @Override public Mono<Void> closeGracefully() { closed = true; session = null; active.forEach(Thread::interrupt); return Mono.empty(); }
}
