package com.selfanalyst.usage;

import com.selfanalyst.agent.PlanMiddleware;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlanMiddlewareBudgetTest {

    @Test
    void emitsStopRequestAfterBlockBudgetIsReached(@TempDir Path tempDir) {
        UsageMeter meter = new UsageMeter(UsageMeter.Mode.BLOCK, 3, 0.8, tempDir);
        try {
            meter.record(UsageMeter.Category.AGENT, 3, 0);
            PlanMiddleware middleware = new PlanMiddleware(meter);
            ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
            TextBlockDeltaEvent delta = new TextBlockDeltaEvent("reply", "block", "123456789");

            List<AgentEvent> events = middleware.onReasoning(
                            null,
                            RuntimeContext.empty(),
                            input,
                            ignored -> Flux.just(delta))
                    .collectList()
                    .block();

            assertEquals(2, events.size());
            assertEquals(delta, events.get(0));
            assertInstanceOf(RequestStopEvent.class, events.get(1));
        } finally {
            meter.flush();
        }
    }
}
