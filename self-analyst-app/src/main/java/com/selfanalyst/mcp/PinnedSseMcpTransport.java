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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Legacy MCP SSE: one bounded pinned GET and same-authority POSTs; never replay a request. */
public final class PinnedSseMcpTransport implements McpClientTransport {
    private static final Duration STREAM_LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_PENDING = 32, MAX_EVENTS = 1024;
    private final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(1048576).build()).build()));

    @FunctionalInterface interface ReadEvents {
        void read(URI uri, Map<String, String> headers, Duration connect, Duration lifetime, int maximum,
                  PinnedHttpsClient.EventStream stream, PinnedHttpsClient.EventBytes consumer) throws IOException;
    }
    @FunctionalInterface interface Post {
        PinnedHttpsClient.Response send(PinnedHttpsClient.SsePostTarget target, Map<String, String> headers, byte[] body,
                                       Duration timeout, int maximum, BooleanSupplier authorize,
                                       BooleanSupplier startSend) throws IOException;
    }
    private record BusinessLease(BooleanSupplier stillAuthorized) { }
    private static final class Pending {
        private final long expiresAt;
        private final BusinessLease lease;
        // These two fields are changed only under the pending monitor.
        private boolean acknowledged;
        private McpSchema.JSONRPCResponse response;
        private Pending(long expiresAt, BusinessLease lease) {
            this.expiresAt = expiresAt;
            this.lease = lease;
        }
    }

    private final ReadEvents readEvents;
    private final Post post;
    private final URI endpoint;
    private final Duration timeout, initializationTimeout;
    private final int maxBytes;
    private final Supplier<String> bearer;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final Sinks.Empty<Void> ready = Sinks.empty();
    private final PinnedHttpsClient.EventStream stream = new PinnedHttpsClient.EventStream();
    private final Set<Thread> active = ConcurrentHashMap.newKeySet();
    private final Map<Object, Pending> pending = new LinkedHashMap<>();
    private final Object businessLock = new Object();
    private volatile BusinessLease businessLease;
    private volatile PinnedHttpsClient.SsePostTarget messageEndpoint;
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inbound;
    private volatile Thread eventWorker;

    public PinnedSseMcpTransport(PinnedHttpsClient http, URI endpoint, Duration requestTimeout,
                                 Duration initializationTimeout, int maxBytes, Supplier<String> bearer) {
        this(http::readEventStream, http::postSse, endpoint, requestTimeout, initializationTimeout, maxBytes, bearer);
    }

    PinnedSseMcpTransport(ReadEvents readEvents, Post post, URI endpoint, Duration requestTimeout,
                          Duration initializationTimeout, int maxBytes, Supplier<String> bearer) {
        this.readEvents = Objects.requireNonNull(readEvents);
        this.post = Objects.requireNonNull(post);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.timeout = checkedTimeout(requestTimeout);
        this.initializationTimeout = checkedTimeout(initializationTimeout);
        if (maxBytes < 1 || maxBytes > 1048576) throw new IllegalArgumentException("invalid_sse_budget");
        this.maxBytes = maxBytes;
        this.bearer = Objects.requireNonNull(bearer);
    }

    private static Duration checkedTimeout(Duration timeout) {
        if (timeout == null || timeout.toMillis() < 1 || timeout.toMillis() > 120000)
            throw new IllegalArgumentException("invalid_sse_timeout");
        return timeout;
    }

    /** An expired or failed stream needs a new transport and MCP initialization. */
    public boolean isOpen() { return messageEndpoint != null && !closed.get(); }

    @Override public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.defer(() -> {
            if (closed.get() || !started.compareAndSet(false, true))
                return Mono.error(new IllegalStateException("mcp_sse_closed"));
            inbound = Objects.requireNonNull(handler);
            Thread worker = Thread.ofVirtual().name("integration-mcp-sse").unstarted(this::runEvents);
            eventWorker = worker;
            worker.start();
            return ready.asMono().timeout(initializationTimeout)
                    .doOnError(ignored -> stop()).doOnCancel(this::stop);
        });
    }

    private void runEvents() {
        try {
            if (closed.get()) return;
            var headers = headers();
            headers.put("Accept", "text/event-stream");
            EventParser parser = new EventParser();
            readEvents.read(endpoint, headers, initializationTimeout, STREAM_LIFETIME,
                    Math.max(262144, maxBytes * 8), stream, parser::accept);
        } catch (IOException | RuntimeException unavailable) {
            // Neither server-supplied endpoint/session nor raw errors enter diagnostics.
        } finally {
            stop();
        }
    }

    public <T> T withBusinessGuard(BooleanSupplier stillAuthorized, Supplier<T> action) {
        synchronized (businessLock) {
            if (businessLease != null || closed.get() || !stillAuthorized.getAsBoolean())
                throw new SecurityException("connection_revoked");
            businessLease = new BusinessLease(stillAuthorized);
            try { return action.get(); }
            finally { businessLease = null; }
        }
    }

    @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        McpSchema.JSONRPCRequest request = message instanceof McpSchema.JSONRPCRequest rpc ? rpc : null;
        boolean business = request != null && "tools/call".equals(request.method());
        BusinessLease lease = business ? businessLease : null;
        // McpClientSession subscribes to connect() asynchronously. The first SDK initialize POST can
        // arrive while the GET is still opening. Wait for the endpoint event without blocking an IO worker
        // or letting cancellation of this single send cancel the shared readiness signal.
        return ready.asMono().timeout(initializationTimeout).then(Mono.<Void>fromRunnable(() -> {
            Thread thread = Thread.currentThread();
            active.add(thread);
            byte[] body = null;
            try {
                PinnedHttpsClient.SsePostTarget target = messageEndpoint;
                if (!allowed(target, business, lease)) throw new SecurityException("connection_revoked");
                if (request != null && request.id() == null) throw new IllegalStateException("mcp_sse_invalid_id");
                var headers = headers();
                headers.put("Content-Type", "application/json");
                body = mapper.writeValueAsBytes(message);
                if (body.length > 65536) throw new IllegalArgumentException("mcp_request_limit");
                long deadline = System.nanoTime() + timeout.toNanos();
                // Accept a response to this ID only once the request is ready to be written.
                BooleanSupplier authorize = () -> allowed(target, business, lease) && System.nanoTime() < deadline;
                var response = post.send(target, headers, body, timeout, Math.min(8192, maxBytes), authorize, () -> {
                    if (!authorize.getAsBoolean()) return false;
                    if (request != null) {
                        synchronized (pending) {
                            if (!authorize.getAsBoolean()) return false;
                            pending.values().removeIf(item -> item.expiresAt <= System.nanoTime());
                            if (pending.size() >= MAX_PENDING || pending.containsKey(request.id())) return false;
                            pending.put(request.id(), new Pending(deadline, lease));
                        }
                    }
                    return true;
                });
                if (!allowed(target, business, lease)) throw new SecurityException("connection_revoked");
                if (response.status() < 200 || response.status() >= 300)
                    throw new IOException("invalid_sse_post_response");
                // Legacy servers may return an empty acknowledgement or a short text body. The response data
                // comes only from the validated event stream and must not reach the SDK before HTTP acceptance.
                if (request != null) acknowledge(request.id());
            } catch (IOException transportFailure) {
                stop();
                throw new IllegalStateException("mcp_sse_transport_failed");
            } finally {
                if (body != null) Arrays.fill(body, (byte) 0);
                active.remove(thread);
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private boolean allowed(PinnedHttpsClient.SsePostTarget target, boolean business, BusinessLease lease) {
        return target != null && messageEndpoint == target && !closed.get()
                && (!business || lease != null && businessLease == lease && lease.stillAuthorized().getAsBoolean());
    }

    private void acknowledge(Object id) {
        Pending request;
        synchronized (pending) {
            request = pending.get(id);
            if (request == null) return;
            request.acknowledged = true;
            if (request.response == null) return;
            pending.remove(id);
        }
        deliver(request);
    }

    private void receive(McpSchema.JSONRPCResponse response) {
        Pending request;
        synchronized (pending) {
            request = pending.get(response.id());
            if (request == null || request.response != null) return;
            request.response = response;
            if (!request.acknowledged) return;
            pending.remove(response.id());
        }
        deliver(request);
    }

    private void deliver(Pending request) {
        if (closed.get() || request.expiresAt <= System.nanoTime()) return;
        if (request.lease != null && !allowed(messageEndpoint, true, request.lease)) return;
        inbound.apply(Mono.just(request.response)).block(timeout);
    }

    private LinkedHashMap<String, String> headers() {
        var headers = new LinkedHashMap<String, String>();
        String secret = bearer.get();
        if (secret != null && !secret.isEmpty()) headers.put("Authorization", "Bearer " + secret);
        return headers;
    }

    private void stop() {
        if (!closed.compareAndSet(false, true)) return;
        messageEndpoint = null;
        ready.tryEmitError(new IllegalStateException("mcp_sse_closed"));
        synchronized (pending) { pending.clear(); }
        stream.close();
        Thread worker = eventWorker;
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        active.forEach(thread -> { if (thread != Thread.currentThread()) thread.interrupt(); });
    }

    private final class EventParser {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final StringBuilder data = new StringBuilder();
        private String event;
        private boolean firstLine = true, afterCr, hasData;
        private int dataBytes, events;

        void accept(byte[] bytes, int length) throws IOException {
            for (int i = 0; i < length; i++) {
                if (closed.get()) throw new IOException("endpoint_cancelled");
                int octet = bytes[i] & 255;
                if (afterCr) {
                    afterCr = false;
                    if (octet == '\n') continue;
                }
                if (octet == '\r' || octet == '\n') {
                    afterCr = octet == '\r';
                    processLine();
                } else {
                    if (line.size() >= maxBytes) throw new IOException("response_limit");
                    line.write(octet);
                }
            }
        }

        private void processLine() throws IOException {
            int lineBytes = line.size();
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString();
            line.reset();
            if (firstLine) {
                firstLine = false;
                if (value.startsWith("\uFEFF")) value = value.substring(1);
            }
            if (value.isEmpty()) {
                if (hasData) {
                    if (++events > MAX_EVENTS) throw new IOException("response_limit");
                    dispatch();
                }
                event = null;
                data.setLength(0);
                hasData = false;
                dataBytes = 0;
                return;
            }
            int colon = value.indexOf(':');
            String field = colon < 0 ? value : value.substring(0, colon);
            String fieldValue = colon < 0 ? "" : value.substring(colon + 1);
            if (fieldValue.startsWith(" ")) fieldValue = fieldValue.substring(1);
            if (field.equals("event")) {
                if (fieldValue.length() > 32 || event != null) throw new IOException("invalid_sse_event");
                event = fieldValue;
            } else if (field.equals("data")) {
                if (lineBytes + 1 > maxBytes - dataBytes) throw new IOException("response_limit");
                dataBytes += lineBytes + 1;
                if (hasData) data.append('\n');
                data.append(fieldValue);
                hasData = true;
            }
            // Comments, id and retry are not used. This client never resumes or reconnects.
        }

        private void dispatch() throws IOException {
            if ("endpoint".equals(event)) {
                if (messageEndpoint != null || closed.get()) throw new IOException("invalid_sse_endpoint");
                messageEndpoint = PinnedHttpsClient.ssePostTarget(endpoint, data.toString());
                if (closed.get()) {
                    messageEndpoint = null;
                    throw new IOException("endpoint_cancelled");
                }
                ready.tryEmitEmpty();
                return;
            }
            if (messageEndpoint == null) throw new IOException("invalid_sse_endpoint");
            if (event != null && !event.isEmpty() && !"message".equals(event)) return;
            var message = McpSchema.deserializeJsonRpcMessage(mapper, data.toString());
            if (message instanceof McpSchema.JSONRPCRequest)
                throw new IOException("mcp_server_request_unsupported");
            // Unsolicited notifications, and responses abandoned after timeout/cancellation, carry no authority.
            if (message instanceof McpSchema.JSONRPCResponse response) receive(response);
        }
    }

    @Override public <T> T unmarshalFrom(Object data, TypeRef<T> type) { return mapper.convertValue(data, type); }
    @Override public List<String> protocolVersions() { return List.of("2024-11-05"); }
    @Override public Mono<Void> closeGracefully() { stop(); return Mono.empty(); }
}
