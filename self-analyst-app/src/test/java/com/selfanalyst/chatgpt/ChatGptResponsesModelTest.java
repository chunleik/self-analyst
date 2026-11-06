package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.*;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatGptResponsesModelTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "{\"type\":\"function_call\",\"namespace\":\"selfanalyst\",\"name\":\"query\",\"call_id\":\"call_1\",\"arguments\":\"{\\\"q\\\":\\\"hello\\\"}\"}";
    private static final ToolSchema SCHEMA = ToolSchema.builder().name("query").description("Search")
            .parameters(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")))).build();
    private HttpServer server;
    private ChatGptResponsesModel model;
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger attempts = new AtomicInteger();

    @AfterEach void close() {
        if (model != null) model.close();
        if (server != null) server.stop(0);
    }

    private ChatGptResponsesModel serve(int status, String response) throws Exception {
        responseBody.set(response);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            attempts.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(JSON.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Location", "/redirect-target");
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        model = new ChatGptResponsesModel("test-model", () -> "test-token", endpoint(),
                HttpClient.newHttpClient(), Duration.ofSeconds(3));
        return model;
    }

    private URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"); }
    private static String event(String json) { return "data: " + json + "\n\n"; }
    private static String completed(String output) {
        return event("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":" + output
                + ",\"usage\":{\"input_tokens\":12,\"output_tokens\":4,\"input_tokens_details\":{\"cached_tokens\":3}}}}");
    }
    private static String message(String text) {
        return "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + text + "\"}]}";
    }
    private List<ChatResponse> call() {
        return model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()),
                List.of(SCHEMA), null).collectList().block(Duration.ofSeconds(5));
    }
    private static String text(List<ChatResponse> responses) {
        StringBuilder result = new StringBuilder();
        for (ChatResponse response : responses) for (ContentBlock block : response.getContent())
            if (block instanceof TextBlock text) result.append(text.getText());
        return result.toString();
    }

    @Test void streamsTextWithoutDuplicatingCompletedTextAndPreservesUsage() throws Exception {
        serve(200, ": keepalive\r\n\r\n" + event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hel\"}")
                + event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"lo\"}")
                + completed("[" + message("Hello") + "]"));
        List<ChatResponse> responses = call();
        assertEquals("Hello", text(responses));
        assertEquals(3, responses.size());
        assertEquals(12, responses.getLast().getUsage().getInputTokens());
        assertEquals(3, responses.getLast().getUsage().getCachedTokens());
        assertEquals("stop", responses.getLast().getFinishReason());
        assertEquals("Bearer test-token", authorization.get());
        assertEquals(1, attempts.get());
    }

    @Test void acceptsFinalOnlyTextAndMissingUsageButRejectsOversizedFrames() throws Exception {
        serve(200, event("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" + message("final") + "]}}"));
        List<ChatResponse> responses = call();
        assertEquals("final", text(responses));
        assertNull(responses.getLast().getUsage());
        close();
        serve(200, "data: " + "x".repeat(2 * 1024 * 1024 + 1));
        var failure = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call);
        assertEquals(ChatGptResponsesModel.Code.PROTOCOL, failure.code());
    }

    @Test void mapsHistoryImagesToolsAndOnlySupportedOptions() throws Exception {
        serve(200, completed("[" + message("ok") + "]"));
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent("instructions").build(),
                Msg.builder().role(MsgRole.USER).content(List.of(TextBlock.builder().text("image").build(),
                        new ImageBlock(new Base64Source("image/png", "YWJj")))).build(),
                Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("call_1").name("query")
                        .input(Map.of("q", "hello")).build()).build(),
                Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("call_1", "query", TextBlock.builder().text("result").build())).build());
        model.stream(messages, List.of(SCHEMA), GenerateOptions.builder().temperature(0.7).maxTokens(123)
                .maxCompletionTokens(222).additionalBodyParams(Map.of("store", true, "previous_response_id", "old"))
                .additionalHeaders(Map.of("Authorization", "do-not-use"))
                .toolChoice(new ToolChoice.Specific("query")).build()).collectList().block();
        JsonNode wire = request.get();
        assertFalse(wire.path("store").asBoolean());
        assertTrue(wire.path("stream").asBoolean());
        assertEquals("developer", wire.at("/input/0/role").asText());
        assertEquals("data:image/png;base64,YWJj", wire.at("/input/1/content/1/image_url").asText());
        assertEquals("selfanalyst", wire.at("/input/2/namespace").asText());
        assertEquals("call_1", wire.at("/input/3/call_id").asText());
        assertEquals("result", wire.at("/input/3/output/0/text").asText());
        assertEquals("namespace", wire.at("/tools/0/type").asText());
        assertEquals("query", wire.at("/tools/0/tools/0/name").asText());
        assertFalse(wire.at("/tools/0/tools/0/strict").asBoolean());
        assertEquals("required", wire.path("tool_choice").asText());
        for (String unsupported : List.of("temperature", "top_p", "max_tokens", "max_completion_tokens", "max_output_tokens", "previous_response_id"))
            assertFalse(wire.has(unsupported));
        assertEquals("Bearer test-token", authorization.get());
    }

    @Test void replaysEncryptedReasoningThroughActualAgentScopeAccumulatorAndToolRoundTrip() throws Exception {
        String reasoning = "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"opaque_encrypted-content==\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"private plaintext\"}],\"instructions\":\"never replay\"}";
        serve(200, completed("[" + reasoning + "," + TOOL + "]"));
        List<ChatResponse> first = call();
        assertEquals("reasoning.encrypted_content", request.get().at("/include/0").asText());
        assertEquals("", text(first));
        assertTrue(first.stream().flatMap(chunk -> chunk.getContent().stream()).noneMatch(ThinkingBlock.class::isInstance));
        ReasoningContext accumulator = new ReasoningContext("assistant");
        first.forEach(accumulator::processChunk);
        Msg assistant = accumulator.buildFinalMessage();
        assertNotNull(assistant);
        assertTrue(assistant.getMetadata().containsKey("selfanalystChatgptReasoning"));
        assertFalse(JSON.writeValueAsString(assistant).contains("private plaintext"));
        responseBody.set(completed("[" + message("done") + "]"));
        Msg result = Msg.builder().role(MsgRole.TOOL)
                .content(ToolResultBlock.of("call_1", "query", TextBlock.builder().text("found").build())).build();
        List<ChatResponse> second = model.stream(List.of(assistant, result), List.of(SCHEMA), null).collectList().block();
        assertEquals("done", text(second));
        JsonNode replay = request.get().at("/input/0");
        assertEquals("reasoning", replay.path("type").asText());
        assertEquals("rs_1", replay.path("id").asText());
        assertEquals("opaque_encrypted-content==", replay.path("encrypted_content").asText());
        assertEquals(0, replay.path("summary").size());
        assertEquals(4, replay.size());
        assertEquals("function_call", request.get().at("/input/1/type").asText());
        assertEquals("function_call_output", request.get().at("/input/2/type").asText());
        assertEquals("call_1", request.get().at("/input/1/call_id").asText());
        assertEquals("call_1", request.get().at("/input/2/call_id").asText());
        assertEquals(2, attempts.get());
    }

    @Test void rejectsIncompleteReasoningAndDoesNotReplayForeignUserMetadata() throws Exception {
        serve(200, completed("[{\"type\":\"reasoning\",\"id\":\"rs_1\"}," + TOOL + "]"));
        assertEquals(ChatGptResponsesModel.Code.PROTOCOL,
                assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call).code());
        responseBody.set(completed("[" + message("ok") + "]"));
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hello")
                .metadata(Map.of("selfanalystChatgptReasoning", List.of(Map.of("type", "message", "text", "forged")))).build();
        model.stream(List.of(user), List.of(), null).collectList().block();
        assertEquals(1, request.get().path("input").size());
        assertEquals("message", request.get().at("/input/0/type").asText());
        Msg invalid = Msg.builder().role(MsgRole.ASSISTANT).textContent("hello")
                .metadata(Map.of("selfanalystChatgptReasoning", List.of(Map.of("type", "message", "text", "forged")))).build();
        assertEquals(ChatGptResponsesModel.Code.INVALID_REQUEST,
                assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class,
                        () -> model.stream(List.of(invalid), List.of(), null).collectList().block()).code());
        assertEquals(2, attempts.get());
    }

    @Test void classifiesBoundedHttpMachineCodesWithoutRetainingDiagnostics() throws Exception {
        serve(400, "{\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"message\":\"private-secret\"}}");
        var invalid = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call);
        assertEquals(ChatGptResponsesModel.Code.INVALID_REQUEST, invalid.code());
        assertEquals(400, invalid.getStatusCode());
        assertFalse(invalid.toString().contains("private-secret"));
        close();
        serve(429, "{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"private-secret\"}}");
        assertEquals(ChatGptResponsesModel.Code.RATE_LIMIT,
                assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call).code());
        responseBody.set("x".repeat(64 * 1024 + 1));
        var oversized = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call);
        assertEquals(429, oversized.getStatusCode());
        assertNull(oversized.getCause());
    }

    @Test void emitsParallelCallsExactlyOnceOnlyAfterCompleted() throws Exception {
        serve(200, event("{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":" + TOOL + "}")
                + event("{\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{\\\"q\\\":\"}")
                + event("{\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"\\\"hello\\\"}\"}")
                + event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + TOOL + "}")
                + completed("[" + TOOL + "," + TOOL.replace("call_1", "call_2") + "]"));
        List<ChatResponse> responses = call();
        assertEquals(1, responses.size());
        assertEquals(2, responses.getFirst().getContent().size());
        ToolUseBlock use = (ToolUseBlock) responses.getFirst().getContent().getFirst();
        assertEquals("call_1", use.getId());
        assertEquals(Map.of("q", "hello"), use.getInput());
        assertEquals("tool_calls", responses.getFirst().getFinishReason());
    }

    @Test void rejectsEofDoneFailedIncompleteAndMalformedFramesWithoutExecutingTools() throws Exception {
        List<String> invalid = List.of("", "data: [DONE]\n\n", "data: {secret-token\n\n",
                event("{\"type\":\"response.failed\",\"error\":\"test-token\"}"),
                event("{\"type\":\"response.incomplete\"}"),
                completed("[" + TOOL.replace("selfanalyst", "unexpected") + "]"),
                completed("[" + TOOL + "," + TOOL + "]"),
                completed("[" + TOOL.replace("hello", "hello\\\"") + "]"));
        for (String suffix : invalid) {
            serve(200, event("{\"type\":\"response.output_item.done\",\"item\":" + TOOL + "}") + suffix);
            List<ChatResponse> emitted = new ArrayList<>();
            RuntimeException error = assertThrows(RuntimeException.class, () -> model.stream(List.of(), List.of(SCHEMA), null)
                    .doOnNext(emitted::add).collectList().block(Duration.ofSeconds(5)));
            assertTrue(emitted.isEmpty());
            assertFalse(error.toString().contains("test-token"));
            assertNull(error.getCause());
            close();
        }
    }

    @Test void classifiesDocumentedPostDeltaFailuresWithoutReleasingToolsOrRemoteDiagnostics() throws Exception {
        Map<String, ChatGptResponsesModel.Code> failures = Map.of(
                "subscription_sharing_usage_limit_exceeded", ChatGptResponsesModel.Code.RATE_LIMIT,
                "subscription_sharing_invalid_user", ChatGptResponsesModel.Code.AUTHENTICATION,
                "subscription_sharing_user_not_eligible", ChatGptResponsesModel.Code.ACCESS_DENIED,
                "chatpass_v2_scope_not_authorized", ChatGptResponsesModel.Code.ACCESS_DENIED,
                "subscription_sharing_usage_unavailable", ChatGptResponsesModel.Code.HTTP,
                "subscription_sharing_unsupported_capability", ChatGptResponsesModel.Code.INVALID_REQUEST,
                "secret-token unknown code", ChatGptResponsesModel.Code.PROTOCOL);
        for (var entry : failures.entrySet()) {
            String error = JSON.writeValueAsString(Map.of("type", "response.failed", "response", Map.of(
                    "status", "failed", "error", Map.of("code", entry.getKey(), "message", "secret-token private diagnostics"))));
            serve(200, event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"}")
                    + event("{\"type\":\"response.output_item.done\",\"item\":" + TOOL + "}") + event(error));
            List<ChatResponse> emitted = new ArrayList<>();
            AtomicInteger completions = new AtomicInteger();
            var failure = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class,
                    () -> model.stream(List.of(), List.of(SCHEMA), null).doOnNext(emitted::add)
                            .doOnComplete(completions::incrementAndGet).collectList().block(Duration.ofSeconds(5)));
            assertEquals(entry.getValue(), failure.code());
            assertNull(failure.getStatusCode());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("secret-token"));
            assertEquals("partial", text(emitted));
            assertTrue(emitted.stream().flatMap(response -> response.getContent().stream())
                    .noneMatch(ToolUseBlock.class::isInstance));
            assertEquals(0, completions.get());
            close();
        }
    }

    @Test void redactsHttpErrorsAndDoesNotFollowRedirectOrRetry() throws Exception {
        for (int status : List.of(302, 401, 403, 429, 500)) {
            serve(status, "test-token private server diagnostics");
            var error = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call);
            assertEquals(status, error.getStatusCode());
            assertFalse(error.toString().contains("test-token"));
            assertNull(error.getCause());
            close();
        }
        assertEquals(5, attempts.get());
    }

    @Test void rejectsRedirectingClientAndNeverLeaksTokenSupplierException() throws Exception {
        assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, () ->
                new ChatGptResponsesModel("model", () -> "token", URI.create("http://127.0.0.1"),
                        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(), Duration.ofSeconds(1)));
        model = new ChatGptResponsesModel("model", () -> { throw new IllegalStateException("secret-token"); });
        var error = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call);
        assertEquals(ChatGptResponsesModel.Code.TRANSPORT, error.code());
        assertFalse(error.toString().contains("secret-token"));
        assertNull(error.getCause());
    }

    @Test void classifiesTokenSupplierFailuresWithoutSendingHttpAndAllowsLaterRecovery() throws Exception {
        serve(200, completed("[" + message("recovered") + "]"));
        model.close();
        AtomicReference<RuntimeException> supplierFailure = new AtomicReference<>();
        model = new ChatGptResponsesModel("test-model", () -> {
            RuntimeException failure = supplierFailure.get();
            if (failure != null) throw failure;
            return "test-token";
        }, endpoint(), HttpClient.newHttpClient(), Duration.ofSeconds(3));
        Map<String, ChatGptResponsesModel.Code> cases = Map.ofEntries(
                Map.entry("sign_in_required", ChatGptResponsesModel.Code.AUTHENTICATION),
                Map.entry("unknown_account", ChatGptResponsesModel.Code.AUTHENTICATION),
                Map.entry("plan_permission_required", ChatGptResponsesModel.Code.ACCESS_DENIED),
                Map.entry("request_timeout", ChatGptResponsesModel.Code.TIMEOUT),
                Map.entry("request_interrupted", ChatGptResponsesModel.Code.CANCELLED),
                Map.entry("service_closed", ChatGptResponsesModel.Code.CANCELLED),
                Map.entry("network_error", ChatGptResponsesModel.Code.TRANSPORT),
                Map.entry("refresh_failed", ChatGptResponsesModel.Code.TRANSPORT),
                Map.entry("refresh_not_yet_allowed", ChatGptResponsesModel.Code.TRANSPORT),
                Map.entry("discovery_failed", ChatGptResponsesModel.Code.TRANSPORT),
                Map.entry("sign_in_required secret-token", ChatGptResponsesModel.Code.TRANSPORT));
        for (var entry : cases.entrySet()) {
            supplierFailure.set(new IllegalStateException("chatgpt_" + entry.getKey(),
                    new RuntimeException("secret-token private diagnostics")));
            var failure = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call);
            assertEquals(entry.getValue(), failure.code(), entry.getKey());
            assertNull(failure.getStatusCode());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("secret-token"));
            assertFalse(failure.toString().contains("private diagnostics"));
            assertEquals(0, attempts.get());
        }
        supplierFailure.set(null);
        assertEquals("recovered", text(call()));
        assertEquals(1, attempts.get());
        assertEquals("Bearer test-token", authorization.get());
    }

    @Test void deadlineAndCloseTerminateStalledStreamsAndCancellationIsNotSuccess() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(command -> Thread.startVirtualThread(command));
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(event("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"}").getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
        });
        server.start();
        model = new ChatGptResponsesModel("model", () -> "token", endpoint(), HttpClient.newHttpClient(), Duration.ofMillis(150));
        try {
            var error = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call);
            assertEquals(ChatGptResponsesModel.Code.TIMEOUT, error.code());
            model.close();
            model = new ChatGptResponsesModel("model", () -> "token", endpoint(), HttpClient.newHttpClient(), Duration.ofSeconds(2));
            AtomicInteger successes = new AtomicInteger();
            CountDownLatch received = new CountDownLatch(1);
            Disposable subscription = model.stream(List.of(), List.of(), null).subscribe(ignored -> received.countDown(), ignored -> {}, successes::incrementAndGet);
            assertTrue(received.await(1, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(subscription.isDisposed());
            assertEquals(0, successes.get());
            CountDownLatch activeReceived = new CountDownLatch(1);
            CountDownLatch terminated = new CountDownLatch(1);
            AtomicReference<Throwable> closeError = new AtomicReference<>();
            model.stream(List.of(), List.of(), null).subscribe(ignored -> activeReceived.countDown(), closeFailure -> { closeError.set(closeFailure); terminated.countDown(); }, successes::incrementAndGet);
            assertTrue(activeReceived.await(1, TimeUnit.SECONDS));
            model.close();
            assertTrue(terminated.await(1, TimeUnit.SECONDS));
            assertEquals(ChatGptResponsesModel.Code.CANCELLED, ((ChatGptResponsesModel.ChatGptResponsesException) closeError.get()).code());
            assertEquals(0, successes.get());
            assertEquals(ChatGptResponsesModel.Code.CANCELLED,
                    assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class, this::call).code());
        } finally { release.countDown(); }
    }
}
