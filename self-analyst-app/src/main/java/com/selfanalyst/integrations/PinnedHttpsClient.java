package com.selfanalyst.integrations;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Bounded HTTP/1.1 HTTPS exchange with fresh checked DNS, direct pinned sockets and no redirects. */
public final class PinnedHttpsClient {
    public record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public Response { headers = Map.copyOf(headers); body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    @FunctionalInterface interface Connector {
        SSLSocket connect(PublicEndpointPolicy.PinnedEndpoint endpoint, Duration timeout,
                          java.util.function.Consumer<SSLSocket> connected) throws IOException;
    }
    private final PublicEndpointPolicy policy;
    private final Connector connector;
    private final Semaphore capacity = new Semaphore(8);
    // Streaming GETs must not consume the slots needed for their corresponding POSTs.
    private final Semaphore streamCapacity = new Semaphore(8);
    public PinnedHttpsClient() { this(new PublicEndpointPolicy()); }
    public PinnedHttpsClient(PublicEndpointPolicy policy) { this(policy, PublicEndpointPolicy.PinnedEndpoint::openSocket); }
    PinnedHttpsClient(PublicEndpointPolicy policy, Connector connector) { this.policy = policy; this.connector = connector; }

    public Response exchange(URI endpoint, String method, Map<String, String> headers, byte[] body,
                             Duration timeout, int maxBytes) throws IOException {
        URI canonical = PublicEndpointPolicy.validate(endpoint.toASCIIString());
        return exchange(canonical, null, method, headers, body, timeout, maxBytes, () -> true, () -> true);
    }

    /** Only the legacy SSE transport may derive a session POST from its configured public GET target. */
    public static final class SsePostTarget {
        private final URI canonical;
        private final String query;
        private SsePostTarget(URI canonical, String query) {
            this.canonical = canonical;
            this.query = query;
        }
    }

    public static SsePostTarget ssePostTarget(URI streamEndpoint, String advertised) throws IOException {
        URI origin = PublicEndpointPolicy.validate(streamEndpoint.toASCIIString());
        if (advertised == null || advertised.length() > 1536 || advertised.startsWith("//"))
            throw new IOException("invalid_sse_endpoint");
        try {
            URI candidate = new URI(advertised);
            if (!candidate.isAbsolute() && (candidate.getRawAuthority() != null || !advertised.startsWith("/")))
                throw new IOException("invalid_sse_endpoint");
            if (candidate.getRawFragment() != null || candidate.getRawUserInfo() != null)
                throw new IOException("invalid_sse_endpoint");
            // Restrict the advertised route to ordinary literal path segments. Do not turn query, fragment,
            // encoded separators, or ambiguous dot segments into a different HTTP request target.
            String path = candidate.getRawPath();
            if (path == null || !path.matches("(?:/[A-Za-z0-9_.~-]+)+/?")
                    || java.util.Arrays.stream(path.split("/", -1)).anyMatch(p -> p.equals(".") || p.equals("..")))
                throw new IOException("invalid_sse_endpoint");
            String query = candidate.getRawQuery();
            if (query != null && !query.matches("(?:sessionId|session_id)=[A-Za-z0-9_-]{1,256}"))
                throw new IOException("invalid_sse_endpoint");
            if (candidate.isAbsolute() && candidate.getRawAuthority() == null)
                throw new IOException("invalid_sse_endpoint");
            String base = candidate.isAbsolute() ? candidate.getScheme() + "://" + candidate.getRawAuthority()
                    : "https://" + origin.getRawAuthority();
            URI canonical = PublicEndpointPolicy.validate(base + path);
            if (!canonical.getRawAuthority().equals(origin.getRawAuthority()))
                throw new IOException("invalid_sse_endpoint");
            return new SsePostTarget(canonical, query);
        } catch (java.net.URISyntaxException | IllegalArgumentException invalidTarget) {
            throw new IOException("invalid_sse_endpoint");
        }
    }

