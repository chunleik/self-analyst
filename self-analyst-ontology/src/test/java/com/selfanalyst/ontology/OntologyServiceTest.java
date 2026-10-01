package com.selfanalyst.ontology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.selfanalyst.ontology.Ontology.*;
import static org.junit.jupiter.api.Assertions.*;

class OntologyServiceTest {
    @TempDir Path root;
    private final Instant start = Instant.parse("2026-09-01T08:00:00Z");
    private Entity activity(String id, String title, int day) {
        return new Entity(id, "activity", title, "", List.of(), "wiki", "entry:" + id,
                start.plusSeconds(day * 86400L), start.plusSeconds(day * 86400L + 3600),
                List.of(new Evidence("wiki:" + id + "/f1", title, true)), Map.of("periodMeaning", "summary-period"));
    }
    private Snapshot snapshot(Entity... entities) { return new Snapshot(List.of(entities), List.of(), Map.of("wiki", "current")); }
    private OntologyService service(AtomicReference<Snapshot> input) {
        return new OntologyService(new OntologyStore(root.resolve("ontology.db")), input::get);
    }
    private Page<Entity> search(OntologyService s, String type, String related, boolean unresolved) {
        return s.search("", type, related, unresolved, null, null, 0, 100);
    }

    @Test void crossDayAliasesAreInferredAndAmbiguityRemainsVisible() {
        var input = new AtomicReference<>(snapshot(activity("a", "IDE self-analyst", 0), activity("b", "Browser SelfAnalyst PR", 1)));
        try (var s = service(input)) {
            Entity p = s.saveEntity(null, "project", "SelfAnalyst", "", List.of("self-analyst"));
            assertEquals(2, search(s, "activity", p.id(), false).total());
            Detail detail = s.detail("a", null, null, 0, 100);
            assertEquals("inferred", detail.relations().items().getFirst().assertion().claimType());
            s.saveEntity(null, "project", "Other", "", List.of("self-analyst"));
            assertEquals("ambiguous", s.detail("a", null, null, 0, 100).classification());
            assertEquals(1, search(s, "activity", p.id(), false).total());
            assertEquals(1, search(s, "activity", null, true).total());
        }
    }

    @Test void correctionsSurviveRebuildRestartAndMerge() {
        var input = new AtomicReference<>(snapshot(activity("a", "Alpha beta", 0)));
        String target;
        String original;
        try (var s = service(input)) {
            Entity alpha = s.saveEntity(null, "project", "Alpha", "", List.of());
            Entity beta = s.saveEntity(null, "project", "Beta", "", List.of());
            original = beta.id();
            s.decide("a", "relatedTo", alpha.id(), "reject");
            s.decide("a", "relatedTo", beta.id(), "confirm");
            Entity merged = s.saveEntity(null, "project", "Merged", "", List.of()); target = merged.id();
            s.merge(beta.id(), merged.id());
            s.rebuild();
            assertEquals(0, search(s, "activity", alpha.id(), false).total());
            assertThrows(IllegalArgumentException.class, () -> s.merge(merged.id(), merged.id()));
        }
        try (var s = service(input)) {
            assertEquals(target, s.detail(original, null, null, 0, 100).entity().id());
            assertEquals(1, search(s, "activity", target, false).total());
            assertEquals("confirmed", s.detail("a", null, null, 0, 100).relations().items().getFirst().assertion().claimType());
        }
    }

    @Test void removedSourcesAndFailedRefreshCannotLeakOldContent() {
        Entity project = new Entity("memory:1", "project", "Private project", "", List.of(), "memory", "1", null, null, List.of(), Map.of());
        var input = new AtomicReference<>(snapshot(project, activity("a", "Private project", 0)));
        try (var s = service(input)) {
            assertEquals(1, search(s, "project", null, false).total());
            input.set(snapshot(activity("a", "Unrelated", 0)));
            assertEquals(0, search(s, "project", null, false).total());
            assertThrows(IllegalArgumentException.class, () -> s.detail("memory:1", null, null, 0, 10));
            input.set(null);
            assertThrows(IllegalStateException.class, () -> search(s, null, null, false));
            input.set(Snapshot.empty());
            assertEquals(0, search(s, null, null, false).total());
        }
    }

