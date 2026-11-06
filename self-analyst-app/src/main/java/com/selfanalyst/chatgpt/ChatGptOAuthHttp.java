package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Bounded, redirect-free OAuth transport. Never include response bodies in exceptions. */
final class ChatGptOAuthHttp {
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final int MAX_BODY = 1024 * 1024;
    private final HttpClient client;
    private final URI origin;
    private final Duration timeout;

    ChatGptOAuthHttp(HttpClient client, URI origin) { this(client, origin, Duration.ofSeconds(20)); }

    ChatGptOAuthHttp(HttpClient client, URI origin, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(20)) > 0) throw failure("unsafe_transport");
        this.timeout = timeout;
        if (client.followRedirects() != HttpClient.Redirect.NEVER) throw failure("unsafe_transport");
        this.client = client;
        this.origin = origin;
    }

    URI trusted(String value) {
        try {
            URI uri = URI.create(value);
            if (!origin.getScheme().equals(uri.getScheme()) || !origin.getHost().equals(uri.getHost())
                    || origin.getPort() != uri.getPort() || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null) throw failure("untrusted_endpoint");
            return uri;
        } catch (IllegalArgumentException e) {
            throw failure("untrusted_endpoint");
        }
    }

    JsonNode get(URI uri) {
        Result response = send(HttpRequest.newBuilder(trusted(uri.toString())).timeout(Duration.ofSeconds(20)).GET().build());
        if (response.status != 200) throw failure("discovery_failed");
        return parse(response.body);
    }

    Result post(URI uri, Map<String, String> parameters) {
        return send(HttpRequest.newBuilder(trusted(uri.toString())).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters))).build());
    }

    private Result send(HttpRequest request) {
        LimitedBody body = new LimitedBody();
        var future = client.sendAsync(request, info -> body);
        try {
            HttpResponse<byte[]> response = future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return new Result(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("request_interrupted");
        } catch (java.util.concurrent.TimeoutException e) {
            throw failure("request_timeout");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
            throw failure("network_error");
        } finally {
            body.cancel();
            future.cancel(true);
        }
    }

    void shutdown() { client.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; if (cancelled) value.cancel(); else value.request(1); }
        void cancel() { cancelled = true; Flow.Subscription value = subscription; if (value != null) value.cancel(); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_BODY - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(failure("response_too_large")); return;
                }
                byte[] next = new byte[chunk.remaining()]; chunk.get(next); bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(failure("network_error")); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    static JsonNode parse(byte[] body) {
        try {
            JsonNode value = JSON.readTree(body);
            if (value == null || !value.isObject()) throw failure("invalid_response");
            return value;
        } catch (java.io.IOException e) {
            throw failure("invalid_response");
        }
    }

    static String required(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 65536)
            throw failure("invalid_response");
        return value.textValue();
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static IllegalStateException failure(String code) { return new IllegalStateException("chatgpt_" + code); }

    // Deliberately not a record: record.toString() would expose a remote token response.
    static final class Result {
        final int status;
        final byte[] body;
        Result(int status, byte[] body) { this.status = status; this.body = body; }
    }
}
