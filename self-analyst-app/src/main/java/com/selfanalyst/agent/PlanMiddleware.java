package com.selfanalyst.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.usage.UsageMeter;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Agent lifecycle logging and token-budget enforcement for AgentScope 2.x. */
public final class PlanMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PlanMiddleware.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Nullable when token metering is disabled. */
    private final UsageMeter usageMeter;

    public PlanMiddleware(UsageMeter usageMeter) {
        this.usageMeter = usageMeter;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> {
            if (event instanceof AgentResultEvent result && result.getResult() != null) {
                log.info("Agent 回复:\n{}", result.getResult().getTextContent());
            }
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            log.debug("Agent 正在思考...");
            StringBuilder text = new StringBuilder();
            return next.apply(input)
                    .doOnNext(event -> {
                        if (event instanceof TextBlockDeltaEvent delta
                                && delta.getDelta() != null) {
                            text.append(delta.getDelta());
                        }
                    })
                    .concatWith(Flux.defer(() -> afterReasoning(text.toString())));
        });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnComplete(() -> log.debug("工具执行完成"));
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            StringBuilder generated = new StringBuilder();
            AtomicReference<ChatUsage> usage = new AtomicReference<>();
            AtomicBoolean recorded = new AtomicBoolean();
            Runnable recordOnce = () -> {
                if (recorded.compareAndSet(false, true)) {
                    recordModelCall(input, generated.toString(), usage.get());
                }
            };
            Flux<AgentEvent> modelEvents;
            try {
                modelEvents = next.apply(input);
            } catch (Throwable failure) {
                recordOnce.run();
                return Flux.error(failure);
            }
            return modelEvents
                    .doOnNext(event -> collectModelOutput(event, generated, usage))
                    .doOnComplete(recordOnce)
                    .doOnError(ignored -> recordOnce.run())
                    .doOnCancel(recordOnce);
        });
    }

    private Flux<AgentEvent> afterReasoning(String text) {
        if (text != null && !text.isBlank()) {
            log.debug("  {}", text.lines().limit(3)
                    .reduce("", (a, b) -> a + (a.isEmpty() ? "" : " ") + b));
        }
        if (usageMeter == null || !usageMeter.isBlocked()) return Flux.empty();
        log.warn("已达每日 token 预算，提前中止 Agent 循环");
        return Flux.just(new RequestStopEvent("Daily token budget reached"));
    }

    private void recordModelCall(ModelCallInput input, String generated, ChatUsage usage) {
        if (usageMeter == null) return;
        if (usage != null) {
            usageMeter.record(UsageMeter.Category.AGENT,
                    usage.getInputTokens(), usage.getOutputTokens());
        } else {
            usageMeter.record(UsageMeter.Category.AGENT,
                    estimateModelInputTokens(input), estimateTokens(generated));
        }
    }

    private static void collectModelOutput(
            AgentEvent event, StringBuilder generated, AtomicReference<ChatUsage> usage) {
        if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
            generated.append(delta.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent delta && delta.getDelta() != null) {
            generated.append(delta.getDelta());
        } else if (event instanceof ToolCallDeltaEvent delta) {
            if (delta.getToolCallName() != null) generated.append(delta.getToolCallName());
            if (delta.getDelta() != null) generated.append(delta.getDelta());
        } else if (event instanceof ModelCallEndEvent end && end.getUsage() != null) {
            usage.set(end.getUsage());
        }
    }

    static long estimateModelInputTokens(ModelCallInput input) {
        if (input == null) return 0;
        try {
            return estimateTokens(MAPPER.writeValueAsString(
                    new Object[]{input.messages(), input.tools()}));
        } catch (Exception ignored) {
            StringBuilder fallback = new StringBuilder();
            if (input.messages() != null) {
                input.messages().forEach(message -> fallback.append(message.getTextContent()));
            }
            if (input.tools() != null) {
                input.tools().forEach(tool -> fallback
                        .append(tool.getName())
                        .append(tool.getDescription())
                        .append(tool.getParameters()));
            }
            return estimateTokens(fallback.toString());
        }
    }

    static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + 2) / 3;
    }
}
