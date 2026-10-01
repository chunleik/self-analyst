package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DesktopSummaryAsyncTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    @TempDir Path tempDir;

    @Test
    void changingFactsAndConcurrentPollsShareOneBatchAndKeepLiveStatistics() {
        var clock = new MutableClock();
        var facts = new Facts();
        var queue = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        try (var assembler = assembler(facts, clock, queue)) {
            var request = request((prompt, timeout) -> {
                assertEquals(Duration.ofSeconds(60), timeout);
                calls.incrementAndGet();
                return SummaryPromptServiceTest.validResponse();
            });
            var initial = assembler.assemble(request);
            assertEquals(0, calls.get());
            assertEquals(1, queue.size());
            java.util.stream.IntStream.range(0, 20).parallel().forEach(i ->
                    assertNotNull(assembler.assemble(request).get("timeline")));
            assertEquals(1, queue.size(), "simultaneous HTTP-equivalent requests share admission");
            assertEquals("主要在 Chrome", current(initial).get("headline"));
            for (int i = 0; i < 8; i++) {
                clock.advance(Duration.ofSeconds(30));
                facts.current = SummaryPromptServiceTest.facts("new title " + i, false);
                assembler.assemble(request);
            }
            assertEquals(1, queue.size(), "polls must not queue obsolete generations");
            queue.remove().run();
            assertEquals(2, calls.get());
            var published = assembler.assemble(request);
            assertEquals("查看连接超时参数", current(published).get("headline"));
            assertTrue(json(current(published).get("titleFacts")).contains("连接超时参数调试"));
            assertFalse(json(current(published).get("titleFacts")).contains("new title"));
            assertEquals(0, queue.size(), "completion starts a fresh admission interval");
            clock.advance(Duration.ofMinutes(5));
            assembler.assemble(request);
            assertEquals(1, queue.size());
        }
    }

    @Test
    void failureStopsTheBatchAndPersistsExponentialCooldownAcrossRestart() {
        var clock = new MutableClock();
        var facts = new Facts();
        var queue = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        var request = request((prompt, timeout) -> {
            if (calls.incrementAndGet() <= 2) throw new IllegalStateException("network failure");
            return SummaryPromptServiceTest.validResponse();
        });
        try (var first = assembler(facts, clock, queue)) {
            first.assemble(request);
            queue.remove().run();
            assertEquals(1, calls.get(), "a failure must stop remaining current/today/advice calls");
        }
        try (var restarted = assembler(facts, clock, queue)) {
            clock.advance(Duration.ofMinutes(4));
            facts.current = SummaryPromptServiceTest.facts("different title", false);
            restarted.assemble(request);
            assertEquals(0, queue.size());
            clock.advance(Duration.ofMinutes(1));
            restarted.assemble(request);
            queue.remove().run();
            assertEquals(2, calls.get());
            clock.advance(Duration.ofMinutes(9));
            restarted.assemble(request);
            assertEquals(0, queue.size());
            clock.advance(Duration.ofMinutes(1));
            restarted.assemble(request);
            queue.remove().run();
            assertEquals(4, calls.get());
            assertEquals(0, ((Map<?, ?>) restarted.assemble(request).get("enhancement")).get("consecutiveFailures"));
        }
    }

    @Test
    void admittedButUnfinishedBatchStillHasCooldownAfterRestart() {
        var clock = new MutableClock();
        var queue = new ArrayDeque<Runnable>();
        var request = request((prompt, timeout) -> SummaryPromptServiceTest.validResponse());
        try (var first = assembler(new Facts(), clock, queue)) { first.assemble(request); }
        queue.remove().run(); // A cancelled queued batch must not acquire its model session.
        var secondQueue = new ArrayDeque<Runnable>();
        try (var restarted = assembler(new Facts(), clock, secondQueue)) {
            restarted.assemble(request);
            assertTrue(secondQueue.isEmpty());
        }
    }

    @Test
    void oldLanguageBatchCannotOverwriteNewLanguageLocalFacts() {
        var clock = new MutableClock();
        var queue = new ArrayDeque<Runnable>();
        try (var assembler = assembler(new Facts(), clock, queue)) {
            var client = (SummaryPromptService.SummaryTextClient) (prompt, timeout) -> SummaryPromptServiceTest.validResponse();
            assembler.assemble(request(client));
            var english = new DesktopSummaryAssembler.Request(true, 2, Lang.english(), client);
            assembler.assemble(english);
            queue.remove().run();
            var result = assembler.assemble(english);
            assertEquals("主要在 Chrome", current(result).get("headline"));
            assertEquals(0, queue.size(), "a language switch cannot bypass admission");
            assertTrue(((List<?>) current(result).get("taskSegments")).isEmpty());
        }
    }

    @Test
    void unwritableAdmissionDoesNotSendModelRequests() throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        java.nio.file.Files.writeString(file, "occupied");
        var queue = new ArrayDeque<Runnable>();
        try (var assembler = new DesktopSummaryAssembler(new Facts(), new BehaviorAdviceService(),
                new SummaryPromptService(), new SummarySnapshotStore(file), null, new MutableClock(), queue::add)) {
            var result = assembler.assemble(request((prompt, timeout) -> { fail("no persisted admission"); return ""; }));
            assertTrue(queue.isEmpty());
            assertNotNull(result.get("current"));
        }
    }

    @Test
    void slowModelDoesNotBlockResponsesAndCloseReleasesItsOwnLease() throws Exception {
        var started = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var closed = new AtomicInteger();
        var calls = new AtomicInteger();
        var facts = new Facts();
        var clock = new MutableClock();
        Supplier<SummaryPromptService.SummaryTextSession> sessions = () -> new SummaryPromptService.SummaryTextSession() {
            public String complete(String prompt, Duration timeout) {
                calls.incrementAndGet();
                started.countDown();
                try { released.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return SummaryPromptServiceTest.validResponse();
            }
            public void close() { closed.incrementAndGet(); }
        };
        var assembler = new DesktopSummaryAssembler(facts, new BehaviorAdviceService(), new SummaryPromptService(),
                new SummarySnapshotStore(tempDir), null, clock);
        try {
            var request = DesktopSummaryAssembler.Request.background(true, 2, Lang.chinese(), sessions);
            var result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assembler.assemble(request));
            assertNotNull(result.get("timeline"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 10; i++) {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assembler.assemble(request));
            }
            assertEquals(1, calls.get());
        } finally {
            assertTimeoutPreemptively(Duration.ofSeconds(3), assembler::close);
            released.countDown();
        }
        assertEquals(1, closed.get());
        assembler.assemble(DesktopSummaryAssembler.Request.background(true, 2, Lang.chinese(), sessions));
        assertEquals(1, calls.get(), "closed assemblers cannot acquire another lease");
    }

    private DesktopSummaryAssembler assembler(Facts facts, Clock clock, ArrayDeque<Runnable> queue) {
        return new DesktopSummaryAssembler(facts, new BehaviorAdviceService(), new SummaryPromptService(),
                new SummarySnapshotStore(tempDir), null, clock, queue::add);
    }
    private static DesktopSummaryAssembler.Request request(SummaryPromptService.SummaryTextClient client) {
        return new DesktopSummaryAssembler.Request(true, 2, Lang.chinese(), client);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> current(Map<String, Object> response) { return (Map<String, Object>) response.get("current"); }
    private static String json(Object value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static final class Facts implements SummaryFactSource {
        SummaryService.LocalFacts current = SummaryPromptServiceTest.facts("连接超时参数调试", false);
        public SummaryService.LocalFacts currentStatus(Instant now) { return current; }
        public SummaryService.LocalFacts currentWindowFacts(Instant start, Instant end, String label) { return current; }
        public SummaryService.LocalFacts factsFor(Instant start, Instant end, String label) {
            return new SummaryService.LocalFacts(label, List.of(), List.of(), "0秒", "0秒", 0, "");
        }
        public SummaryService.BehaviorData behaviorData() { return new SummaryService.BehaviorData(0,0,0,0,0,0,0,List.of()); }
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T06:00:00Z");
        public ZoneId getZone() { return ZONE; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}
