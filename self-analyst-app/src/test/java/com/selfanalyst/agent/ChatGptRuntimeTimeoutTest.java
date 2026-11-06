package com.selfanalyst.agent;

import com.selfanalyst.chatgpt.ChatGptResponsesModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ChatGptRuntimeTimeoutTest {
    @Test void plainModelHonoursLongCallerTimeoutsWhileChatKeeps45Seconds() {
        try (var chat = SelfAnalystAgent.chatGptModel("model", () -> "token", false);
             var plain = SelfAnalystAgent.chatGptModel("model", () -> "token", true)) {
            assertEquals(Duration.ofSeconds(45), chat.timeout());
            // SummaryRepairChatService allows 120s; the plain model must not end the stream first.
            assertTrue(plain.timeout().compareTo(Duration.ofSeconds(120)) > 0);
            assertEquals(ChatGptResponsesModel.PLAIN_TIMEOUT, plain.timeout());
        }
    }
}
