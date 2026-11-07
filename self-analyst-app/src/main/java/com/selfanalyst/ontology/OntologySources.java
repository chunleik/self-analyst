package com.selfanalyst.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.selfanalyst.memory.GrowthProfile;
import com.selfanalyst.memory.LongTermMemoryService;
import com.selfanalyst.wiki.*;
import java.util.*;
import java.util.function.Supplier;
import static com.selfanalyst.ontology.Ontology.*;

/** Projects only allowed summaries and current memory. Never reads capture or ordinary file content. */
public final class OntologySources implements Supplier<Snapshot> {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int ENTRY_LIMIT = 5000;
    private static final int ENTITY_LIMIT = 20000;
    private static final int ASSERTION_LIMIT = 100000;
    private final WikiStore wiki;
    private final Supplier<GrowthProfile> memory;
    private final Supplier<WikiPrivacyPolicy> privacy;

    public OntologySources(WikiStore wiki, Supplier<GrowthProfile> memory, WikiPrivacyPolicy privacy) {
        this(wiki, memory, () -> privacy == null ? WikiPrivacyPolicy.none() : privacy);
    }

    public OntologySources(WikiStore wiki, Supplier<GrowthProfile> memory, Supplier<WikiPrivacyPolicy> privacy) {
        this.wiki = wiki; this.memory = memory; this.privacy = Objects.requireNonNull(privacy);
    }

    @Override public Snapshot get() {
        WikiPrivacyPolicy privacy = Objects.requireNonNull(this.privacy.get());
        Map<String, Entity> entities = new LinkedHashMap<>();
        List<Assertion> assertions = new ArrayList<>();
        Map<String, String> coverage = new LinkedHashMap<>();
        coverage.put("periodMeaning", "summary-period-not-task-duration");
        if (memory == null) coverage.put("memory", "unavailable");
        else {
            GrowthProfile profile = Objects.requireNonNull(memory.get());
            addMemory(privacy, profile, entities, assertions);
            coverage.put("memory", "current");
        }
        if (wiki == null) coverage.put("wiki", "unavailable");
        else {
            List<WikiEntry> fetched = wiki.ontologySnapshot(ENTRY_LIMIT + 1);
            List<WikiEntry> entries = fetched.stream().limit(ENTRY_LIMIT)
                    .sorted(Comparator.comparing(WikiEntry::level).thenComparing(WikiEntry::periodStart)).toList();
            List<WikiEntry> selected = new ArrayList<>();
            int omitted = 0, filtered = 0;
            boolean entityLimit = false;
            for (WikiEntry entry : entries) {
                if (entities.size() + 13 > ENTITY_LIMIT || assertions.size() + 12 > ASSERTION_LIMIT) { entityLimit = true; break; }
                // Prefer finer summaries; report every suppressed coarse entry as omitted coverage.
                if (selected.stream().anyMatch(fine -> fine.level().ordinal() < entry.level().ordinal()
                        && fine.periodStart().isBefore(entry.periodEnd()) && fine.periodEnd().isAfter(entry.periodStart()))) {
                    omitted++; continue;
                }
                boolean used = false;
                for (var task : entry.taskSegments()) {
                    if (entities.size() + 13 > ENTITY_LIMIT || assertions.size() + 12 > ASSERTION_LIMIT) { entityLimit = true; break; }
                    if (!safe(privacy, task.title()) || !safe(privacy, task.summary()) || task.apps().stream().anyMatch(privacy::excludedApp)) { filtered++; continue; }
                    String title = clean(privacy, task.title(), 300);
                    if (title.isBlank()) continue;
                    String activityId = stableId("activity", entry.id() + "\n" + task.title() + "\n" + task.summary());
                    List<Evidence> evidence = evidence(privacy, entry, task);
                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("level", entry.level().name()); attrs.put("periodMeaning", "summary-period-not-task-duration");
                    attrs.put("claimType", Objects.toString(task.claimType(), "legacy"));
                    attrs.put("confidence", Objects.toString(task.confidence(), "unknown"));
                    attrs.put("sourceCoverage", clip(entry.sourceCoverage().toString(), 1000));
                    attrs.put("evidenceOmitted", Integer.toString(Math.max(0, task.evidenceFactIds().size() - 12)));
                    Entity activity = new Entity(activityId, "activity", title, clean(privacy, task.summary(), 2000), List.of(), "wiki",
                            clip(entry.id(), 512), entry.periodStart(), entry.periodEnd(), evidence, attrs);
                    entities.put(activityId, activity); used = true;
                    // Deduplicate apps and cap independently, keeping snapshot size bounded.
                    for (String app : task.apps().stream().filter(Objects::nonNull).distinct().limit(12).toList()) {
                        if (!safe(privacy, app) || app.isBlank()) continue;
                        String appId = stableId("application", normalize(app));
                        entities.putIfAbsent(appId, new Entity(appId, "application", clean(privacy, app, 300), "", List.of(), "wiki", "",
                                null, null, List.of(), Map.of()));
                        assertions.add(new Assertion(activityId, "uses", appId, "inferred", "wiki", "accepted",
                                entry.periodStart(), entry.periodEnd(), evidence));
                    }
                }
                if (used) selected.add(entry);
            }
            coverage.put("wiki", fetched.size() > ENTRY_LIMIT || entityLimit ? "truncated" : "current");
            coverage.put("entryLimit", Integer.toString(ENTRY_LIMIT));
            coverage.put("entriesRead", Integer.toString(Math.min(fetched.size(), ENTRY_LIMIT)));
            coverage.put("coarseEntriesOmitted", Integer.toString(omitted));
            coverage.put("privacyFilteredSegments", Integer.toString(filtered));
            coverage.put("projectionLimitReached", Boolean.toString(entityLimit));
        }
        return new Snapshot(new ArrayList<>(entities.values()), assertions, coverage);
    }