    @Test void namespaceAndTimePaginationArePreserved() {
        var input = new AtomicReference<>(snapshot(activity("a", "Alpha", 0), activity("b", "Alpha", 1)));
        try (var s = service(input)) {
            Entity p = s.saveEntity(null, "project", "Alpha", "", List.of());
            var page = s.search("", "activity", p.id(), false, null, null, 0, 1);
            assertTrue(page.hasMore()); assertEquals(2, page.total());
            assertEquals("b", page.items().getFirst().id());
            var day = s.search("", "activity", p.id(), false, "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z", 0, 10);
            assertEquals("a", day.items().getFirst().id());
            assertEquals("wiki:a/f1", s.detail("a", null, null, 0, 10).relations().items().getFirst().assertion().evidence().getFirst().ref());
            assertThrows(IllegalArgumentException.class, () -> s.search("", null, null, false, "bad", null, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> s.search("", null, null, false, null, null, 0, 101));
        }
    }

    @Test void wordBoundariesUnicodeAndInvalidMutations() {
        var input = new AtomicReference<>(snapshot(activity("a", "JavaScript 配置界面", 0)));
        try (var s = service(input)) {
            Entity java = s.saveEntity(null, "topic", "Java", "", List.of());
            Entity cjk = s.saveEntity(null, "topic", "配置", "", List.of());
            assertEquals(0, search(s, "activity", java.id(), false).total());
            assertEquals(1, search(s, "activity", cjk.id(), false).total());
            assertThrows(IllegalArgumentException.class, () -> s.decide("a", "supports", cjk.id(), "confirm"));
            assertThrows(IllegalArgumentException.class, () -> s.decide("missing", "about", cjk.id(), "confirm"));
            assertThrows(IllegalArgumentException.class, () -> s.saveEntity("a", "topic", "Bad", "", List.of()));
            Entity fullWidth = s.saveEntity(null, "project", "ＪａｖａＳｃｒｉｐｔ", "", List.of());
            assertEquals(1, search(s, "activity", fullWidth.id(), false).total());
        }
    }

    @Test void goalsPatternsAndImprovementsHaveTypedRelations() {
        Entity goal = new Entity("g", "goal", "Goal", "", List.of(), "memory", "g", null, null, List.of(), Map.of());
        Entity pattern = new Entity("p", "pattern", "Pattern", "", List.of(), "memory", "p", null, null, List.of(), Map.of());
        Entity log = new Entity("i", "improvement", "Improvement", "", List.of(), "memory", "i", null, null, List.of(), Map.of());
        var input = new AtomicReference<>(new Snapshot(List.of(goal, pattern, log),
                List.of(new Assertion("i", "tracks", "g", "observed", "memory", "accepted", null, null, List.of())), Map.of()));
        try (var s = service(input)) {
            Entity project = s.saveEntity(null, "project", "Project", "", List.of());
            s.decide(project.id(), "supports", "g", "confirm");
            s.decide("p", "relatedTo", project.id(), "confirm");
            assertEquals(2, s.detail("g", null, null, 0, 10).relations().total());
            assertEquals(2, s.detail(project.id(), null, null, 0, 10).relations().total());
        }
    }

    @Test void unknownAndCorruptStoresArePreserved() throws Exception {
        Path db = root.resolve("ontology.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = c.createStatement()) { sql.execute("PRAGMA user_version=99"); }
        byte[] before = Files.readAllBytes(db);
        assertThrows(IllegalStateException.class, () -> new OntologyStore(db));
        assertArrayEquals(before, Files.readAllBytes(db));
        Path corrupt = root.resolve("broken.db"); Files.writeString(corrupt, "not sqlite");
        assertThrows(IllegalStateException.class, () -> new OntologyStore(corrupt));
        assertEquals("not sqlite", Files.readString(corrupt));
    }

    @Test void storageRollbackRetainsUserDataAfterFailedWrite() throws Exception {
        Path db = root.resolve("ontology.db");
        try (var store = new OntologyStore(db)) {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var sql = c.createStatement()) {
                sql.execute("CREATE TRIGGER refuse_update BEFORE UPDATE ON user_state BEGIN SELECT RAISE(ABORT,'test failure'); END");
            }
            Entity p = new Entity("manual:1", "project", "Project", "", List.of(), "manual", "", null, null, List.of(), Map.of());
            assertThrows(IllegalStateException.class, () -> store.saveUsers(new UserState(Map.of(p.id(), p), Map.of(), Map.of())));
            assertTrue(store.users().entities().isEmpty());
            store.replaceProjection(snapshot(activity("a", "Still usable", 0)));
            assertEquals(1, store.projection().entities().size());
        }
    }

    @Test void unchangedSourceCacheInvalidatesOnUserRenameAndRejection() {
        var input = new AtomicReference<>(snapshot(activity("a", "Alpha", 0)));
        try (var s = service(input)) {
            Entity project = s.saveEntity(null, "project", "Alpha", "", List.of());
            assertEquals(1, search(s, "activity", project.id(), false).total());
            s.saveEntity(project.id(), "project", "Beta", "", List.of());
            assertEquals(0, search(s, "activity", project.id(), false).total());
            s.decide("a", "relatedTo", project.id(), "confirm");
            assertEquals(1, search(s, "activity", project.id(), false).total());
            s.removeRelationship("a", "relatedTo", project.id());
            assertEquals(0, search(s, "activity", project.id(), false).total());
        }
    }
    @Test void sourceSnapshotRejectsDanglingRelationsBeforePublication() {
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(List.of(activity("a", "Activity", 0)),
                List.of(new Assertion("a", "relatedTo", "missing", "inferred", "wiki", "accepted", null, null, List.of())), Map.of()));
    }
}
