package com.selfanalyst.ontology;

import com.selfanalyst.memory.*;
import com.selfanalyst.wiki.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.selfanalyst.ontology.Ontology.*;
import static org.junit.jupiter.api.Assertions.*;

class OntologySourcesTest {
    @TempDir Path root;
    private WikiEntry entry(String id, WikiLevel level, Instant start, long seconds, String title, List<String> refs) {
        return new WikiEntry(id, level, start, start.plusSeconds(seconds), "UTC", WikiStatus.SUMMARIZED,
                title, title, List.of(new WikiEntry.TaskSegment(title, "Related development", List.of(), List.of("IDE"), "medium", refs, "inferred")),
                new WikiEntry.WikiMetrics(10, 0, 0, List.of(), Map.of("evidenceFacts", List.of(Map.of("id", "f1", "app", "IDE", "title", title)))),
                List.of(), "model", "v1", 0, null, null, start, start, start);
    }

    @Test void projectsMemoryRemovalAndLegacyGoalsHaveLiveSourceIdentity() throws Exception {
        var memory = new LongTermMemoryService(MemoryStore.load(root));
        var project = memory.createManual("project", "SelfAnalyst", "user statement", null, "ui_manual", "active");
        memory.profile().getGoals().add(new GrowthProfile.Goal("goal", "Learn Java", "hours", 0, 10, LocalDate.now(), true));
        memory.profile().getPatterns().add(new GrowthProfile.KnownPattern("Morning focus", "user", LocalDate.now(), 8));
        memory.profile().getLogs().add(new GrowthProfile.ImprovementLog("goal", "Study", "reviewed", LocalDate.now()));
        memory.profile().getLogs().add(new GrowthProfile.ImprovementLog("missing", "Read", "observed", LocalDate.now()));
        var sources = new OntologySources(null, memory::ontologySnapshot, WikiPrivacyPolicy.none());
        try (var service = new OntologyService(new OntologyStore(root.resolve("ontology.db")), sources)) {
            assertEquals(1, service.search("", "project", null, false, null, null, 0, 20).total());
            var first = sources.get();
            assertEquals(1, first.assertions().size());
            assertTrue(first.entities().stream().anyMatch(e -> e.type().equals("improvement") && "missing".equals(e.attributes().get("goalSource"))));
            memory.delete(project.id());
            assertEquals(0, service.search("", "project", null, false, null, null, 0, 20).total());
            assertFalse(memory.profile().getMemories().stream().anyMatch(m -> m.id().equals(project.id())));
        }
    }

    @Test void wikiEvidenceIsNamespacedMissingEvidenceIsExplicitAndFinePeriodsWin() {
        try (var wiki = new WikiStore(root.resolve("wiki.db"))) {
            Instant start = Instant.parse("2026-09-01T00:00:00Z");
            wiki.upsert(entry("hour", WikiLevel.HOUR, start, 3600, "SelfAnalyst", List.of("f1", "missing")));
            wiki.upsert(entry("hour2", WikiLevel.HOUR, start.plusSeconds(86400), 3600, "SelfAnalyst", List.of("f1")));
            wiki.upsert(entry("day", WikiLevel.DAY, start, 86400, "SelfAnalyst daily", List.of("f1")));
            Snapshot snapshot = new OntologySources(wiki, GrowthProfile::new, WikiPrivacyPolicy.none()).get();
            var activities = snapshot.entities().stream().filter(e -> e.type().equals("activity")).toList();
            assertEquals(2, activities.size());
            assertEquals("1", snapshot.coverage().get("coarseEntriesOmitted"));
            Entity hour = activities.stream().filter(e -> e.sourceRef().equals("hour")).findFirst().orElseThrow();
            assertEquals("wiki:hour/fact:f1", hour.evidence().getFirst().ref());
            assertFalse(hour.evidence().get(1).available()); assertEquals("", hour.evidence().get(1).text());
            assertTrue(activities.stream().anyMatch(e -> e.evidence().getFirst().ref().equals("wiki:hour2/fact:f1")));
            assertEquals(3600, java.time.Duration.between(hour.start(), hour.end()).toSeconds());
            assertEquals("summary-period-not-task-duration", hour.attributes().get("periodMeaning"));
            wiki.markSkipped("hour", "invalidated");
            assertFalse(new OntologySources(wiki, GrowthProfile::new, WikiPrivacyPolicy.none()).get().entities().stream().anyMatch(e -> e.id().equals(hour.id())));
        }
    }

