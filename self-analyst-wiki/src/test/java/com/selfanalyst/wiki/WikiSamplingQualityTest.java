package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.statistics.ActivityStatistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Offline, synthetic quality checks; these metrics do not certify model claim truthfulness. */
class WikiSamplingQualityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-21T04:00:00Z");

    @Test
    void fixedSanitizedCorpusProducesDeterministicCoverageAndEvidenceReport() throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Executable> assertions = new ArrayList<>();
        for (JsonNode file : resource("index.json")) {
            JsonNode fixture = resource(file.asText());
            String id = fixture.get("id").asText();
            WikiPeriod period = new WikiPeriod(WikiLevel.DAY, START,
                    START.plusSeconds(fixture.get("periodSeconds").asLong()), "UTC");
            List<Event> windows = events(fixture.get("windows"), 1);
            List<Event> contents = events(fixture.get("contents"), 10001);
            List<Event> afk = events(fixture.get("afk"), 20001);
            var statistics = ActivityStatistics.compute(windows, afk, period.start(), period.end());
            int budget = fixture.get("budgetChars").asInt();
            var full = WikiTitleSampler.sample(period, statistics.activeEvents(), windows, contents, Integer.MAX_VALUE);
            var selected = WikiTitleSampler.sample(period, statistics.activeEvents(), windows, contents, budget);

            Collections.shuffle(windows, new Random(42));
            Collections.shuffle(contents, new Random(43));
            Collections.shuffle(afk, new Random(44));
            var after = ActivityStatistics.compute(windows, afk, period.start(), period.end());
            var shuffled = WikiTitleSampler.sample(period, after.activeEvents(), windows, contents, budget);

            JsonNode expected = fixture.get("expectations");
            List<String> titles = selected.facts().stream().map(WikiTitleSampler.Fact::title).toList();
            Map<String, WikiTitleSampler.Fact> allFacts = new LinkedHashMap<>();
            full.facts().forEach(fact -> allFacts.put(fact.id(), fact));
            Map<String, Boolean> checks = new LinkedHashMap<>();
            checks.put("withinBudget", selected.jsonLines().length() <= budget);
            checks.put("deterministic", selected.equals(shuffled));
            checks.put("statisticsUnchanged", statistics.equals(after));
            checks.put("validFactReferences", selected.facts().stream().allMatch(f -> f.equals(allFacts.get(f.id()))));
            checks.put("validSourceReferences", validSources(selected, windows, contents));
            checks.put("requiredTitles", allMatch(expected.path("requiredTitles"), value -> titles.contains(value.asText())));
            checks.put("excludedTitles", allMatch(expected.path("excludedTitles"), value -> !titles.contains(value.asText())));
            checks.put("requiredTitleSuffixes", allMatch(expected.path("requiredTitleSuffixes"), value ->
                    titles.stream().anyMatch(title -> title.endsWith(value.asText()))));
            checks.put("bodyAbsent", allMatch(expected.path("forbiddenText"), value -> !selected.jsonLines().contains(value.asText())));
            checks.put("observationSemantics", allMatch(expected.path("observationTitles"), value -> selected.facts().stream()
                    .anyMatch(f -> f.title().equals(value.asText()) && f.activeSeconds() == null
                            && f.intervals().stream().noneMatch(WikiTitleSampler.Interval::activityMatched))));
            checks.put("minFacts", selected.facts().size() >= expected.path("minFacts").asInt(0));
            checks.put("maxFacts", selected.facts().size() <= expected.path("maxFacts").asInt(Integer.MAX_VALUE));
            int requiredMask = expected.path("requiredTimeMask").asInt(0);
            int selectedMask = ((Number) selected.coverage().get("selectedTimeMask")).intValue();
            int candidateMask = ((Number) selected.coverage().get("candidateTimeMask")).intValue();
            checks.put("requiredTimeLayers", (selectedMask & requiredMask) == requiredMask);
            checks.put("expectedActivityCoverage", !expected.has("estimatedActivity")
                    || statistics.estimated() == expected.get("estimatedActivity").asBoolean());

            int coveredTopics = 0;
            for (JsonNode topic : expected.path("topics")) {
                boolean covered = false;
                for (JsonNode fragment : topic.path("titleFragments")) {
                    if (titles.stream().anyMatch(title -> title.contains(fragment.asText()))) covered = true;
                }
                if (covered) coveredTopics++;
            }
            int topicCount = expected.path("topics").size();
            checks.put("requiredTopics", coveredTopics == topicCount);
            double selectedActivity = selected.facts().stream().filter(f -> "window".equals(f.source()))
                    .mapToDouble(f -> f.activeSeconds() == null ? 0 : f.activeSeconds()).sum();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("id", id);
            report.put("budgetChars", budget);
            report.put("usedChars", selected.jsonLines().length());
            report.put("candidateFacts", full.facts().size());
            report.put("selectedFacts", selected.facts().size());
            report.put("expectedTopics", topicCount);
            report.put("coveredTopics", coveredTopics);
            report.put("topicCoverage", fraction(coveredTopics, topicCount));
            report.put("candidateTimeMask", candidateMask);
            report.put("selectedTimeMask", selectedMask);
            report.put("timeLayerCoverage", fraction(Integer.bitCount(selectedMask), Integer.bitCount(candidateMask)));
            report.put("activeSecondsCoverage", fraction(selectedActivity, statistics.activeSeconds()));
            report.put("observationFacts", selected.facts().stream().filter(f -> f.activeSeconds() == null).count());
            report.put("modelCalls", 0);
            report.put("checks", checks);
            report.put("passed", checks.values().stream().allMatch(Boolean::booleanValue));
            cases.add(report);
            checks.forEach((name, passed) -> assertions.add(() -> assertTrue(passed, id + ": " + name)));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("mode", "offline-synthetic");
        report.put("factBuilderVersion", WikiFactBuilder.FACT_BUILDER_VERSION);
        report.put("automaticChecks", "input coverage, stable evidence identities, observation semantics, and budget limits");
        report.put("manualReviewRequired", "Model assertions and completed-work claims require separate human review; no model is called here.");
        report.put("cases", cases);
        Path output = Path.of("target", "wiki-sampling-quality.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        assertAll("fixed sampling quality cases", assertions);
    }

    private static JsonNode resource(String name) throws IOException {
        try (var stream = WikiSamplingQualityTest.class.getResourceAsStream("/wiki-quality/" + name)) {
            if (stream == null) throw new IOException("Missing synthetic quality fixture: " + name);
            return JSON.readTree(stream);
        }
    }

    private static List<Event> events(JsonNode rows, long firstId) {
        List<Event> result = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (String field : List.of("app", "title", "status")) {
                if (row.has(field)) data.put(field, row.get(field).asText());
            }
            if (row.has("contextTitle")) data.put("context_title", row.get("contextTitle").asText());
            if (row.has("contextKind")) data.put("context_kind", row.get("contextKind").asText());
            if (row.has("textContent")) data.put("text_content", row.get("textContent").asText());
            result.add(new Event(firstId++, START.plusNanos(Math.round(row.get("offsetSeconds").asDouble() * 1e9)),
                    row.get("durationSeconds").asDouble(), data));
        }
        return result;
    }

    private static boolean validSources(WikiTitleSampler.Selection selection, List<Event> windows, List<Event> contents) {
        Set<Long> windowIds = new HashSet<>(), contentIds = new HashSet<>();
        windows.forEach(event -> windowIds.add(event.id()));
        contents.forEach(event -> contentIds.add(event.id()));
        return selection.facts().stream().allMatch(fact -> fact.intervals().stream().allMatch(interval ->
                ("window".equals(fact.source()) ? windowIds : contentIds).containsAll(interval.sourceEventIds())));
    }

    private static boolean allMatch(JsonNode values, java.util.function.Predicate<JsonNode> predicate) {
        for (JsonNode value : values) if (!predicate.test(value)) return false;
        return true;
    }

    private static double fraction(double numerator, double denominator) {
        return denominator == 0 ? 1 : Math.round(numerator / denominator * 1_000_000) / 1_000_000.0;
    }
}
