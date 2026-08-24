package com.selfanalyst.agent;

import com.selfanalyst.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAnalystAgentConcurrencyTest {

    @Test
    void overlappingChatFailsFastAndGateReleasesOnTermination(@TempDir Path tempDir)
            throws Exception {
        try (SelfAnalystAgent agent = new SelfAnalystAgent(Config.testDefaults(tempDir))) {
            Sinks.One<String> gate = Sinks.one();
            CompletableFuture<String> first = agent.runExclusiveChat(gate::asMono).toFuture();

            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> agent.runExclusiveChat(() -> Mono.just("second")).block());
            assertTrue(hasMessage(error, "still running"));
            AtomicBoolean deletionIntentWritten = new AtomicBoolean();
            RuntimeException busyDelete = assertThrows(RuntimeException.class,
                    () -> agent.deleteChatSessionStateWithIntent(
                            "a".repeat(32),
                            () -> deletionIntentWritten.set(true),
                            () -> "must-not-delete"));
            assertTrue(hasMessage(busyDelete, "still running"));
            assertFalse(deletionIntentWritten.get());

            gate.tryEmitValue("first");
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("third", agent.runExclusiveChat(() -> Mono.just("third")).block());

            assertEquals("chained", agent.runExclusiveChat(() -> Mono.just("outer"))
                    .flatMap(ignored -> agent.runExclusiveChat(() -> Mono.just("chained")))
                    .block());

            assertThrows(IllegalArgumentException.class,
                    () -> agent.runExclusiveChat(
                            () -> Mono.error(new IllegalArgumentException("boom"))).block());
            assertEquals("after-error",
                    agent.runExclusiveChat(() -> Mono.just("after-error")).block());

            Sinks.One<String> cancelledGate = Sinks.one();
            Disposable cancelled = agent.runExclusiveChat(cancelledGate::asMono).subscribe();
            cancelled.dispose();
            assertEquals("after-cancel",
                    agent.runExclusiveChat(() -> Mono.just("after-cancel")).block());

            Disposable cancelledStream = agent.runExclusiveChatStream(Flux::never).subscribe();
            RuntimeException busyDuringStream = assertThrows(RuntimeException.class,
                    () -> agent.runExclusiveChat(() -> Mono.just("must-not-run")).block());
            assertTrue(hasMessage(busyDuringStream, "still running"));
            cancelledStream.dispose();
            assertEquals("after-stream-cancel",
                    agent.runExclusiveChat(() -> Mono.just("after-stream-cancel")).block());

            String activeSession = "a".repeat(32);
            String activeMessage = "1".repeat(12);
            Disposable desktopStream = agent.runExclusiveDesktopChatStream(
                    activeSession, activeMessage, Flux::never).subscribe();
            assertFalse(agent.cancelChat(activeSession, "2".repeat(12)),
                    "a stop from another turn must not arm interruption");
            assertTrue(agent.cancelChat(activeSession, activeMessage));
            assertFalse(agent.cancelChat(activeSession, activeMessage),
                    "cancellation must be idempotent");
            desktopStream.dispose();
            assertFalse(agent.cancelChat(activeSession, activeMessage),
                    "a late stop must not affect the next turn");
            Disposable nextDesktopStream = agent.runExclusiveDesktopChatStream(
                    activeSession, "3".repeat(12), Flux::never).subscribe();
            assertFalse(agent.cancelChat(activeSession, activeMessage),
                    "the previous message id must not cancel the next turn");
            nextDesktopStream.dispose();

            Mono<String> reusable = agent.runExclusiveChat(() -> Mono.just("reused"));
            assertEquals("reused", reusable.block());
            assertEquals("reused", reusable.block());

            CountDownLatch transcriptDeleteEntered = new CountDownLatch(1);
            CountDownLatch finishTranscriptDelete = new CountDownLatch(1);
            CompletableFuture<String> deletion = CompletableFuture.supplyAsync(
                    () -> agent.deleteChatSessionStateThen("a".repeat(32), () -> {
                        transcriptDeleteEntered.countDown();
                        try {
                            assertTrue(finishTranscriptDelete.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return "transcript-deleted";
                    }));
            assertTrue(transcriptDeleteEntered.await(2, TimeUnit.SECONDS));
            RuntimeException deleteOverlap = assertThrows(RuntimeException.class,
                    () -> agent.runExclusiveChat(() -> Mono.just("must-not-run")).block());
            assertTrue(hasMessage(deleteOverlap, "still running"));
            finishTranscriptDelete.countDown();
            assertEquals("transcript-deleted", deletion.get(2, TimeUnit.SECONDS));
            assertEquals("after-delete",
                    agent.runExclusiveChat(() -> Mono.just("after-delete")).block());
        }
    }

    private static boolean hasMessage(Throwable error, String text) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
