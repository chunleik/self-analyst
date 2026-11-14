package com.selfanalyst.observability;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.*;

/**
 * Single direct, pinned socket per exchange; HTTP proxy and redirect support intentionally absent.
 */
public class ObservabilityTransport implements AutoCloseable {
    private final ObservabilityEndpointPolicy policy;
    private final AtomicReference<Exchange> active = new AtomicReference<>();

    private static final class Exchange {
        private volatile boolean cancelled;
        private volatile Socket socket;

        synchronized void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new IOException("cancelled");
        }

        synchronized void attach(Socket next) throws IOException {
            if (cancelled) {
                next.close();
                throw new IOException("cancelled");
            }
            socket = next;
        }

        void write(byte[] headers, byte[] body) throws IOException {
            check();
            socket.getOutputStream().write(headers);
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        }

        synchronized void cancel() {
            cancelled = true;
            if (socket != null)
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
        }
    }

    public ObservabilityTransport(ObservabilityEndpointPolicy policy) {
        this.policy = policy;
    }

    public int send(
            ObservabilityEndpointPolicy.ApprovedEndpoint approved,
            String authorization,
            byte[] body)
            throws IOException {
        return send(approved, authorization, body, () -> true);
    }

    public int send(
            ObservabilityEndpointPolicy.ApprovedEndpoint approved,
            String authorization,
            byte[] body,
            java.util.function.BooleanSupplier authorized)
            throws IOException {
        if (body.length > 1048576) throw new IOException("payload_limit");
        Exchange exchange = new Exchange();
        if (!active.compareAndSet(null, exchange)) throw new IOException("endpoint_busy");
        FutureTask<Integer> task =
                new FutureTask<>(
                        () -> {
                            try {
                                return exchange(
                                        approved, authorization, body, exchange, authorized);
                            } finally {
                                exchange.cancel();
                                active.compareAndSet(exchange, null);
                            }
                        });
        Thread.ofVirtual().name("observability-http").start(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new IOException("timeout");
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new IOException("cancelled");
        } catch (ExecutionException failure) {
            throw new IOException(safe(failure.getCause()));
        } finally {
            exchange.cancel();
            task.cancel(true);
        }
    }

    private int exchange(
            ObservabilityEndpointPolicy.ApprovedEndpoint endpoint,
            String authorization,
            byte[] body,
            Exchange exchange,
            java.util.function.BooleanSupplier authorized)
            throws IOException {
        URI uri = endpoint.uri();
        exchange.check();
        if (!authorized.getAsBoolean()) throw new IOException("cancelled");
        InetAddress address = policy.verify(endpoint);
        exchange.check();
        if (!authorized.getAsBoolean()) throw new IOException("cancelled");
        Socket raw = new Socket();
        exchange.attach(raw);
        try {
            raw.connect(
                    new InetSocketAddress(
                            address,
                            uri.getPort() < 0
                                    ? uri.getScheme().equals("https") ? 443 : 80
                                    : uri.getPort()),
                    2500);
            raw.setSoTimeout(2500);
            Socket socket = raw;
            exchange.check();
            if (uri.getScheme().equals("https")) {
                var ssl =
                        (SSLSocket)
                                ObservabilityEndpointPolicy.sslContext(endpoint.caPem())
                                        .getSocketFactory()
                                        .createSocket(
                                                raw,
                                                ObservabilityEndpointPolicy.strip(uri.getHost()),
                                                uri.getPort() < 0 ? 443 : uri.getPort(),
                                                true);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                exchange.attach(ssl);
                ssl.startHandshake();
                socket = ssl;
            }
            exchange.check();
            if (!authorized.getAsBoolean()) throw new IOException("cancelled");
            String headers =
                    "POST "
                            + uri.getRawPath()
                            + " HTTP/1.1\r\nHost: "
                            + uri.getRawAuthority()
                            + "\r\n"
                            + "Connection: close\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Accept-Encoding: identity\r\n"
                            + "Authorization: "
                            + authorization
                            + "\r\nx-langfuse-ingestion-version: 4\r\nContent-Length: "
                            + body.length
                            + "\r\n\r\n";
            exchange.write(headers.getBytes(StandardCharsets.US_ASCII), body);
            InputStream in = socket.getInputStream();
            String status = line(in);
            if (!status.matches("HTTP/1\\.[01] [2-5][0-9]{2}(?: [\\x20-\\x7e]*)?"))
                throw new IOException("invalid_response");
            int code = Integer.parseInt(status.substring(9, 12));
            int total = 0;
            int length = -1;
            boolean chunked = false;
            String contentType = null;
            String header;
            while (!(header = line(in)).isEmpty()) {
                total += header.length();
                if (total > 16384) throw new IOException("invalid_response");
                String lower = header.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-type:")) {
                    if (contentType != null) throw new IOException("invalid_response");
                    contentType = lower.substring(13).trim();
                }
                if (lower.startsWith("content-length:")) {
                    if (length != -1) throw new IOException("invalid_response");
                    try {
                        length = Integer.parseInt(header.substring(15).trim());
                    } catch (NumberFormatException bad) {
                        throw new IOException("invalid_response");
                    }
                    if (length < 0 || length > 65536) throw new IOException("invalid_response");
                }
                if (lower.startsWith("transfer-encoding:")) {
                    if (!lower.substring(18).trim().equals("chunked") || chunked)
                        throw new IOException("invalid_response");
                    chunked = true;
                }
                if (lower.startsWith("content-encoding:")
                        && !lower.substring(17).trim().equals("identity"))
                    throw new IOException("invalid_response");
            }
            if (code >= 300 && code < 400) throw new IOException("redirect_rejected");
            if (code >= 200 && code < 300 && code != 200) throw new IOException("invalid_response");
            if (code == 200) {
                if (contentType == null
                        || !contentType.split(";", 2)[0].trim().equals("application/json"))
                    throw new IOException("invalid_response");
                if (chunked && length >= 0) throw new IOException("invalid_response");
                byte[] response =
                        chunked
                                ? chunks(in)
                                : length < 0 ? in.readNBytes(65537) : in.readNBytes(length);
                if (response.length == 0
                        || response.length > 65536
                        || length >= 0 && response.length != length)
                    throw new IOException("invalid_response");
                if (response.length > 0) {
                    com.fasterxml.jackson.databind.JsonNode json;
                    try {
                        json = ObservabilityStore.JSON.readTree(response);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                        throw new IOException("invalid_response");
                    }
                    if (json == null || !json.isObject()) throw new IOException("invalid_response");
                    var partial = json.path("partialSuccess");
                    if (!partial.isMissingNode() && !partial.isObject())
                        throw new IOException("invalid_response");
                    var rejected = partial.path("rejectedSpans");
                    if (!rejected.isMissingNode()
                            && !(rejected.isIntegralNumber()
                                    || rejected.isTextual()
                                            && rejected.asText().matches("[0-9]{1,18}")))
                        throw new IOException("invalid_response");
                    var message = partial.path("errorMessage");
                    if (!message.isMissingNode() && !message.isTextual())
                        throw new IOException("invalid_response");
                    if (partial.path("rejectedSpans").asLong(0) != 0)
                        throw new IOException("receiver_partial_rejection");
                }
            }
            return code;
        } finally {
            raw.close();
        }
    }

    private static byte[] chunks(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int count = 0; count < 4096; count++) {
            String size = line(in);
            if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("invalid_response");
            long length = Long.parseLong(size, 16);
            if (length > 65536 - result.size()) throw new IOException("invalid_response");
            if (length == 0) {
                // Trailers can contain secrets; consume bounded lines without retaining or logging
                // them.
                int total = 0;
                String trailer;
                while (!(trailer = line(in)).isEmpty()) {
                    total += trailer.length();
                    if (total > 16384) throw new IOException("invalid_response");
                }
                return result.toByteArray();
            }
            byte[] part = in.readNBytes((int) length);
            if (part.length != length || !line(in).isEmpty())
                throw new IOException("invalid_response");
            result.write(part);
        }
        throw new IOException("invalid_response");
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        boolean cr = false;
        while (b.size() < 8192) {
            int c = in.read();
            if (c < 0) throw new IOException("invalid_response");
            if (cr) {
                if (c != 10) throw new IOException("invalid_response");
                return b.toString(StandardCharsets.US_ASCII);
            }
            if (c == 13) cr = true;
            else if (c == 10 || c < 32 || c > 126) throw new IOException("invalid_response");
            else b.write(c);
        }
        throw new IOException("invalid_response");
    }

    static String safe(Throwable error) {
        if (error instanceof SSLException) return "tls_failure";
        if (error instanceof SocketTimeoutException) return "timeout";
        String code = error == null ? "" : error.getMessage();
        return Set.of(
                                "timeout",
                                "cancelled",
                                "endpoint_changed",
                                "endpoint_busy",
                                "endpoint_resolution_failed",
                                "redirect_rejected",
                                "invalid_response",
                                "payload_limit",
                                "receiver_partial_rejection")
                        .contains(Objects.toString(code, ""))
                ? code
                : "connection_failed";
    }

    public void cancel() {
        Exchange exchange = active.get();
        if (exchange != null) exchange.cancel();
    }

    @Override
    public void close() {
        cancel();
    }
}
