package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 仅显式查询官方账户目录；不把 OAuth 凭据交给用户可编辑的 Base URL。 */
public final class ChatGptModelCatalog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 1024 * 1024;
    private final URI endpoint;
    private final Duration timeout;

    public ChatGptModelCatalog() { this(URI.create("https://api.openai.com/v1/models"), Duration.ofSeconds(15)); }
    ChatGptModelCatalog(URI endpoint, Duration timeout) { this.endpoint = endpoint; this.timeout = timeout; }
    public record Entry(String slug, String displayName) {}
    public record Result(boolean ok, String code, List<Entry> models, boolean truncated) {}

    public Result fetch(Supplier<String> accessToken) {
        String token;
        try { token = accessToken.get(); }
        catch (RuntimeException unavailable) {
            return failure(switch (ChatGptTokenFailure.classify(unavailable)) {
                case AUTHENTICATION -> "authentication";
                case ACCESS_DENIED -> "permission";
                case TIMEOUT -> "timeout";
                case TRANSPORT, CANCELLED -> "network";
            });
        }
        if (token == null || token.isBlank()) return failure("authentication");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
            var subscriber = new BoundedBody();
            var pending = client.sendAsync(request, ignored -> subscriber);
            HttpResponse<byte[]> response;
            try { response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (TimeoutException timedOut) { subscriber.cancel(); pending.cancel(true); return failure("timeout"); }
            catch (InterruptedException interrupted) {
                subscriber.cancel(); pending.cancel(true); Thread.currentThread().interrupt(); return failure("network");
            }
            if (response.statusCode() != 200) return failure(switch (response.statusCode()) {
                case 401 -> "authentication"; case 403 -> "permission"; case 429 -> "rate_limit";
                case 404, 405, 501 -> "unsupported_discovery"; default -> "http";
            });
            var body = JSON.readTree(response.body());
            if (body == null || !body.path("models").isArray()) return failure("protocol");
            var entries = new LinkedHashMap<String, Entry>();
            boolean truncated = false;
            for (var item : body.path("models")) {
                if (!"list".equals(item.path("visibility").asText())) continue;
                var slug = item.path("slug");
                var name = item.path("display_name");
                if (!slug.isTextual() || !name.isTextual() || !valid(slug.asText(), token) || !valid(name.asText(), token)) continue;
                if (entries.size() >= 500 && !entries.containsKey(slug.asText())) { truncated = true; continue; }
                entries.putIfAbsent(slug.asText(), new Entry(slug.asText(), name.asText()));
            }
            return new Result(true, "success", List.copyOf(entries.values()), truncated);
        } catch (Exception failed) {
            for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
                if (cause instanceof ResponseLimitException) return failure("response_limit");
                if (cause instanceof HttpTimeoutException) return failure("timeout");
                if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) return failure("protocol");
            }
            return failure("network");
        }
    }
    private static boolean valid(String text, String token) {
        return !text.isBlank() && text.length() <= 256 && !text.contains(token)
                && text.chars().noneMatch(Character::isISOControl);
    }
    private static Result failure(String code) { return new Result(false, code, List.of(), false); }
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
