package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Public Responses transport for the Sign in with ChatGPT preview, not Chat Completions. */
public final class ChatGptResponsesModel implements Model, AutoCloseable {
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final String NAMESPACE = "selfanalyst";
    private static final String REASONING = "selfanalystChatgptReasoning";
    private static final int MAX_ERROR = 64 * 1024;
    private static final int MAX_FRAME = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE = 32 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String model;
    private final Supplier<String> accessToken;
    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Runnable> active = ConcurrentHashMap.newKeySet();

    public ChatGptResponsesModel(String model, Supplier<String> accessToken) {
        this(model, accessToken, ENDPOINT, HTTP, Duration.ofSeconds(45));
    }

    // Test-only transport injection is deliberately not a public configurable bearer-token destination.
    ChatGptResponsesModel(String model, Supplier<String> accessToken, URI endpoint,
                          HttpClient client, Duration timeout) {
        if (model == null || model.isBlank()) throw failure(Code.INVALID_REQUEST);
        this.model = model;
        this.accessToken = Objects.requireNonNull(accessToken);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.client = Objects.requireNonNull(client);
        this.timeout = Objects.requireNonNull(timeout);
        if (client.followRedirects() != HttpClient.Redirect.NEVER || timeout.isNegative() || timeout.isZero())
            throw failure(Code.INVALID_REQUEST);
    }

