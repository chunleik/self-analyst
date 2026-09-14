package com.selfanalyst.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.memory.LongTermMemoryService;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PendingMemoryReview implements AutoCloseable {
    private final LongTermMemoryService memory;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "pending-memory-review");
        thread.setDaemon(true);
        return thread;
    });
    private int failures;
    private final com.selfanalyst.i18n.Lang lang;

    public PendingMemoryReview(LongTermMemoryService memory) {
        this(memory, com.selfanalyst.i18n.Lang.chinese());
    }

    public PendingMemoryReview(LongTermMemoryService memory, com.selfanalyst.i18n.Lang lang) {
        this.memory = memory;
        this.lang = lang;
    }

    public void start(SummaryPromptService.SummaryTextClient client) {
        worker.schedule(() -> run(client), 1, TimeUnit.SECONDS);
    }

    private void run(SummaryPromptService.SummaryTextClient client) {
        boolean success = reviewBatch(client);
        failures = success ? 0 : Math.min(failures + 1, 6);
        if (!worker.isShutdown()) worker.schedule(() -> run(client),
                success ? 30 : Math.min(1800, 30L << failures), TimeUnit.SECONDS);
    }

    public boolean reviewBatch(SummaryPromptService.SummaryTextClient client) {
        boolean success = true;
        for (var item : memory.list("pending", null, null, null).stream().limit(8).toList()) {
            try {
                if (LongTermMemoryService.containsForbiddenContent(item.content())
                        || LongTermMemoryService.containsForbiddenContent(item.evidence()) || item.sensitive()) {
                    memory.resolvePending(item, false, 0);
                    continue;
                }
                if (client == null) { success = false; continue; }
                String prompt = com.selfanalyst.i18n.Messages.text(lang, "memory.review")
                        + "\ncontent: " + bounded(item.content()) + "\nevidence: " + bounded(item.evidence());
                var result = new ObjectMapper().readTree(client.complete(prompt, Duration.ofSeconds(20)));
                if (result == null || !result.path("durable").isBoolean() || !result.path("supported").isBoolean()
                        || !result.path("sensitive").isBoolean() || !result.path("confidence").isInt()
                        || result.path("confidence").asInt() < 1 || result.path("confidence").asInt() > 10) {
                    success = false;
                    continue;
                }
                int confidence = result.path("confidence").asInt();
                boolean keep = result.path("durable").asBoolean() && result.path("supported").asBoolean()
                        && !result.path("sensitive").asBoolean() && confidence >= 8
                        && item.evidence() != null && !item.evidence().isBlank();
                memory.resolvePending(item, keep, confidence);
            } catch (Exception failure) {
                success = false;
            }
        }
        return success;
    }

    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(2000, value.length()));
    }

    @Override public void close() {
        worker.shutdownNow();
    }
}
