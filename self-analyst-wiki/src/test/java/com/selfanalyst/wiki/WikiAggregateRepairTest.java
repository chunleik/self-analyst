package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WikiAggregateRepairTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2026-10-10T04:00:00Z");
    static final Instant END = START.plusSeconds(86400);
    static final Clock CLOCK = Clock.fixed(END.plusSeconds(3600), ZoneOffset.UTC);

    static WikiEntry entry(String id, WikiLevel level, Instant start, Instant end, WikiStatus status) {
        return new WikiEntry(id, level, start, end, "UTC", status,
                status == WikiStatus.SUMMARIZED ? "查看数据库同步设计。" : null, "数据库同步",
                status == WikiStatus.SUMMARIZED ? WikiRepairServiceTest.observed().taskSegments() : List.of(),
                new WikiEntry.WikiMetrics(100, 20, 2, List.of(new WikiEntry.AppDuration("IDE", 100)), Map.of()),
                List.of(), "fake", "test", 0, null, null, START, START, null);
    }

    static void children(WikiStore store, WikiEntry parent) {
        WikiLevel level = parent.level() == WikiLevel.DAY ? WikiLevel.HALF_DAY : WikiLevel.HOUR;
        int i = 0;
        for (var period : WikiPeriodFactory.generate(parent.periodStart(), parent.periodEnd(), level, ZoneOffset.UTC)) {
            store.upsert(entry("child-" + i++, level, period.start(), period.end(), WikiStatus.SUMMARIZED));
        }
    }

    static WikiRepairService service(WikiStore store) {
        // A null EventStore ensures aggregate repairs cannot fall back to raw event reads.
        var builder = new WikiFactBuilder(null, 24000);
        return new WikiRepairService(store, value -> WikiRepairService.buildRepairFacts(store, builder, value),
                WikiPrivacyPolicy::none, () -> "test", CLOCK);
    }

    static WikiRepairService.View valid(WikiRepairService service) {
        var opened = service.open("session", "parent");
        var candidate = WikiRepairServiceTest.candidate("查看数据库同步设计。", "inferred",
                List.of(opened.evidence().getFirst().id()));
        var edited = service.edit("session", opened.draft().id(), 0, candidate);
        var valid = service.validate("session", edited.draft().id(), edited.draft().draftRevision());
        assertEquals("valid", valid.draft().state(), valid.draft().issues().toString());
        return valid;
    }

    @Test void failedAndBudgetExhaustedParentsCanBeSavedLocallyWithoutChangingUsageOrChildren() {
        for (WikiLevel level : List.of(WikiLevel.HALF_DAY, WikiLevel.DAY)) {
            for (boolean budget : List.of(false, true)) {
                try (var store = new WikiStore(directory.resolve(level + "-" + budget + ".db"))) {
                    var parent = entry("parent", level, START, level == WikiLevel.DAY ? END : START.plusSeconds(28800), WikiStatus.FAILED);
                    store.upsert(parent); children(store, parent);
                    if (budget) store.markGenerationFailureIfRevision(parent, store.revision(parent.id()), null,
                            Map.of("state", "period_budget", "reason", "WIKI_PERIOD_BUDGET_EXHAUSTED", "calls", 12, "tokens", 195091),
                            Instant.parse("9999-12-31T00:00:00Z"), false);
                    assertTrue(WikiRepairService.repairable(store, store.versionedEntry("parent").entry(), CLOCK.instant()));
                    var service = service(store); var valid = valid(service);
                    var committed = service.commit("session", valid.draft().id(), valid.draft().draftRevision(), "save");
                    assertEquals(level, committed.target().level());
                    var saved = store.versionedEntry("parent").entry();
                    assertEquals(WikiStatus.SUMMARIZED, saved.status());
                    assertEquals(parent.metrics().activeSeconds(), saved.metrics().activeSeconds());
                    assertEquals(parent.metrics().afkSeconds(), saved.metrics().afkSeconds());
                    assertEquals(0, store.revision("child-0"));
                    if (budget) {
                        assertEquals(12, WikiGenerationProgress.from(saved).get("calls"));
                        assertEquals(195091, WikiGenerationProgress.from(saved).get("tokens"));
                    }
                    assertFalse(WikiRepairService.repairable(store, saved, CLOCK.instant()));
                }
            }
        }
    }

    @Test void missingFailedOrObsoleteChildrenPreventParentRepair() {
        try (var store = new WikiStore(directory.resolve("incomplete.db"))) {
            var parent = entry("parent", WikiLevel.DAY, START, END, WikiStatus.FAILED);
            store.upsert(parent);
            assertFalse(WikiRepairService.repairable(store, parent, CLOCK.instant()));
            assertEquals("WIKI_REPAIR_DEPENDENCIES_INCOMPLETE", assertThrows(WikiRepairException.class,
                    () -> service(store).open("session", "parent")).code());
            children(store, parent);
            store.markFailed("child-0", "WIKI_TRANSPORT_ERROR", END);
            assertFalse(WikiRepairService.repairable(store, parent, CLOCK.instant()));
            children(store, parent);
            var child = store.versionedEntry("child-0").entry();
            store.upsert(new WikiEntry(child.id(), child.level(), child.periodStart(), child.periodEnd(), child.timezone(), child.status(),
                    child.summary(), child.primaryTask(), child.taskSegments(), child.metrics(), List.of(), null, null,
                    0, null, null, START, START, null, null, null, Map.of(), "legacy", "legacy"));
            assertFalse(WikiRepairService.repairable(store, parent, CLOCK.instant()));
        }
    }

    @Test void changedChildRevisionRejectsValidatedParentWithoutLosingDraft() {
        try (var store = new WikiStore(directory.resolve("conflict.db"))) {
            var parent = entry("parent", WikiLevel.DAY, START, END, WikiStatus.FAILED);
            store.upsert(parent); children(store, parent);
            var service = service(store); var valid = valid(service);
            var child = store.versionedEntry("child-0").entry();
            // Even an identical text republish must invalidate the captured source version.
            store.updateStatus(child.id(), child.status(), child.summary(), child.primaryTask(), child.taskSegments(),
                    child.metrics(), List.of(), "new", "new");
            assertEquals("WIKI_REPAIR_INPUT_CONFLICT", assertThrows(WikiRepairException.class,
                    () -> service.commit("session", valid.draft().id(), valid.draft().draftRevision(), "save")).code());
            assertEquals(valid.draft().candidate(), service.bySession("session").draft().candidate());
            assertEquals(WikiStatus.FAILED, store.versionedEntry("parent").entry().status());
        }
    }

    @Test void aggregateRepairRetainsConfirmedChildProvenance() {
        try (var store = new WikiStore(directory.resolve("provenance.db"))) {
            var parent = entry("parent", WikiLevel.HALF_DAY, START, START.plusSeconds(28800), WikiStatus.FAILED);
            store.upsert(parent); children(store, parent);
            var child = store.versionedEntry("child-0").entry();
            store.markFailed(child.id(), "WIKI_EVIDENCE_UNSUPPORTED_CLAIM", END);
            var childRepair = new WikiRepairService(store, WikiRepairServiceTest::facts, WikiPrivacyPolicy::none, () -> "test", CLOCK);
            var opened = childRepair.open("child-session", child.id());
            var edited = childRepair.edit("child-session", opened.draft().id(), 0,
                    WikiRepairServiceTest.candidate("已完成数据库同步。", "user_confirmed", List.of()));
            var confirmed = childRepair.confirm("child-session", edited.draft().id(), edited.draft().draftRevision(), List.of(0));
            var checked = childRepair.validate("child-session", confirmed.draft().id(), confirmed.draft().draftRevision());
            childRepair.commit("child-session", checked.draft().id(), checked.draft().draftRevision(), "child-save");
            var repairs = service(store); var view = repairs.open("session", "parent");
            var evidence = view.evidence().stream().filter(fact -> fact.confirmedSource() != null).findFirst().orElseThrow();
            var candidate = WikiRepairServiceTest.candidate("已完成数据库同步。", "user_confirmed", List.of(evidence.id()));
            var parentDraft = repairs.edit("session", view.draft().id(), 0, candidate);
            var valid = repairs.validate("session", parentDraft.draft().id(), parentDraft.draft().draftRevision());
            assertEquals("valid", valid.draft().state(), valid.draft().issues().toString());
            repairs.commit("session", valid.draft().id(), valid.draft().draftRevision(), "parent-save");
            var sources = WikiEvidencePolicy.provenance(store.versionedEntry("parent").entry().metrics().extra());
            assertEquals(evidence.confirmedSource(), sources.get(evidence.id()));
            assertTrue(sources.get(evidence.id()).entryIds().contains(child.id()));
        }
    }

    @Test void ordinaryWaitingFutureAndUnsupportedLevelsAreNotRepairable() {
        assertFalse(WikiRepairService.repairable(entry("pending", WikiLevel.DAY, START, END, WikiStatus.PENDING), CLOCK.instant()));
        assertFalse(WikiRepairService.repairable(entry("future", WikiLevel.DAY, START, END, WikiStatus.FAILED), START));
        assertFalse(WikiRepairService.repairable(entry("week", WikiLevel.WEEK, START, END, WikiStatus.FAILED), CLOCK.instant()));
    }
}