    @Override public String getModelName() { return model; }

    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.create(sink -> {
            AtomicReference<InputStream> body = new AtomicReference<>();
            AtomicBoolean disposed = new AtomicBoolean();
            AtomicReference<Disposable> deadline = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().unstarted(() -> {
                try {
                    if (sink.isCancelled() || closed.get()) return;
                    Request request = request(messages, tools, options);
                    String token;
                    try { token = accessToken.get(); }
                    catch (RuntimeException tokenFailure) {
                        throw failure(Code.valueOf(ChatGptTokenFailure.classify(tokenFailure).name()));
                    }
                    if (token == null || token.isBlank()) throw failure(Code.AUTHENTICATION);
                    HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(timeout)
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request.body())))
                            .build();
                    if (sink.isCancelled() || closed.get()) return;
                    HttpResponse<InputStream> response = client.send(http, HttpResponse.BodyHandlers.ofInputStream());
                    body.set(response.body());
                    try (InputStream input = response.body()) {
                        if (sink.isCancelled() || closed.get()) return;
                        if (response.statusCode() != 200) throw httpFailure(response.statusCode(), input);
                        if (!response.headers().firstValue("Content-Type").orElse("")
                                .split(";", 2)[0].trim().equalsIgnoreCase("text/event-stream")) throw failure(Code.PROTOCOL);
                        read(input, sink, request.names());
                    }
                } catch (Exception error) {
                    if (!disposed.get()) sink.error(error instanceof ChatGptResponsesException ? error
                            : failure(error instanceof java.net.http.HttpTimeoutException ? Code.TIMEOUT : Code.TRANSPORT));
                } finally {
                    closeBody(body.getAndSet(null));
                }
            });
            Runnable cancel = () -> {
                sink.error(failure(Code.CANCELLED));
                worker.interrupt();
                closeBody(body.getAndSet(null));
            };
            active.add(cancel);
            sink.onDispose(() -> {
                disposed.set(true);
                active.remove(cancel);
                Disposable timer = deadline.get();
                if (timer != null) timer.dispose();
                if (Thread.currentThread() != worker) worker.interrupt();
                closeBody(body.getAndSet(null));
            });
            if (closed.get()) { cancel.run(); return; }
            deadline.set(Schedulers.parallel().schedule(() -> sink.error(failure(Code.TIMEOUT)),
                    timeout.toNanos(), TimeUnit.NANOSECONDS));
            if (disposed.get()) { deadline.get().dispose(); return; }
            worker.start();
        });
    }

    @Override public void close() {
        closed.set(true);
        active.forEach(Runnable::run);
    }

    private record Request(ObjectNode body, Set<String> names) {}

    private Request request(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) throws Exception {
        ObjectNode request = JSON.createObjectNode().put("model", model).put("store", false).put("stream", true);
        request.putArray("include").add("reasoning.encrypted_content");
        ArrayNode input = request.putArray("input");
        for (Msg message : messages == null ? List.<Msg>of() : messages) {
            String role = switch (message.getRole()) {
                case SYSTEM -> "developer";
                case ASSISTANT -> "assistant";
                case USER -> "user";
                case TOOL -> "user";
            };
            // ReasoningContext propagates response metadata into the aggregated assistant Msg.
            // ThinkingBlock metadata is not preserved by AgentScope's thinking accumulator.
            if (message.getRole() == MsgRole.ASSISTANT && message.getMetadata() != null
                    && message.getMetadata().containsKey(REASONING)) {
                JsonNode reasoning = JSON.valueToTree(message.getMetadata().get(REASONING));
                if (!reasoning.isArray() || reasoning.size() > 256) throw failure(Code.INVALID_REQUEST);
                Set<String> seen = new HashSet<>();
                for (JsonNode item : reasoning) {
                    ObjectNode replay = reasoningItem(item, Code.INVALID_REQUEST);
                    if (!seen.add(replay.path("id").asText())) throw failure(Code.INVALID_REQUEST);
                    input.add(replay);
                }
            }
            ArrayNode content = JSON.createArrayNode();
            for (ContentBlock block : message.getContent()) {
                if (block instanceof ToolUseBlock use) {
                    flushMessage(input, content, role);
                    input.addObject().put("type", "function_call").put("call_id", required(use.getId()))
                            .put("name", required(use.getName())).put("namespace", NAMESPACE)
                            .put("arguments", JSON.writeValueAsString(use.getInput()));
                } else if (block instanceof ToolResultBlock result) {
                    flushMessage(input, content, role);
                    ObjectNode output = input.addObject().put("type", "function_call_output")
                            .put("call_id", required(result.getId()));
                    ArrayNode values = output.putArray("output");
                    for (ContentBlock value : result.getOutput()) values.add(inputContent(value));
                } else if (!(block instanceof ThinkingBlock)) {
                    content.add(inputContent(block));
                }
            }
            flushMessage(input, content, role);
        }
        List<ToolSchema> selected = tools == null ? List.of() : tools;
        ToolChoice choice = options == null ? null : options.getToolChoice();
        if (choice instanceof ToolChoice.Specific specific) {
            selected = selected.stream().filter(t -> t.getName().equals(specific.toolName())).toList();
            if (selected.size() != 1) throw failure(Code.INVALID_REQUEST);
        }
        Set<String> names = new HashSet<>();
        if (!selected.isEmpty()) {
            ObjectNode namespace = request.putArray("tools").addObject().put("type", "namespace")
                    .put("name", NAMESPACE).put("description", "SelfAnalyst local tools");
            ArrayNode definitions = namespace.putArray("tools");
            for (ToolSchema tool : selected) {
                if (!names.add(tool.getName())) throw failure(Code.INVALID_REQUEST);
                ObjectNode definition = definitions.addObject().put("type", "function")
                        .put("name", tool.getName()).put("description", tool.getDescription())
                        .put("strict", Boolean.TRUE.equals(tool.getStrict()));
                definition.set("parameters", JSON.valueToTree(tool.getParameters()));
            }
        }
        if (choice != null) request.put("tool_choice", choice instanceof ToolChoice.None ? "none"
                : choice instanceof ToolChoice.Auto ? "auto" : "required");
        if (options != null && options.getParallelToolCalls() != null)
            request.put("parallel_tool_calls", options.getParallelToolCalls());
        // Never forward arbitrary options, headers, destinations, temperature or output token limits.
        return new Request(request, choice instanceof ToolChoice.None ? Set.of() : names);
    }

    private static void flushMessage(ArrayNode input, ArrayNode content, String role) {
        if (content.isEmpty()) return;
        ObjectNode message = input.addObject().put("type", "message").put("role", role);
        message.set("content", content.deepCopy());
        content.removeAll();
    }

    private static ObjectNode inputContent(ContentBlock block) {
        if (block instanceof TextBlock text)
            return JSON.createObjectNode().put("type", "input_text").put("text", text.getText());
        if (block instanceof ImageBlock image && image.getSource() instanceof Base64Source source)
            return JSON.createObjectNode().put("type", "input_image")
                    .put("image_url", "data:" + source.getMediaType() + ";base64," + source.getData());
        // Local image references must be expanded by ChatImageModel, never leaked to the service.
        throw failure(Code.INVALID_REQUEST);
    }

    private static void read(InputStream input, FluxSink<ChatResponse> sink, Set<String> names) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        Map<String, StringBuilder> text = new HashMap<>();
        String id = UUID.randomUUID().toString();
        int total = 0;
        while (!sink.isCancelled()) {
            int next = reader.read();
            if (++total > MAX_RESPONSE) throw failure(Code.PROTOCOL);
            if (next != '\n' && next != -1) {
                line.append((char) next);
                if (line.length() > MAX_FRAME) throw failure(Code.PROTOCOL);
                continue;
            }
            String value = line.toString();
            line.setLength(0);
            if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
            if (value.startsWith("data:")) {
                String field = value.substring(5);
                if (field.startsWith(" ")) field = field.substring(1);
                data.append(field).append('\n');
                if (data.length() > MAX_FRAME) throw failure(Code.PROTOCOL);
            }
            if ((value.isEmpty() || next == -1) && !data.isEmpty()) {
                String frame = data.toString();
                data.setLength(0);
                if (frame.trim().equals("[DONE]")) throw failure(Code.PROTOCOL);
                JsonNode event;
                try { event = JSON.readTree(frame); }
                catch (Exception ignored) { throw failure(Code.PROTOCOL); }
                String type = event.path("type").asText();
                switch (type) {
                    case "response.output_text.delta", "response.refusal.delta" -> {
                        String delta = string(event, "delta");
                        String key = index(event, "output_index") + ":" + index(event, "content_index");
                        text.computeIfAbsent(key, ignored -> new StringBuilder()).append(delta);
                        if (!delta.isEmpty()) sink.next(ChatResponse.builder().id(id)
                                .content(List.of(TextBlock.builder().text(delta).build())).build());
                    }
                    case "response.completed" -> {
                        ChatResponse result = completed(event.path("response"), id, text, names);
                        if (!sink.isCancelled()) { sink.next(result); sink.complete(); }
                        return;
                    }
                    case "response.failed" -> throw streamFailure(event.path("response").path("error"));
                    case "error", "response.error" -> throw streamFailure(event.has("error") ? event.path("error") : event);
                    case "response.incomplete" -> throw failure(Code.PROTOCOL);
                    default -> { /* Lifecycle, reasoning, and argument fragments are not executable. */ }
                }
            }
            if (next == -1) throw failure(Code.PROTOCOL);
        }
    }

    private static ChatResponse completed(JsonNode response, String id, Map<String, StringBuilder> streamed,
                                          Set<String> names) throws Exception {
        if (!"completed".equals(response.path("status").asText()) || !response.path("output").isArray()
                || response.hasNonNull("error") || response.hasNonNull("incomplete_details"))
            throw failure(Code.PROTOCOL);
        List<ContentBlock> content = new ArrayList<>();
        Set<String> calls = new HashSet<>();
        List<Map<String, Object>> reasoning = new ArrayList<>();
        Set<String> reasoningIds = new HashSet<>();
        int outputIndex = 0;
        for (JsonNode item : response.path("output")) {
            if (item.hasNonNull("status") && !"completed".equals(item.path("status").asText()))
                throw failure(Code.PROTOCOL);
            switch (item.path("type").asText()) {
                case "message" -> {
                    if (!item.path("content").isArray()) throw failure(Code.PROTOCOL);
                    int contentIndex = 0;
                    for (JsonNode part : item.path("content")) {
                        String field = switch (part.path("type").asText()) {
                            case "output_text" -> "text";
                            case "refusal" -> "refusal";
                            default -> throw failure(Code.PROTOCOL);
                        };
                        String full = string(part, field);
                        StringBuilder prior = streamed.remove(outputIndex + ":" + contentIndex++);
                        String prefix = prior == null ? "" : prior.toString();
                        if (!full.startsWith(prefix)) throw failure(Code.PROTOCOL);
                        if (full.length() > prefix.length())
                            content.add(TextBlock.builder().text(full.substring(prefix.length())).build());
                    }
                }
                case "function_call" -> {
                    String name = string(item, "name");
                    String call = string(item, "call_id");
                    if (!NAMESPACE.equals(item.path("namespace").asText()) || !names.contains(name)
                            || call.isBlank() || !calls.add(call)) throw failure(Code.PROTOCOL);
                    String arguments = string(item, "arguments");
                    JsonNode parsed;
                    try { parsed = JSON.readTree(arguments); }
                    catch (Exception ignored) { throw failure(Code.PROTOCOL); }
                    if (parsed == null || !parsed.isObject()) throw failure(Code.PROTOCOL);
                    content.add(ToolUseBlock.builder().id(call).name(name).content(arguments)
                            .input(JSON.convertValue(parsed, new TypeReference<Map<String, Object>>() {})).build());
                }
                case "reasoning" -> {
                    ObjectNode replay = reasoningItem(item, Code.PROTOCOL);
                    if (reasoning.size() >= 256 || !reasoningIds.add(replay.path("id").asText()))
                        throw failure(Code.PROTOCOL);
                    reasoning.add(JSON.convertValue(replay, new TypeReference<Map<String, Object>>() {}));
                }
                default -> throw failure(Code.PROTOCOL);
            }
            outputIndex++;
        }
        if (!streamed.isEmpty()) throw failure(Code.PROTOCOL);
        ChatUsage usage = null;
        JsonNode counts = response.path("usage");
        if (counts.isObject() && counts.has("input_tokens") && counts.has("output_tokens"))
            usage = new ChatUsage(index(counts, "input_tokens"), index(counts, "output_tokens"),
                    counts.path("input_tokens_details").has("cached_tokens")
                            ? index(counts.path("input_tokens_details"), "cached_tokens") : 0, 0);
        return ChatResponse.builder().id(id).content(content).usage(usage)
                .metadata(reasoning.isEmpty() ? Map.of() : Map.of(REASONING, reasoning))
                .finishReason(calls.isEmpty() ? "stop" : "tool_calls").build();
    }

    private static ObjectNode reasoningItem(JsonNode item, Code invalid) {
        JsonNode id = item.path("id"), encrypted = item.path("encrypted_content");
        if (!"reasoning".equals(item.path("type").asText()) || !id.isTextual()
                || !id.textValue().matches("[A-Za-z0-9_-]{1,256}") || !encrypted.isTextual()
                || encrypted.textValue().isEmpty() || encrypted.textValue().length() > MAX_FRAME
                || !encrypted.textValue().matches("[A-Za-z0-9_+/=.\\-]+")) throw failure(invalid);
        ObjectNode safe = JSON.createObjectNode().put("type", "reasoning").put("id", id.textValue())
                .put("encrypted_content", encrypted.textValue());
        safe.putArray("summary");
        return safe;
    }

    private static int index(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw failure(Code.PROTOCOL);
        return value.intValue();
    }
    private static String string(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw failure(Code.PROTOCOL);
        return node.path(field).textValue();
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw failure(Code.INVALID_REQUEST);
        return value;
    }
    private static void closeBody(InputStream body) {
        if (body != null) try { body.close(); } catch (Exception ignored) { }
    }
    private static ChatGptResponsesException failure(Code code) { return new ChatGptResponsesException(code, null); }
    private static ChatGptResponsesException streamFailure(JsonNode error) {
        // Exact, documented machine codes only. Never retain diagnostic text or claim an SSE
        // error changed the already-successful HTTP status. Unknown codes stay fail-closed.
        return failure(switch (error.path("code").asText()) {
            case "subscription_sharing_usage_limit_exceeded" -> Code.RATE_LIMIT;
            case "subscription_sharing_invalid_user" -> Code.AUTHENTICATION;
            case "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported",
                    "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> Code.ACCESS_DENIED;
            case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> Code.HTTP;
            case "subscription_sharing_unsupported_capability" -> Code.INVALID_REQUEST;
            default -> Code.PROTOCOL;
        });
    }
    private static ChatGptResponsesException httpFailure(int status, InputStream input) {
        // Admission failures may not be JSON. Parse only a small body and exact safe machine
        // codes, preserve the real HTTP status, and never expose text, params or parser causes.
        try {
            byte[] bytes = input.readNBytes(MAX_ERROR + 1);
            if (bytes.length <= MAX_ERROR) {
                JsonNode body = JSON.readTree(bytes);
                ChatGptResponsesException classified = streamFailure(body.path("error"));
                if (classified.code() != Code.PROTOCOL)
                    return new ChatGptResponsesException(classified.code(), status);
            }
        } catch (Exception ignored) { }
        return httpFailure(status);
    }
    private static ChatGptResponsesException httpFailure(int status) {
        return new ChatGptResponsesException(switch (status) {
            case 401 -> Code.AUTHENTICATION;
            case 403 -> Code.ACCESS_DENIED;
            case 429 -> Code.RATE_LIMIT;
            default -> Code.HTTP;
        }, status);
    }
    public enum Code { AUTHENTICATION, ACCESS_DENIED, RATE_LIMIT, HTTP, INVALID_REQUEST, PROTOCOL, TIMEOUT, TRANSPORT, CANCELLED }

    /** Contains only fixed local diagnostics and an HTTP status, never remote text or a token-bearing cause. */
    public static final class ChatGptResponsesException extends RuntimeException implements ModelHttpException {
        private final Code code;
        private final Integer status;
        private ChatGptResponsesException(Code code, Integer status) {
            super("ChatGPT request failed: " + code.name() + (status == null ? "" : " (HTTP " + status + ")"));
            this.code = code;
            this.status = status;
        }
        public Code code() { return code; }
        @Override public Integer getStatusCode() { return status; }
    }
}
