package com.selfanalyst.integration.wiki;

import com.selfanalyst.wiki.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public class WikiVerification {

    private static int passed;
    private static int failed;
    private static final String TZ = "Asia/Shanghai";

    public static void main(String[] args) throws Exception {
        Path tmpDir = Files.createTempDirectory("wiki-verify-");

        try {
            step("CRUD round trip", () -> {
                Path db = tmpDir.resolve("crud.db");
                WikiStore store = new WikiStore(db);
                Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS);
                Instant end = start.plus(1, ChronoUnit.HOURS);

                store.upsert(new WikiEntry("e1", WikiLevel.HOUR, start, end, TZ,
                        WikiStatus.PENDING, null, null, List.of(), null,
                        List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null));

                List<WikiEntry> results = store.query(start.minusSeconds(3600), end.plusSeconds(3600), WikiLevel.HOUR);
                check(results.size() == 1, "should have 1 entry");
                check("e1".equals(results.get(0).id()), "id should match");
                check(results.get(0).status() == WikiStatus.PENDING, "status should be PENDING");

                store.updateStatus("e1", WikiStatus.SUMMARIZED, "summary text", "primary task",
                        List.of(new WikiEntry.TaskSegment("T1", "desc", List.of(), List.of(), "high")),
                        new WikiEntry.WikiMetrics(3600, 0, 5,
                                List.of(new WikiEntry.AppDuration("firefox", 3600)), null),
                        List.of(), "test-model", "v1");

                List<WikiEntry> updated = store.query(start.minusSeconds(3600), end.plusSeconds(3600), WikiLevel.HOUR);
                check(updated.get(0).status() == WikiStatus.SUMMARIZED, "status should be SUMMARIZED");
                check("summary text".equals(updated.get(0).summary()), "summary should match");
                check(updated.get(0).taskSegments().size() == 1, "should have 1 task segment");
                store.close();
                Files.deleteIfExists(db);
            });

            step("unique constraint on level+period+timezone", () -> {
                Path db = tmpDir.resolve("unique.db");
                WikiStore store = new WikiStore(db);
                Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS);
                Instant end = start.plus(1, ChronoUnit.HOURS);

                store.upsert(new WikiEntry("id_a", WikiLevel.HOUR, start, end, TZ,
                        WikiStatus.PENDING, null, null, List.of(), null,
                        List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null));
                store.upsert(new WikiEntry("id_b", WikiLevel.HOUR, start, end, TZ,
                        WikiStatus.SUMMARIZED, "b", null, List.of(), null,
                        List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null));

                List<WikiEntry> results = store.query(start.minusSeconds(1), end.plusSeconds(1), WikiLevel.HOUR);
                check(results.size() == 1, "should be exactly 1 entry after upsert");
                check("id_b".equals(results.get(0).id()), "upsert should replace");
                store.close();
                Files.deleteIfExists(db);
            });

            step("status lifecycle: PENDING → SUMMARIZED → FAILED → SKIPPED", () -> {
                Path db = tmpDir.resolve("status.db");
                WikiStore store = new WikiStore(db);
                Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS);
                Instant end = start.plus(1, ChronoUnit.HOURS);
                String id = "st";

                store.upsert(new WikiEntry(id, WikiLevel.HOUR, start, end, TZ,
                        WikiStatus.PENDING, null, null, List.of(), null,
                        List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null));

                store.updateStatus(id, WikiStatus.SUMMARIZED, "s", "t", List.of(),
                        new WikiEntry.WikiMetrics(0, 0, 0, List.of(), null), List.of(), "m", "v1");
                List<WikiEntry> r1 = store.query(start.minusSeconds(1), end.plusSeconds(1), WikiLevel.HOUR);
                check(r1.get(0).status() == WikiStatus.SUMMARIZED, "should be SUMMARIZED");

                store.markFailed(id, "timeout", Instant.now().plus(60, ChronoUnit.MINUTES));
                List<WikiEntry> r2 = store.query(start.minusSeconds(1), end.plusSeconds(1), WikiLevel.HOUR);
                check(r2.get(0).status() == WikiStatus.FAILED, "should be FAILED");
                check("timeout".equals(r2.get(0).lastError()), "error should match");

                store.markSkipped(id, "skip reason");
                List<WikiEntry> r3 = store.query(start.minusSeconds(1), end.plusSeconds(1), WikiLevel.HOUR);
                check(r3.get(0).status() == WikiStatus.SKIPPED, "should be SKIPPED");
                store.close();
                Files.deleteIfExists(db);
            });

            step("semantic document lifecycle", () -> {
                Path db = tmpDir.resolve("semdoc.db");
                WikiStore store = new WikiStore(db);
                Instant now = Instant.now();
                String eid = "sem_entry";

                store.upsert(new WikiEntry(eid, WikiLevel.DAY,
                        now.truncatedTo(ChronoUnit.DAYS),
                        now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS),
                        TZ, WikiStatus.SUMMARIZED, "s", "t", List.of(),
                        new WikiEntry.WikiMetrics(0, 0, 0, List.of(), null),
                        List.of(), null, null, 0, null, null, now, now, now));

                String docId = "doc_" + UUID.randomUUID().toString().substring(0, 8);
                store.upsertSemanticDoc(new WikiStore.SemanticDoc(docId, eid, "summary",
                        WikiLevel.DAY.name(),
                        now.truncatedTo(ChronoUnit.DAYS).toString(),
                        now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).toString(),
                        "abc", "text-embedding-3-small", 1024,
                        WikiStore.SemanticDocStatus.PENDING, 0, null, null, now, now, null));

                List<WikiStore.SemanticDoc> pending = store.findPendingSemanticDocs(10);
                check(pending.size() == 1, "should have 1 pending doc");

                store.markSemanticDocIndexed(docId);
                List<WikiStore.SemanticDoc> after = store.findSemanticDocsByEntry(eid);
                check(after.get(0).status() == WikiStore.SemanticDocStatus.INDEXED, "should be INDEXED");

                store.markSemanticDocsStale("other-model", 1536);
                List<WikiStore.SemanticDoc> stale = store.findSemanticDocsByEntry(eid);
                check(stale.get(0).status() == WikiStore.SemanticDocStatus.STALE, "should be STALE");
                store.close();
                Files.deleteIfExists(db);
            });

            step("summarize with fake LLM and persist", () -> {
                Path db = tmpDir.resolve("summarize.db");
                WikiStore store = new WikiStore(db);
                String json = "{\"summary\":\"代码开发\",\"primaryTask\":\"集成测试\",\"taskSegments\":[{\"title\":\"T1\",\"summary\":\"desc\",\"evidence\":[],\"apps\":[],\"confidence\":\"high\"}],\"metrics\":{\"activeSeconds\":3600,\"afkSeconds\":0,\"switchCount\":10,\"topApps\":[]}}";
                WikiSummarizer summarizer = new WikiSummarizer(p -> json);

                Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS);
                Instant end = start.plus(1, ChronoUnit.HOURS);
                WikiPeriod period = new WikiPeriod(WikiLevel.HOUR, start, end, TZ);
                WikiFactBuilder.WikiFacts facts = new WikiFactBuilder.WikiFacts(period, 3600, 0, 10,
                        List.of(), List.of(), List.of(), List.of());
                WikiSummarizer.SummaryResult result = summarizer.summarize(facts, Duration.ofSeconds(5));

                check("代码开发".equals(result.summary()), "summary should match");
                check("集成测试".equals(result.primaryTask()), "primaryTask should match");
                check(result.taskSegments().size() == 1, "should have 1 segment");

                store.upsert(new WikiEntry("sum_e", WikiLevel.HOUR, start, end, TZ,
                        WikiStatus.PENDING, null, null, List.of(), null,
                        List.of(), null, null, 0, null, null, Instant.now(), Instant.now(), null));
                store.updateStatus("sum_e", WikiStatus.SUMMARIZED, result.summary(), result.primaryTask(),
                        result.taskSegments(), result.metrics(), List.of(), "fake", "wiki-v1");

                List<WikiEntry> entries = store.query(start.minusSeconds(1), end.plusSeconds(1), WikiLevel.HOUR);
                check(entries.get(0).status() == WikiStatus.SUMMARIZED, "should be SUMMARIZED");
                check("代码开发".equals(entries.get(0).summary()), "persisted summary should match");
                store.close();
                Files.deleteIfExists(db);
            });

        } finally {
            deleteDir(tmpDir);
        }

        System.out.println("\n=== Wiki Verification: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void step(String desc, Step r) {
        try {
            r.run();
            System.out.println("[PASS] " + desc);
            passed++;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + desc + " — " + t.getMessage());
            failed++;
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void deleteDir(Path dir) {
        try {
            Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @FunctionalInterface
    interface Step { void run() throws Exception; }
}
