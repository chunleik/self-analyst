package com.selfanalyst.agent;

import com.selfanalyst.chatgpt.ChatGptResponsesModel;
import com.selfanalyst.wiki.WikiSummaryPipeline.CallFailure;
import com.selfanalyst.wiki.WikiSummaryPipeline.FailureKind;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class PlainTransportFailureTest {
    @Test void tokenRefreshFailuresRemainRetryableButMissingGrantStopsBackgroundWork() throws Exception {
        var cases = Map.of(
                "chatgpt_network_error", FailureKind.TRANSPORT,
                "chatgpt_refresh_failed", FailureKind.TRANSPORT,
                "chatgpt_refresh_not_yet_allowed", FailureKind.TRANSPORT,
                "chatgpt_request_timeout", FailureKind.TIMEOUT,
                "chatgpt_request_interrupted", FailureKind.INTERRUPTED,
                "chatgpt_sign_in_required", FailureKind.CONFIGURATION,
                "chatgpt_plan_permission_required", FailureKind.CONFIGURATION);
        for (var entry : cases.entrySet()) {
            try (var model = new ChatGptResponsesModel("test-model", () -> {
                throw new IllegalStateException(entry.getKey(), new RuntimeException("private-token"));
            })) {
                var failure = assertThrows(ChatGptResponsesModel.ChatGptResponsesException.class,
                        () -> model.stream(List.of(), List.of(), null).collectList().block(Duration.ofSeconds(2)));
                var result = classify(new CompletionException(failure), null);
                assertEquals(entry.getValue(), result.kind(), entry.getKey());
                assertNull(result.getCause());
                assertFalse(result.toString().contains("private-token"));
            }
        }
    }

    @Test void nestedTransportStatusWinsOverAuthenticationDigitsInTheBody() throws Exception {
        Throwable failure = new CompletionException(new ModelException("outer private 401",
                new HttpTransportException("private provider response", 500, "secret-body 401")));
        CallFailure result = classify(failure, new ChatUsage(11, 7, .1));
        assertEquals("WIKI_MODEL_HTTP_500", result.code());
        assertEquals(FailureKind.TRANSPORT, result.kind());
        assertEquals(11L, result.inputTokens());
        assertEquals(7L, result.outputTokens());
        assertFalse(result.beforeSend());
        assertNull(result.getCause());
        assertFalse(result.toString().contains("private"));
        assertFalse(result.toString().contains("secret-body"));
    }

    @Test void typedPaymentAndValidationFailuresAreConfigurationFailures() throws Exception {
        for (int status : new int[]{402, 422}) {
            Throwable failure = new RuntimeException("private wrapper 500",
                    new TypedFailure(status, null));
            CallFailure result = classify(failure, null);
            assertEquals("WIKI_MODEL_HTTP_" + status, result.code());
            assertEquals(FailureKind.CONFIGURATION, result.kind());
            assertFalse(result.beforeSend());
            assertNull(result.inputTokens());
            assertNull(result.outputTokens());
        }
    }

    @Test void nestedRateLimitAndServerStatusesRemainTransportFailures() throws Exception {
        for (int status : new int[]{429, 500, 501, 503}) {
            CallFailure result = classify(new ModelException("opaque wrapper", new TypedFailure(status, null)), null);
            assertEquals("WIKI_MODEL_HTTP_" + status, result.code());
            assertEquals(FailureKind.TRANSPORT, result.kind());
        }
    }

    @Test void missingOrInvalidOuterStatusDoesNotHideAValidInnerStatus() throws Exception {
        for (Integer status : new Integer[]{null, -1, 999}) {
            CallFailure result = classify(new TypedFailure(status, new TypedFailure(502, null)), null);
            assertEquals("WIKI_MODEL_HTTP_502", result.code());
        }
        assertEquals("WIKI_MODEL_FAILURE", classify(null, null).code());
    }

    @Test void messageOnlyAndTlsErrorsCannotPretendToBeHttpPaymentErrors() throws Exception {
        CallFailure result = classify(new ModelException("private 401",
                new SSLHandshakeException("private TLS detail including 402")), null);
        assertEquals("WIKI_MODEL_FAILURE", result.code());
        assertEquals(FailureKind.TRANSPORT, result.kind());
        assertNull(result.getCause());
    }

    @Test void cyclicAndDeepCauseChainsTerminateWithoutReadingMessages() {
        CycleFailure first = new CycleFailure();
        CycleFailure second = new CycleFailure();
        first.next = second;
        second.next = first;
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertEquals("WIKI_MODEL_FAILURE", classify(first, null).code()));

        Throwable deep = new TypedFailure(500, null);
        for (int i = 0; i < 64; i++) deep = new RuntimeException("private 401", deep);
        Throwable bounded = deep;
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertEquals("WIKI_MODEL_FAILURE", classify(bounded, null).code()));
    }

    private static CallFailure classify(Throwable failure, ChatUsage usage) throws Exception {
        var method = SelfAnalystAgent.class.getDeclaredMethod("plainTransportFailure", Throwable.class, ChatUsage.class);
        method.setAccessible(true);
        return (CallFailure) method.invoke(null, failure, usage);
    }

    private static final class TypedFailure extends RuntimeException implements ModelHttpException {
        private final Integer status;
        TypedFailure(Integer status, Throwable cause) { super("private body with false 401", cause); this.status = status; }
        @Override public Integer getStatusCode() { return status; }
        @Override public String getMessage() { throw new AssertionError("The classifier must not inspect provider text"); }
    }

    private static final class CycleFailure extends RuntimeException {
        private Throwable next;
        @Override public synchronized Throwable getCause() { return next; }
        @Override public String getMessage() { throw new AssertionError("The classifier must not inspect provider text"); }
    }
}
