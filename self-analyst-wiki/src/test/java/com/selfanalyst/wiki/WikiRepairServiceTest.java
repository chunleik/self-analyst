package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WikiRepairServiceTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2026-10-10T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(START.plusSeconds(7200), ZoneOffset.UTC);

    static WikiEntry entry(String id) {
        return new WikiEntry(id, WikiLevel.HOUR, START, START.plusSeconds(3600), "UTC", WikiStatus.FAILED,
                null, null, List.of(), new WikiEntry.WikiMetrics(100, 20, 2, List.of(new WikiEntry.AppDuration("IDE", 100)),
                Map.of("generationProgress", Map.of("state", "quality", "calls", 3, "tokens", 900,
                        "configurationStamp", "private-config"))), List.of(), "fake", "v1", 2, START.plusSeconds(9999),
                "WIKI_EVIDENCE_UNSUPPORTED_CLAIM:taskSegments.summary", START, START, null);
    }

    static WikiFactBuilder.WikiFacts facts(WikiEntry entry) {
        var fact = new WikiTitleSampler.Fact("f1", "window", "IDE", "数据库同步设计", "observed", 100.0, 1, List.of(), 0);
        var selection = new WikiTitleSampler.Selection(List.of(fact), "{}", Map.of("candidateFacts", 1));
        return new WikiFactBuilder.WikiFacts(new WikiPeriod(entry.level(), entry.periodStart(), entry.periodEnd(), entry.timezone()),
                100, 20, 2, List.of(new WikiEntry.AppDuration("IDE", 100)), List.of(), List.of(), List.of(),
                "facts-v1", "projector-v1", Map.of(), Map.of(), selection);
    }

    static WikiRepairService service(WikiStore store) {
        return new WikiRepairService(store, WikiRepairServiceTest::facts, WikiPrivacyPolicy::none, () -> "configuration-v1", CLOCK);
    }

    static WikiRepairService.Candidate observed() {
        return candidate("查看数据库同步设计。", "observed", List.of("f1"));
    }

    static WikiRepairService.Candidate candidate(String summary, String claim, List<String> refs) {
        return new WikiRepairService.Candidate(summary, "数据库同步", List.of(new WikiEntry.TaskSegment("数据库同步", summary,
                List.of("untrusted evidence"), List.of("forged-app"), "high", refs, claim)));
    }

    static WikiRepairService.View edited(WikiRepairService service, String session, WikiRepairService.Candidate candidate) {
        var opened = service.open(session, "target");
        return service.edit(session, opened.draft().id(), opened.draft().draftRevision(), candidate);
    }

    static WikiRepairService.View ready(WikiRepairService service) {
        var edited = edited(service, "session", observed());
        var checked = service.validate("session", edited.draft().id(), edited.draft().draftRevision());
        assertEquals("valid", checked.draft().state(), checked.draft().issues().toString());
        return checked;
    }

    @Test void localOnlyOpenEditValidateCommitRestartsAndPreservesMetricsAndHistory() throws Exception {
        Path path = directory.resolve("wiki.db"); String repairId;
        try (WikiStore store = new WikiStore(path)) {
            store.upsert(entry("target"));
            var service = service(store);
            var opened = service.open("session", "target");
            repairId = opened.draft().id();
            assertEquals("unvalidated", opened.draft().state()); assertFalse(opened.failureTextAvailable());
            assertTrue(opened.message().contains("未保存")); assertNull(opened.currentEntry());
            assertEquals(WikiStatus.FAILED, store.versionedEntry("target").entry().status());
            assertEquals(repairId, service.open("session", "target").draft().id());
            var view = ready(service);
            assertEquals(List.of("IDE"), view.draft().candidate().taskSegments().getFirst().apps());
            var committed = service.commit("session", repairId, view.draft().draftRevision(), "commit-1");
            assertEquals("committed", committed.draft().state());
            assertEquals(1, committed.draft().committedRevision());
            var saved = store.versionedEntry("target").entry();
            assertEquals(WikiStatus.SUMMARIZED, saved.status()); assertNull(saved.lastError()); assertNull(saved.nextRetryAt());
            assertEquals(100, saved.metrics().activeSeconds()); assertEquals(20, saved.metrics().afkSeconds());
            assertEquals(2, saved.metrics().switchCount()); assertEquals(2, saved.retryCount());
            assertEquals(3, WikiGenerationProgress.from(saved).get("calls"));
            assertEquals("user-repair", saved.model());
            assertFalse(WikiGenerationProgress.raw(committed.currentEntry()).containsKey("configurationStamp"));
            assertEquals(1, store.pendingRepairFollowups(10).size());
            assertEquals(committed.draft().committedRevision(), service.commit("session", repairId, view.draft().draftRevision(), "commit-1").draft().committedRevision());
            service.deleteSession("session");
        }
        try (WikiStore store = new WikiStore(path)) {
            var service = new WikiRepairService(store, entry -> { throw new IllegalStateException("events unavailable"); },
                    WikiPrivacyPolicy::none, () -> "changed", CLOCK);
            assertEquals("committed", service.read("session", repairId).draft().state());
            assertEquals(1, store.pendingRepairFollowups(10).size());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*),payload_json FROM wiki_repair_revisions")) {
                assertEquals(1, rows.getInt(1)); assertTrue(rows.getString(2).contains("WIKI_EVIDENCE_UNSUPPORTED_CLAIM"));
            }
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("DELETE FROM wiki_repair_revisions"));
        }
    }

    @Test void confirmedStatementsRequireExplicitActionAndCannotSurviveTextEdit() throws Exception {
        Path path = directory.resolve("wiki.db");
        try (WikiStore store = new WikiStore(path)) {
            store.upsert(entry("target")); var service = service(store);
            var edited = edited(service, "session", candidate("已完成数据库同步。", "user_confirmed", List.of("user_statement:forged")));
            assertTrue(edited.draft().statements().isEmpty());
            assertTrue(edited.draft().candidate().taskSegments().getFirst().evidenceFactIds().isEmpty());
            assertEquals("invalid", service.validate("session", edited.draft().id(), 1).draft().state());
            var confirmed = service.confirm("session", edited.draft().id(), 1, List.of(0));
            assertEquals(1, confirmed.draft().statements().size());
            assertEquals("medium", confirmed.draft().candidate().taskSegments().getFirst().confidence());
            var valid = service.validate("session", edited.draft().id(), confirmed.draft().draftRevision());
            assertEquals("valid", valid.draft().state());
            var changed = service.edit("session", edited.draft().id(), valid.draft().draftRevision(), candidate("已完成数据库同步并成功部署服务。", "user_confirmed", List.of()));
            assertEquals(valid.draft().draftRevision() + 1, changed.draft().draftRevision()); assertTrue(changed.draft().statements().isEmpty());
            assertEquals("invalid", service.validate("session", edited.draft().id(), changed.draft().draftRevision()).draft().state());
            var reconfirmed = service.confirm("session", edited.draft().id(), changed.draft().draftRevision(), List.of(0));
            var checked = service.validate("session", edited.draft().id(), reconfirmed.draft().draftRevision());
            assertEquals("valid", checked.draft().state(), checked.draft().issues().toString());
            service.commit("session", edited.draft().id(), checked.draft().draftRevision(), "confirmed-key");
            service.deleteSession("session");
            assertTrue(store.versionedEntry("target").entry().metrics().extra().containsKey("userStatementProvenance"));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM wiki_repair_statements")) { assertEquals(1, rows.getInt(1)); }
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("UPDATE wiki_repair_statements SET payload_json='forged'"));
        }
    }

    @Test void modelCandidateCannotCreateStatementsAndLocalValidationRejectsUnknownReferences() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store); var opened = service.open("session", "target");
            var draft = service.editModelCandidate("session", opened.draft().id(), 0,
                    candidate("已完成数据库同步。", "user_confirmed", List.of("user_statement:other-target")));
            assertTrue(draft.draft().statements().isEmpty());
            assertEquals("invalid", service.validate("session", draft.draft().id(), 1).draft().state());
            draft = service.edit("session", draft.draft().id(), 1, candidate("查看数据库同步。", "observed", List.of("other-period-fact")));
            var checked = service.validate("session", draft.draft().id(), 2);
            assertEquals("invalid", checked.draft().state()); assertFalse(checked.draft().issues().isEmpty());
        }
    }

    @Test void emptyAndActivityPlaceholderCannotBypassValidation() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store); var opened = service.open("session", "target");
            assertEquals("invalid", service.validate("session", opened.draft().id(), 0).draft().state());
            var edited = service.edit("session", opened.draft().id(), 0, candidate("无活跃活动", "observed", List.of("f1")));
            assertEquals("invalid", service.validate("session", edited.draft().id(), 1).draft().state());
            assertThrows(WikiRepairException.class, () -> service.commit("session", edited.draft().id(), 1, "key"));
        }
    }

    @Test void sessionOwnershipDraftRevisionAndUncommittedDeletionAreEnforcedAcrossRestart() {
        Path path = directory.resolve("wiki.db"); String id;
        try (WikiStore store = new WikiStore(path)) {
            store.upsert(entry("target")); var view = edited(service(store), "session", observed()); id = view.draft().id();
            var service = service(store);
            assertThrows(WikiRepairException.class, () -> service.read("other", id));
            assertThrows(WikiRepairException.class, () -> service.edit("session", id, 0, observed()));
        }
        try (WikiStore store = new WikiStore(path)) {
            var service = service(store); assertEquals(observed().summary(), service.bySession("session").draft().candidate().summary());
            service.deleteSession("session"); assertNull(service.bySession("session"));
            assertEquals(WikiStatus.FAILED, store.versionedEntry("target").entry().status());
        }
    }

    @Test void backgroundFirstCausesConflictAndPreservesDraft() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store); var view = ready(service);
            store.updateStatus("target", WikiStatus.SUMMARIZED, "后台摘要", "后台", List.of(), entry("target").metrics(), List.of(), "fake", "p1");
            var error = assertThrows(WikiRepairException.class, () -> service.commit("session", view.draft().id(), view.draft().draftRevision(), "key"));
            assertEquals("WIKI_REPAIR_TARGET_CONFLICT", error.code());
            assertEquals(observed().summary(), service.read("session", view.draft().id()).draft().candidate().summary());
            assertEquals("后台摘要", store.versionedEntry("target").entry().summary());
        }
    }

    @Test void repairFirstRejectsOldSuccessAndFailureWritesAndTimestampOnlyRetry() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var before = store.versionedEntry("target"); var service = service(store); var view = ready(service);
            service.commit("session", view.draft().id(), view.draft().draftRevision(), "key");
            assertFalse(store.updateStatusIfRevision("target", before.revision(), WikiStatus.SUMMARIZED, "stale", "stale", List.of(),
                    before.entry().metrics(), List.of(), "model", "v1", null, null, Map.of()));
            assertFalse(store.markGenerationFailureIfRevision(before.entry(), before.revision(), null,
                    Map.of("state", "quality", "reason", "WIKI_EVIDENCE_STRUCTURE"), START, true));
            assertFalse(store.markSkippedIfRevision("target", before.revision(), "stale"));
            assertEquals(WikiStatus.SUMMARIZED, store.versionedEntry("target").entry().status());
        }
    }

    @Test void anyRetryOrConfigurationOrFactChangeInvalidatesValidation() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var config = new AtomicReference<>("v1");
            var service = new WikiRepairService(store, WikiRepairServiceTest::facts, WikiPrivacyPolicy::none, config::get, CLOCK);
            var view = ready(service); config.set("v2");
            assertEquals("WIKI_REPAIR_INPUT_CONFLICT", assertThrows(WikiRepairException.class,
                    () -> service.commit("session", view.draft().id(), view.draft().draftRevision(), "key")).code());
            config.set("v1"); store.markFailed("target", "WIKI_EVIDENCE_STRUCTURE", START.plusSeconds(9000));
            assertEquals("WIKI_REPAIR_TARGET_CONFLICT", assertThrows(WikiRepairException.class,
                    () -> service.commit("session", view.draft().id(), view.draft().draftRevision(), "key")).code());
        }
    }

    @Test void auditInsertionFailureRollsBackEntryStatementsDraftAndFollowup() throws Exception {
        Path path = directory.resolve("wiki.db");
        try (WikiStore store = new WikiStore(path)) {
            store.upsert(entry("target")); var service = service(store);
            var draft = edited(service, "session", candidate("已完成数据库同步。", "user_confirmed", List.of()));
            var confirmed = service.confirm("session", draft.draft().id(), 1, List.of(0));
            var checked = service.validate("session", draft.draft().id(), confirmed.draft().draftRevision());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON wiki_repair_revisions BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
            }
            assertThrows(IllegalStateException.class, () -> service.commit("session", draft.draft().id(), checked.draft().draftRevision(), "key"));
            assertEquals(WikiStatus.FAILED, store.versionedEntry("target").entry().status()); assertEquals(0, store.revision("target"));
            assertEquals("valid", service.read("session", draft.draft().id()).draft().state()); assertTrue(store.pendingRepairFollowups(10).isEmpty());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM wiki_repair_statements")) { assertEquals(0, rows.getInt(1)); }
                statement.execute("DROP TRIGGER fail_audit");
            }
            assertEquals("committed", service.commit("session", draft.draft().id(), checked.draft().draftRevision(), "key").draft().state());
        }
    }

    @Test void targetMustBeCurrentEndedQualityFailedHour() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            var service = service(store);
            assertThrows(WikiRepairException.class, () -> service.open("session", "missing"));
            WikiEntry base = entry("target"); store.upsert(base);
            assertThrows(WikiRepairException.class, () -> service.open("session", "target", 99));
            assertFalse(WikiRepairService.repairable(base, START));
            assertTrue(WikiRepairService.repairable(base, CLOCK.instant()));
            store.markFailed("target", "WIKI_TRANSPORT_ERROR", START);
            // Explicit current progress remains quality; overwrite it through the authorized generation transition.
            var snapshot = store.versionedEntry("target");
            store.markGenerationFailureIfRevision(snapshot.entry(), snapshot.revision(), null, Map.of("state", "transport", "reason", "WIKI_TRANSPORT_ERROR"), START, true);
            assertThrows(WikiRepairException.class, () -> service.open("session", "target"));
            store.updateStatus("target", WikiStatus.SUMMARIZED, "done", "done", List.of(), base.metrics(), List.of(), "model", "p");
            assertThrows(WikiRepairException.class, () -> service.open("session", "target"));
        }
    }

    @Test void normalizedUnicodeLengthsAndPrivacyAreEnforcedBeforePersistence() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store); var opened = service.open("session", "target");
            var normalized = service.edit("session", opened.draft().id(), 0, candidate("查看  １０.０.０.１ 数据库。", "observed", List.of("f1")));
            // NFKC occurs before privacy redaction; fullwidth secrets cannot survive normalization.
            assertFalse(normalized.draft().candidate().summary().contains("10.0.0.1"));
            assertThrows(WikiRepairException.class, () -> service.edit("session", opened.draft().id(), 1,
                    new WikiRepairService.Candidate("😀".repeat(1201), "topic", List.of())));
        }
    }
    @Test void retryConflictCanRebaseWithoutLosingLocalTextAndRequiresFreshConfirmation() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store);
            var view = edited(service, "session", candidate("已完成数据库同步。", "user_confirmed", List.of()));
            var confirmed = service.confirm("session", view.draft().id(), view.draft().draftRevision(), List.of(0));
            var checked = service.validate("session", view.draft().id(), confirmed.draft().draftRevision());
            store.markFailed("target", "WIKI_EVIDENCE_UNSUPPORTED_CLAIM:summary", START.plusSeconds(9000));
            assertThrows(WikiRepairException.class, () -> service.commit("session", view.draft().id(), checked.draft().draftRevision(), "key"));
            var local = candidate("已完成数据库同步并成功修复错误。", "user_confirmed", List.of());
            var refreshed = service.rebase("session", view.draft().id(), checked.draft().draftRevision(), local);
            assertEquals(local.summary(), refreshed.draft().candidate().summary());
            assertEquals(checked.draft().draftRevision() + 1, refreshed.draft().draftRevision()); assertEquals(1, refreshed.draft().targetRevision());
            assertTrue(refreshed.draft().statements().isEmpty());
            assertEquals("invalid", service.validate("session", view.draft().id(), refreshed.draft().draftRevision()).draft().state());
            var reconfirmed = service.confirm("session", view.draft().id(), refreshed.draft().draftRevision(), List.of(0));
            var rechecked = service.validate("session", view.draft().id(), reconfirmed.draft().draftRevision());
            assertEquals("valid", rechecked.draft().state());
            assertEquals("committed", service.commit("session", view.draft().id(), rechecked.draft().draftRevision(), "key").draft().state());
        }
    }

    @Test void rebaseRejectsCompletedTargetAndPreservesOldCandidate() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store); var view = ready(service);
            store.markSkipped("target", "empty");
            assertThrows(WikiRepairException.class, () -> service.rebase("session", view.draft().id(), view.draft().draftRevision(), observed()));
            assertEquals(view.draft().candidate(), service.read("session", view.draft().id()).draft().candidate());
        }
    }

    @Test void missingLegacyMetricsNormalizeToZeroAndCommitDoesNotInventStatistics() throws Exception {
        Path path = directory.resolve("wiki.db");
        try (WikiStore store = new WikiStore(path)) {
            store.upsert(entry("target"));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement()) {
                statement.execute("UPDATE wiki_entries SET metrics_json=NULL WHERE id='target'");
            }
            var service = service(store); var view = ready(service);
            assertEquals("committed", service.commit("session", view.draft().id(), view.draft().draftRevision(), "key").draft().state());
            var metrics = store.versionedEntry("target").entry().metrics();
            assertEquals(0, metrics.activeSeconds()); assertEquals(0, metrics.afkSeconds()); assertTrue(metrics.topApps().isEmpty());
        }
    }

    @Test void anotherReadersConfirmationInvalidatesOldValidationAndCommitPreview() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var first = service(store); var second = service(store);
            var oldPreview = ready(first);
            var confirmed = second.confirm("session", oldPreview.draft().id(), oldPreview.draft().draftRevision(), List.of(0));
            assertEquals(oldPreview.draft().draftRevision() + 1, confirmed.draft().draftRevision());
            var latest = second.validate("session", confirmed.draft().id(), confirmed.draft().draftRevision());
            assertEquals("valid", latest.draft().state());
            assertEquals("WIKI_REPAIR_DRAFT_CONFLICT", assertThrows(WikiRepairException.class,
                    () -> first.validate("session", oldPreview.draft().id(), oldPreview.draft().draftRevision())).code());
            assertEquals("WIKI_REPAIR_DRAFT_CONFLICT", assertThrows(WikiRepairException.class,
                    () -> first.commit("session", oldPreview.draft().id(), oldPreview.draft().draftRevision(), "stale-key")).code());
            assertEquals("WIKI_REPAIR_DRAFT_CONFLICT", assertThrows(WikiRepairException.class,
                    () -> first.confirm("session", oldPreview.draft().id(), oldPreview.draft().draftRevision(), List.of(0))).code());
            assertEquals(WikiStatus.FAILED, store.versionedEntry("target").entry().status());
            assertEquals("committed", second.commit("session", latest.draft().id(), latest.draft().draftRevision(), "latest-key").draft().state());
        }
    }

    @Test void derivedValidationPreviewHasOwnRevisionAndRepeatedUnchangedValidationIsStable() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store);
            var edited = edited(service, "session", observed());
            var checked = service.validate("session", edited.draft().id(), edited.draft().draftRevision());
            assertTrue(checked.draft().draftRevision() > edited.draft().draftRevision());
            assertFalse(checked.draft().candidate().equals(edited.draft().candidate()));
            assertEquals("WIKI_REPAIR_DRAFT_CONFLICT", assertThrows(WikiRepairException.class,
                    () -> service.commit("session", edited.draft().id(), edited.draft().draftRevision(), "stale-key")).code());
            var again = service.validate("session", checked.draft().id(), checked.draft().draftRevision());
            assertEquals(checked.draft().draftRevision(), again.draft().draftRevision());
            assertEquals(checked.draft().candidate(), again.draft().candidate());
        }
    }

    @Test void additionalConfirmationRetainsExactPriorStatementActionAndTimeWithNewVersionBinding() {
        try (WikiStore store = new WikiStore(directory.resolve("wiki.db"))) {
            store.upsert(entry("target")); var service = service(store);
            var one = candidate("已完成数据库同步。", "user_confirmed", List.of()).taskSegments().getFirst();
            var two = new WikiEntry.TaskSegment("文档更新", "已完成文档更新。", List.of(), List.of(), "medium", List.of(), "user_confirmed");
            var draft = edited(service, "session", new WikiRepairService.Candidate("待确认", "数据库同步", List.of(one, two)));
            var first = service.confirm("session", draft.draft().id(), draft.draft().draftRevision(), List.of(0));
            var original = first.draft().statements().getFirst();
            var second = service.confirm("session", draft.draft().id(), first.draft().draftRevision(), List.of(1));
            var retained = second.draft().statements().stream().filter(statement -> statement.id().equals(original.id())).findFirst().orElseThrow();
            assertEquals(original.text(), retained.text()); assertEquals(original.sourceId(), retained.sourceId());
            assertEquals(original.confirmedAt(), retained.confirmedAt()); assertEquals(second.draft().draftRevision(), retained.draftRevision());
            var checked = service.validate("session", draft.draft().id(), second.draft().draftRevision());
            assertEquals("valid", checked.draft().state(), checked.draft().issues().toString());
        }
    }

}
