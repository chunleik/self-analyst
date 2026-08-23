package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import com.selfanalyst.usage.UsageMeter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageMeteredModelTest {

    @Test
    @SuppressWarnings("unchecked")
    void recordsCompactionModelUsageInSummaryCategory(@TempDir Path tempDir) {
        UsageMeter meter = new UsageMeter(Config.testDefaults(tempDir), tempDir);
        Model delegate = new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("summary").build()))
                        .usage(new ChatUsage(20, 4, 0.01))
                        .build());
            }

            @Override
            public String getModelName() {
                return "metered-test";
            }
        };
        try {
            new UsageMeteredModel(delegate, meter, UsageMeter.Category.SUMMARY)
                    .stream(List.of(Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("compact this")
                            .build()), List.of(), null)
                    .collectList()
                    .block();

            Map<String, Object> categories =
                    (Map<String, Object>) meter.snapshot().get("categories");
            Map<String, Object> summary = (Map<String, Object>) categories.get("summary");
            assertEquals(20L, ((Number) summary.get("inputTokens")).longValue());
            assertEquals(4L, ((Number) summary.get("outputTokens")).longValue());
            assertEquals(1L, ((Number) summary.get("calls")).longValue());
        } finally {
            meter.flush();
        }
    }
}