    private List<Evidence> evidence(WikiPrivacyPolicy privacy, WikiEntry entry, WikiEntry.TaskSegment task) {
        JsonNode catalog = JSON.valueToTree(entry.metrics() == null || entry.metrics().extra() == null
                ? List.of() : entry.metrics().extra().getOrDefault("evidenceFacts", List.of()));
        List<Evidence> result = new ArrayList<>();
        for (String id : task.evidenceFactIds().stream().distinct().limit(12).toList()) {
            JsonNode found = null;
            if (catalog.isArray()) for (JsonNode fact : catalog) if (id.equals(fact.path("id").asText())) { found = fact; break; }
            boolean available = found != null && safe(privacy, found.path("title").asText()) && !privacy.excludedApp(found.path("app").asText());
            result.add(new Evidence("wiki:" + clip(entry.id(), 300) + "/fact:" + clip(id, 150),
                    available ? clean(privacy, found.path("app").asText() + " — " + found.path("title").asText(), 800) : "", available));
        }
        return List.copyOf(result);
    }

    private void addMemory(WikiPrivacyPolicy privacy, GrowthProfile profile, Map<String, Entity> entities, List<Assertion> assertions) {
        for (var m : profile.getMemories()) {
            if (!"active".equals(m.status()) || m.sensitive() || !Set.of("project", "goal", "pattern").contains(m.type())
                    || !safe(privacy, m.content()) || !safe(privacy, m.evidence())) continue;
            addMemoryEntity(privacy, entities, stableId("memory", m.id()), m.type(), m.content(), m.evidence(), "memory:" + m.id(), Map.of("confidence", Integer.toString(m.confidence())));
        }
        Set<String> goals = new HashSet<>();
        for (var g : profile.getGoals()) if (g.active() && safe(privacy, g.description())) {
            String id = stableId("goal", g.id());
            addMemoryEntity(privacy, entities, id, "goal", g.description(), "", "goal:" + g.id(), Map.of()); goals.add(id);
        }
        for (var p : profile.getPatterns()) if (safe(privacy, p.description()) && safe(privacy, p.evidence())) {
            addMemoryEntity(privacy, entities, stableId("pattern", p.description() + "\n" + p.confirmedAt()), "pattern", p.description(), p.evidence(),
                    "pattern:" + stableId("record", p.description() + "\n" + p.confirmedAt()), Map.of("confidence", Integer.toString(p.confidence())));
        }
        for (var log : profile.getLogs()) if (safe(privacy, log.action()) && safe(privacy, log.outcome())) {
            String id = stableId("improvement", log.goalId() + "\n" + log.action() + "\n" + log.outcome() + "\n" + log.observedAt());
            String goal = stableId("goal", Objects.toString(log.goalId(), ""));
            boolean linked = goals.contains(goal);
            addMemoryEntity(privacy, entities, id, "improvement", log.action(), log.outcome(), id,
                    Map.of("observedAt", Objects.toString(log.observedAt(), ""), "goalSource", linked ? "available" : "missing"));
            if (linked && entities.containsKey(id)) assertions.add(new Assertion(id, "tracks", goal, "observed", "memory", "accepted",
                    null, null, entities.get(id).evidence()));
        }
    }
    private void addMemoryEntity(WikiPrivacyPolicy privacy, Map<String, Entity> entities, String id, String type, String content, String evidence,
                                 String ref, Map<String, String> attrs) {
        if (content == null || content.isBlank()) return;
        if (entities.size() >= ENTITY_LIMIT) throw new IllegalStateException("Memory projection exceeds limit");
        entities.put(id, new Entity(id, type, clean(privacy, content, 300), clean(privacy, content, 2000), List.of(), "memory", clip(ref, 512),
                null, null, List.of(new Evidence(clip(ref, 512), clean(privacy, evidence, 800), true)), attrs));
    }
    private boolean safe(WikiPrivacyPolicy privacy, String text) {
        return !LongTermMemoryService.containsForbiddenContent(text) && !privacy.excludedTitle(text);
    }
    private String clean(WikiPrivacyPolicy privacy, String text, int max) { return clip(privacy.redactText(text), max); }
}
