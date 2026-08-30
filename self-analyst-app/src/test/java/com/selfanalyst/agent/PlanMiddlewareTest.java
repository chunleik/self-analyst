package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.usage.UsageMeter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanMiddlewareTest {

    @Test
    void doesNotLogConversationContent() throws Exception {
        PlanMiddleware middleware = new PlanMiddleware(null);
        String privateContent = "private conversation content";
        AgentResultEvent result = new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent(privateContent)
                .build());
        TextBlockDeltaEvent delta = new TextBlockDeltaEvent(
                "reply", "block", privateContent);
        ReasoningInput reasoningInput = new ReasoningInput(List.of(), List.of(), null);
        List<String> loggedMessages = new CopyOnWriteArrayList<>();

        Class<?> appenderType = Class.forName("ch.qos.logback.core.Appender");
        Object appender = Proxy.newProxyInstance(
                appenderType.getClassLoader(),
                new Class<?>[]{appenderType},
                (proxy, method, args) -> {
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    } else if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    } else if ("doAppend".equals(method.getName())) {
                        Object event = args[0];
                        loggedMessages.add((String) event.getClass()
                                .getMethod("getFormattedMessage")
                                .invoke(event));
                    } else if ("isStarted".equals(method.getName())) {
                        return true;
                    }
                    return null;
                });
        Object logger = org.slf4j.LoggerFactory.getLogger(PlanMiddleware.class);
        Class<?> levelType = Class.forName("ch.qos.logback.classic.Level");
        Object originalLevel = logger.getClass().getMethod("getLevel").invoke(logger);
        Object debugLevel = levelType.getField("DEBUG").get(null);
        logger.getClass().getMethod("setLevel", levelType).invoke(logger, debugLevel);
        logger.getClass().getMethod("addAppender", appenderType).invoke(logger, appender);
        try {
            List<AgentEvent> agentEvents = middleware.onAgent(
                            null,
                            RuntimeContext.empty(),
                            null,
                            ignored -> Flux.just(result))
                    .collectList()
                    .block();
            List<AgentEvent> reasoningEvents = middleware.onReasoning(
                            null,
                            RuntimeContext.empty(),
                            reasoningInput,
                            ignored -> Flux.just(delta))
                    .collectList()
                    .block();

            assertEquals(List.of(result), agentEvents);
            assertEquals(List.of(delta), reasoningEvents);
            assertTrue(loggedMessages.stream()
                    .noneMatch(message -> message.contains(privateContent)));
        } finally {
            logger.getClass().getMethod("detachAppender", appenderType).invoke(logger, appender);
            logger.getClass().getMethod("setLevel", levelType)
                    .invoke(logger, new Object[]{originalLevel});
        }
    }

    @Test
    void estimatesTokensWithCl100kInsteadOfCharacterHeuristic() {
        assertEquals(2, PlanMiddleware.estimateTokens("hello world"));
        assertEquals(0, PlanMiddleware.estimateTokens(null));
    }

    @Test
    void preservesModelEventsAndEstimatesUsageWhenProviderOmitsIt(@TempDir Path tempDir) {
        UsageMeter meter = new UsageMeter(Config.testDefaults(tempDir), tempDir);
        try {
            PlanMiddleware middleware = new PlanMiddleware(meter);
            Msg user = Msg.builder().role(MsgRole.USER).textContent("input text").build();
            ModelCallInput input = new ModelCallInput(List.of(user), List.of(), null, null);
            TextBlockDeltaEvent delta = new TextBlockDeltaEvent("reply", "block", "abcdef");

            List<AgentEvent> events = middleware.onModelCall(
                            null,
                            RuntimeContext.empty(),
                            input,
                            ignored -> Flux.just(delta))
                    .collectList()
                    .block();

            assertEquals(List.of(delta), events);
            assertTrue(meter.totalTokens() > 2L, "fallback must include model input");
        } finally {
            meter.flush();
        }
    }

    @Test
    void metersModelCallOutsideReasoningUsingExactProviderUsage(@TempDir Path tempDir) {
        UsageMeter meter = new UsageMeter(Config.testDefaults(tempDir), tempDir);
        try {
            PlanMiddleware middleware = new PlanMiddleware(meter);
            ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);
            TextBlockDeltaEvent delta = new TextBlockDeltaEvent("reply", "block", "abcdef");
            ModelCallEndEvent end = new ModelCallEndEvent(
                    "reply", new ChatUsage(10, 3, 2, 0.1));

            List<AgentEvent> events = middleware.onModelCall(
                            null,
                            RuntimeContext.empty(),
                            input,
                            ignored -> Flux.just(delta, end))
                    .collectList()
                    .block();

            assertEquals(List.of(delta, end), events);
            assertEquals(13L, meter.totalTokens());
        } finally {
            meter.flush();
        }
    }

    @Test
    void keepsFallbackAccumulatorsIndependentAcrossSubscriptions(@TempDir Path tempDir) {
        UsageMeter meter = new UsageMeter(Config.testDefaults(tempDir), tempDir);
        try {
            PlanMiddleware middleware = new PlanMiddleware(meter);
            ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);
            Flux<AgentEvent> events = middleware.onModelCall(
                    null,
                    RuntimeContext.empty(),
                    input,
                    ignored -> Flux.just(new TextBlockDeltaEvent(
                            "reply", "block", "abcdef")));

            events.collectList().block();
            long firstCallTokens = meter.totalTokens();
            events.collectList().block();

            assertEquals(firstCallTokens * 2, meter.totalTokens());
        } finally {
            meter.flush();
        }
    }

    @Test
    void recordsFallbackUsageOnErrorAndCancellation(@TempDir Path tempDir) {
        UsageMeter meter = new UsageMeter(Config.testDefaults(tempDir), tempDir);
        try {
            PlanMiddleware middleware = new PlanMiddleware(meter);
            ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);
            TextBlockDeltaEvent delta = new TextBlockDeltaEvent("reply", "block", "abcdef");

            assertThrows(IllegalStateException.class, () -> middleware.onModelCall(
                            null,
                            RuntimeContext.empty(),
                            input,
                            ignored -> Flux.concat(
                                    Flux.just(delta),
                                    Flux.error(new IllegalStateException("boom"))))
                    .collectList()
                    .block());
            long afterError = meter.totalTokens();
            assertTrue(afterError > 0);

            middleware.onModelCall(
                            null,
                            RuntimeContext.empty(),
                            input,
                            ignored -> Flux.concat(Flux.just(delta), Flux.never()))
                    .take(1)
                    .blockLast();
            assertTrue(meter.totalTokens() > afterError);
        } finally {
            meter.flush();
        }
    }
}