    @Test void privacyFiltersSourceTextBeforeProjection() throws Exception {
        try (var wiki = new WikiStore(root.resolve("wiki.db"))) {
            Instant start = Instant.parse("2026-09-01T00:00:00Z");
            wiki.upsert(entry("secret", WikiLevel.HOUR, start, 3600, "api_key=secretvalue", List.of("f1")));
            wiki.upsert(entry("excluded", WikiLevel.HOUR, start.plusSeconds(3600), 3600, "Private site", List.of("f1")));
            var sources = new OntologySources(wiki, GrowthProfile::new, WikiPrivacyPolicy.of("IDE", ""));
            assertTrue(sources.get().entities().isEmpty());
        }
    }

    @Test void sourceReadLimitReportsPartialCoverageAndOldStatisticsAreExcluded() throws Exception {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        WikiEntry repeated = entry("bounded", WikiLevel.HOUR, start, 3600, "Project", List.of("f1"));
        try (var wiki = new WikiStore(root.resolve("bounded.db")) {
            @Override public List<WikiEntry> ontologySnapshot(int limit) {
                assertEquals(5001, limit);
                return Collections.nCopies(limit, repeated);
            }
        }) {
            var snapshot = new OntologySources(wiki, GrowthProfile::new, WikiPrivacyPolicy.none()).get();
            assertEquals("truncated", snapshot.coverage().get("wiki"));
            assertEquals("5000", snapshot.coverage().get("entriesRead"));
        }
        Path file = root.resolve("old.db");
        try (var wiki = new WikiStore(file)) {
            wiki.upsert(repeated);
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
                 var update = connection.createStatement()) { update.executeUpdate("UPDATE wiki_entries SET statistics_version='obsolete'"); }
            assertTrue(new OntologySources(wiki, GrowthProfile::new, WikiPrivacyPolicy.none()).get().entities().isEmpty());
        }
    }
    @Test void relationshipHeavySourcesStopWithExplicitPartialCoverage() {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        List<WikiEntry> entries = new ArrayList<>();
        List<String> apps = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "App" + i).toList();
        for (int i = 0; i < 850; i++) {
            List<WikiEntry.TaskSegment> tasks = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(j -> new WikiEntry.TaskSegment("Activity " + j, "Observed", List.of(), apps, "low", List.of(), "inferred")).toList();
            entries.add(new WikiEntry("many-" + i, WikiLevel.HOUR, start.plusSeconds(i * 3600L), start.plusSeconds((i + 1) * 3600L),
                    "UTC", WikiStatus.SUMMARIZED, "", "", tasks, null, List.of(), "", "", 0, null, null, start, start, start));
        }
        try (var wiki = new WikiStore(root.resolve("relations.db")) {
            @Override public List<WikiEntry> ontologySnapshot(int limit) { return entries; }
        }) {
            Snapshot result = new OntologySources(wiki, GrowthProfile::new, WikiPrivacyPolicy.none()).get();
            assertEquals("truncated", result.coverage().get("wiki"));
            assertTrue(result.assertions().size() <= 100000);
            assertFalse(result.entities().isEmpty());
        }
    }
    @Test void readOnlyToolsHaveBoundedResultsAndSafeErrors() {
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 40; i++) entities.add(new Entity("a" + i, "activity", "Title " + i, "d".repeat(2000), List.of(), "wiki", "entry",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), java.util.stream.IntStream.range(0, 12)
                .mapToObj(j -> new Evidence("f:" + j, "e".repeat(800), true)).toList(), Map.of()));
        try (var service = new OntologyService(new OntologyStore(root.resolve("ontology.db")), () -> new Snapshot(entities, List.of(), Map.of()))) {
            var tools = new OntologyTools(service);
            String result = tools.searchOntology("", "activity", null, null, null, 0);
            assertTrue(result.length() <= 60000); assertTrue(result.contains("hasMore"));
            assertTrue(tools.inspectOntology("missing", null, null, 0).contains("Invalid ontology query"));
            service.close();
            assertTrue(tools.searchOntology("", null, null, null, null, 0).contains("Use queryWiki"));
        }
    }
}