    public Response postSse(SsePostTarget target, Map<String, String> headers, byte[] body,
                            Duration timeout, int maxBytes, BooleanSupplier stillAuthorized,
                            BooleanSupplier startSend) throws IOException {
        return exchange(target.canonical, target.query, "POST", headers, body, timeout, maxBytes, stillAuthorized, startSend);
    }

    private Response exchange(URI canonical, String query, String method, Map<String, String> headers, byte[] body,
                              Duration timeout, int maxBytes, BooleanSupplier stillAuthorized,
                              BooleanSupplier startSend) throws IOException {
        if (!Set.of("GET", "POST", "DELETE").contains(method)) throw new IOException("unsupported_http_method");
        if (body == null || body.length > 1048576 || maxBytes < 1 || maxBytes > 1048576
                || timeout == null || timeout.toMillis() < 1 || timeout.toMillis() > 120000)
            throw new IOException("invalid_http_budget");
        byte[] request = request(canonical, query, method, headers, body);
        if (!capacity.tryAcquire()) {
            java.util.Arrays.fill(request, (byte) 0);
            throw new IOException("endpoint_busy");
        }
        AtomicReference<SSLSocket> active = new AtomicReference<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        FutureTask<Response> operation = new FutureTask<>(() -> {
            try {
                checkAuthorized(stillAuthorized);
                var pinned = policy.resolve(canonical); // Each exchange, including reconnect, resolves all addresses again.
                remaining(deadline);
                checkAuthorized(stillAuthorized);
                try (SSLSocket socket = connector.connect(pinned, Duration.ofMillis(Math.min(30000, remaining(deadline))), active::set)) {
                    active.set(socket);
                    var output = socket.getOutputStream();
                    remaining(deadline);
                    checkAuthorized(stillAuthorized); // After connect/handshake, before any request bytes.
                    checkAuthorized(startSend);
                    output.write(request);
                    output.flush();
                    return readResponse(socket, deadline, maxBytes);
                }
            } finally { active.set(null); }
        });
        Thread.ofVirtual().name("integration-https").start(() -> {
            try { operation.run(); }
            finally { java.util.Arrays.fill(request, (byte) 0); capacity.release(); }
        });
        try { return operation.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException expired) { throw new IOException("endpoint_timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("endpoint_cancelled"); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw new IOException(safeCategory(io));
            throw new IOException("endpoint_failed");
        } finally {
            if (!operation.isDone()) operation.cancel(true);
            SSLSocket socket = active.getAndSet(null);
            closeSocket(socket);
        }
    }
    public Response exchange(URI endpoint, String method, Map<String, String> headers, byte[] body, int maxBytes) throws IOException {
        return exchange(endpoint, method, headers, body, Duration.ofSeconds(30), maxBytes);
    }
    @FunctionalInterface public interface EventBytes {
        void accept(byte[] buffer, int length) throws IOException;
    }

    /** Closing a stream also closes its currently registered socket, including during TLS handshake. */
    public static final class EventStream implements AutoCloseable {
        private SSLSocket socket;
        private boolean closed;
        private synchronized void register(SSLSocket value) {
            if (closed) closeSocket(value);
            else socket = value;
        }
        private synchronized boolean active() { return !closed; }
        @Override public void close() {
            SSLSocket previous;
            synchronized (this) {
                closed = true;
                previous = socket;
                socket = null;
            }
            closeSocket(previous);
        }
    }

    /** Streams a single, bounded GET synchronously. Callers own its worker and cancel via the stream handle. */
    public void readEventStream(URI endpoint, Map<String, String> headers, Duration connectTimeout,
                                Duration lifetime, int maxBytes, EventStream stream, EventBytes consumer) throws IOException {
        URI canonical = PublicEndpointPolicy.validate(endpoint.toASCIIString());
        if (connectTimeout == null || connectTimeout.toMillis() < 1 || connectTimeout.toMillis() > 120000
                || lifetime == null || lifetime.compareTo(connectTimeout) < 0 || lifetime.toMillis() > 300000
                || maxBytes < 1 || maxBytes > 8388608) throw new IOException("invalid_http_budget");
        byte[] request = request(canonical, null, "GET", headers, new byte[0]);
        if (!streamCapacity.tryAcquire()) {
            java.util.Arrays.fill(request, (byte) 0);
            throw new IOException("endpoint_busy");
        }
        long start = System.nanoTime(), connectDeadline = start + connectTimeout.toNanos();
        try {
            checkAuthorized(stream::active);
            var pinned = policy.resolve(canonical);
            try (SSLSocket socket = connector.connect(pinned,
                    Duration.ofMillis(Math.min(30000, remaining(connectDeadline))), stream::register)) {
                stream.register(socket);
                remaining(connectDeadline);
                checkAuthorized(stream::active);
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
                Reader reader = new Reader(socket, connectDeadline);
                ResponseHead head = readHead(reader);
                String contentType = single(head.headers(), "content-type");
                if (head.status() != 200 || contentType == null
                        || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("text/event-stream"))
                    throw new IOException("invalid_sse_response");
                reader.deadline = start + lifetime.toNanos();
                readBody(reader, head, maxBytes, consumer);
            }
        } catch (IOException failure) { throw new IOException(safeCategory(failure)); }
        finally {
            java.util.Arrays.fill(request, (byte) 0);
            stream.close();
            streamCapacity.release();
        }
    }

    private static void checkAuthorized(BooleanSupplier authorized) throws IOException {
        if (!authorized.getAsBoolean()) throw new IOException("endpoint_cancelled");
    }
    private static void closeSocket(SSLSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private static String safeCategory(IOException failure) {
        String message = failure.getMessage();
        return message != null && Set.of("endpoint_timeout", "endpoint_cancelled", "endpoint_resolution_failed",
                "non_public_endpoint", "endpoint_connection_failed", "redirect_rejected", "response_limit",
                "invalid_http_response", "unsupported_content_encoding", "invalid_sse_response").contains(message) ? message : "endpoint_failed";
    }
    private static byte[] request(URI endpoint, String query, String method, Map<String, String> headers, byte[] body) throws IOException {
        if (headers == null || headers.size() > 32) throw new IOException("invalid_http_headers");
        String host = endpoint.getRawAuthority();
        StringBuilder request = new StringBuilder(method).append(' ').append(endpoint.getRawPath());
        if (query != null) request.append('?').append(query);
        request.append(" HTTP/1.1\r\nHost: ").append(host).append("\r\nConnection: close\r\nContent-Length: ")
                .append(body.length).append("\r\nAccept-Encoding: identity\r\n");
        Set<String> forbidden = Set.of("host", "connection", "content-length", "transfer-encoding", "accept-encoding",
                "proxy-authorization", "proxy-connection", "upgrade", "expect", "trailer", "te");
        Set<String> seen = new java.util.HashSet<>();
        for (var entry : headers.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key == null || !key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}") || value == null
                    || value.length() > 8192 || value.chars().anyMatch(c -> c < 32 || c > 126)
                    || forbidden.contains(key.toLowerCase(Locale.ROOT)) || !seen.add(key.toLowerCase(Locale.ROOT)))
                throw new IOException("invalid_http_headers");
            request.append(key).append(": ").append(value).append("\r\n");
        }
        request.append("\r\n");
        if (request.length() > 32768) throw new IOException("invalid_http_headers");
        byte[] prefix = request.toString().getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(prefix.length + body.length);
        bytes.writeBytes(prefix); bytes.writeBytes(body);
        return bytes.toByteArray();
    }
    private static Response readResponse(SSLSocket socket, long deadline, int maximum) throws IOException {
        Reader reader = new Reader(socket, deadline);
        ResponseHead head = readHead(reader);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        readBody(reader, head, maximum, (data, length) -> body.write(data, 0, length));
        return new Response(head.status(), head.headers(), body.toByteArray());
    }
    private record ResponseHead(int status, Map<String, List<String>> headers, String length, String transfer) { }
    private static ResponseHead readHead(Reader reader) throws IOException {
        int status;
        Map<String, List<String>> headers;
        int interim = 0;
        do {
            String statusLine = reader.line();
            if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: [\\x20-\\x7E]*)?")) throw invalid();
            status = Integer.parseInt(statusLine.substring(9, 12));
            headers = new LinkedHashMap<>();
            for (int count = 0;; count++) {
                String line = reader.line();
                if (line.isEmpty()) break;
                if (count >= 100 || !line.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+:.*")) throw invalid();
                int colon = line.indexOf(':');
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
            if (status == 101 || ++interim > 4) throw invalid();
        } while (status < 200);
        if (status >= 300 && status <= 399) throw new IOException("redirect_rejected");
        String encoding = single(headers, "content-encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("unsupported_content_encoding");
        String length = single(headers, "content-length"), transfer = single(headers, "transfer-encoding");
        if (length != null && transfer != null) throw invalid();
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        headers.forEach((key, values) -> frozen.put(key, List.copyOf(values)));
        return new ResponseHead(status, Map.copyOf(frozen), length, transfer);
    }
    private static void readBody(Reader reader, ResponseHead head, int maximum, EventBytes consumer) throws IOException {
        String length = head.length(), transfer = head.transfer();
        byte[] buffer = new byte[Math.min(8192, maximum + 1)];
        int total = 0;
        if (head.status() != 204) {
            if (transfer != null) {
                if (!transfer.equalsIgnoreCase("chunked")) throw invalid();
                while (true) {
                    String chunk = reader.line();
                    int semicolon = chunk.indexOf(';');
                    String size = semicolon < 0 ? chunk : chunk.substring(0, semicolon);
                    if (!size.matches("[0-9a-fA-F]{1,8}")) throw invalid();
                    long count = Long.parseLong(size, 16);
                    if (count == 0) {
                        while (!reader.line().isEmpty()) { /* Trailer data is discarded under the same header budget. */ }
                        break;
                    }
                    if (count > maximum - total) throw new IOException("response_limit");
                    reader.exact(buffer, (int) count, consumer);
                    total += (int) count;
                    if (!reader.line().isEmpty()) throw invalid();
                }
            } else if (length != null) {
                if (!length.matches("[0-9]{1,10}")) throw invalid();
                long count = Long.parseLong(length);
                if (count > maximum) throw new IOException("response_limit");
                reader.exact(buffer, (int) count, consumer);
            } else {
                int count;
                while ((count = reader.read(buffer, 0, Math.min(buffer.length, maximum - total + 1))) != -1) {
                    if (count == 0) throw invalid();
                    if (count > maximum - total) throw new IOException("response_limit");
                    consumer.accept(buffer, count);
                    total += count;
                }
            }
        }
    }
    private static String single(Map<String, List<String>> headers, String key) throws IOException {
        List<String> values = headers.get(key);
        if (values == null) return null;
        if (values.size() != 1) throw invalid();
        return values.getFirst();
    }
    private static IOException invalid() { return new IOException("invalid_http_response"); }
    private static int remaining(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("endpoint_cancelled");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("endpoint_timeout");
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }
    private static final class Reader {
        private final SSLSocket socket;
        private final InputStream input;
        private long deadline;
        private int headerBytes;
        Reader(SSLSocket socket, long deadline) throws IOException { this.socket = socket; this.input = socket.getInputStream(); this.deadline = deadline; }
        int read(byte[] bytes, int offset, int length) throws IOException {
            socket.setSoTimeout(remaining(deadline));
            return input.read(bytes, offset, length);
        }
        String line() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean cr = false;
            byte[] one = new byte[1];
            while (true) {
                if (++headerBytes > 32768 || line.size() > 8192) throw new IOException("response_limit");
                if (read(one, 0, 1) != 1) throw invalid();
                int value = one[0] & 255;
                if (cr) { if (value != '\n') throw invalid(); return line.toString(StandardCharsets.ISO_8859_1); }
                if (value == '\r') cr = true;
                else { if (value == '\n' || (value < 32 && value != '\t') || value == 127) throw invalid(); line.write(value); }
            }
        }
        void exact(byte[] bytes, int count, EventBytes output) throws IOException {
            while (count > 0) {
                int read = read(bytes, 0, Math.min(bytes.length, count));
                if (read < 1) throw invalid();
                output.accept(bytes, read); count -= read;
            }
        }
    }
}
