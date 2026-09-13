package com.selfanalyst.llm.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.config.LlmSettings;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 显式诊断只发送固定请求，响应有界且不返回远端正文。 */
public final class LlmConnectionProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 1024 * 1024;
    private final Duration timeout;
    public LlmConnectionProbe() { this(Duration.ofSeconds(15)); }
    public LlmConnectionProbe(Duration timeout) { this.timeout = timeout; }
    public record Result(boolean ok, String code, List<String> models, long latencyMs, boolean truncated) {}

    public Result run(LlmSettings connection, boolean discovery) {
        long start = System.nanoTime();
        if (!connection.available()) return result(start, "not_configured");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(URI.create(connection.baseUrl() + (discovery ? "/models" : "/chat/completions")))
                    .timeout(timeout).header("Authorization", "Bearer " + connection.apiKey());
            if (discovery) request.GET();
            else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(Map.of("model", connection.model(), "stream", false, "max_tokens", 16,
                            "messages", List.of(Map.of("role", "user", "content", "Reply OK."))))));
            var subscriber = new BoundedBody();
            var future = client.sendAsync(request.build(), info -> subscriber);
            HttpResponse<byte[]> response;
            try { response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (TimeoutException timeoutFailure) {
                subscriber.cancel(); future.cancel(true); return result(start, "timeout");
            } catch (InterruptedException interrupted) {
                subscriber.cancel(); future.cancel(true); Thread.currentThread().interrupt(); return result(start, "network");
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) return result(start, switch (status) {
                case 401 -> "authentication"; case 403 -> "permission";
                case 404 -> discovery ? "unsupported_discovery" : "model_not_found";
                case 405, 501 -> discovery ? "unsupported_discovery" : "protocol";
                case 429 -> "rate_limit"; case 400, 422 -> "parameters";
                default -> "http";
            });
            var body = JSON.readTree(response.body());
            if (body == null || !body.isObject()) return result(start, "protocol");
            if (discovery) {
                var data = body.path("data");
                if (!data.isArray()) return result(start, "protocol");
                var models = new LinkedHashSet<String>();
                boolean truncated = false;
                for (var item : data) {
                    var id = item.path("id");
                    if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 256) continue;
                    String name = id.asText().trim();
                    if (name.contains(connection.apiKey())) continue;
                    if (models.size() >= 500 && !models.contains(name)) { truncated = true; continue; }
                    models.add(name);
                }
                return new Result(true, "success", List.copyOf(models), elapsed(start), truncated);
            }
            var text = body.path("choices").path(0).path("message").path("content");
            return result(start, text.isTextual() && !text.asText().isBlank() ? "success" : "protocol");
        } catch (Exception failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseLimitException) return result(start, "response_limit");
                if (cause instanceof HttpTimeoutException) return result(start, "timeout");
                if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) return result(start, "protocol");
            }
            return result(start, "network");
        }
    }
    private static Result result(long start, String code) {
        return new Result(code.equals("success"), code, List.of(), elapsed(start), false);
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static final class ResponseLimitException extends RuntimeException {}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (body.isDone()) value.cancel(); else value.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    body.completeExceptionally(new ResponseLimitException()); subscription.cancel(); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { body.completeExceptionally(failure); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
        void cancel() { body.cancel(true); if (subscription != null) subscription.cancel(); }
    }
}
