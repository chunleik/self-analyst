package com.selfanalyst.wiki;

import com.selfanalyst.wiki.WikiFactBuilder.WikiFacts;
import com.selfanalyst.wiki.WikiTitleSampler.Fact;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Lexical hints organize the complete title catalog; no hint asserts a real-world topic. */
public final class WikiTopicPlanner {
    public static final String VERSION = "wiki-topic-plan-v1";
    private static final Pattern LATIN = Pattern.compile("[\\p{IsLatin}][\\p{IsLatin}\\p{N}_./-]*");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]+");

    private WikiTopicPlanner() {}

    public record Batch(List<Fact> facts, String projection) {}
    public record Plan(List<Batch> leaves, List<String> omittedFactIds, int candidateGroups,
                       int reservedMergeCalls, String fingerprint) {
        public boolean complete() { return omittedFactIds.isEmpty(); }
    }

    public static Plan plan(WikiFacts facts, WikiSummaryPipeline.Limits limits, int overheadChars) {
        return plan(facts, limits, overheadChars, () -> {});
    }

    static Plan plan(WikiFacts full, WikiSummaryPipeline.Limits limits, int overheadChars, Runnable checkDeadline) {
        List<Fact> facts = new ArrayList<>();
        for (Fact fact : full.sampledTitles().facts()) { checkDeadline.run(); facts.add(fact); }
        facts.sort(Comparator.comparing(Fact::id));
        Map<String, Fact> catalog = WikiTopicProtocol.catalog(facts);
        Map<String, Set<String>> tokens = new LinkedHashMap<>();
        Map<String, Integer> frequencies = new HashMap<>();
        for (Fact fact : facts) {
            checkDeadline.run();
            Set<String> values = tokens(fact.title());
            tokens.put(fact.id(), values);
            values.forEach(token -> frequencies.merge(token, 1, Integer::sum));
        }
        Map<String, String> groupById = new HashMap<>();
        SortedMap<String, List<Fact>> groups = new TreeMap<>();
        int commonThreshold = Math.max(3, (int) Math.ceil(facts.size() * .25));
        for (Fact fact : facts) {
            checkDeadline.run();
            boolean shortDistinct = fact.title().codePointCount(0, fact.title().length()) <= 16
                    && tokens.get(fact.id()).stream().filter(token -> frequencies.get(token) == 1).count() >= 2;
            String anchor = (shortDistinct ? java.util.stream.Stream.<String>empty() : tokens.get(fact.id()).stream())
                    .filter(token -> frequencies.get(token) >= 2 && frequencies.get(token) <= commonThreshold)
                    .min(Comparator.<String>comparingInt(frequencies::get).thenComparing(Comparator.naturalOrder()))
                    .orElse("title:" + Normalizer.normalize(fact.title(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
            groupById.put(fact.id(), anchor);
            groups.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(fact);
        }
        List<String> apps = facts.stream().map(Fact::app).distinct().sorted().toList();
        List<String> sources = facts.stream().map(Fact::source).distinct().sorted().toList();
        List<String> groupKeys = List.copyOf(groups.keySet());
        Map<String, String> rows = new HashMap<>();
        for (Fact fact : facts) {
            checkDeadline.run();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fact.id()); row.put("title", fact.title());
            row.put("a", apps.indexOf(fact.app())); row.put("s", sources.indexOf(fact.source()));
            row.put("l", layer(full.period(), fact));
            row.put("o", fact.activeSeconds() == null || fact.activeSeconds() <= 0 || "inferred".equals(fact.kind()) ? 1 : 0);
            row.put("g", Collections.binarySearch(groupKeys, groupById.get(fact.id())));
            rows.put(fact.id(), WikiTopicProtocol.json(row) + "\n");
        }
        String dictionary = WikiTopicProtocol.json(Map.of("apps", apps, "sources", sources)) + "\n";
        Map<String, Integer> costs = new HashMap<>();
        rows.forEach((id, row) -> costs.put(id, row.length()));
        Map<String, String> bundles = new HashMap<>();
        addChildCosts(full, catalog, costs, bundles, checkDeadline);
        WikiFacts template = full.withInput(full.sampledTitles(), List.of());
        int childHeading = full.childSummaries().isEmpty() ? 0 : 50;
        int capacity = Math.min(limits.factChars() - dictionary.length(), limits.requestChars() - overheadChars
                - WikiTopicProtocol.factPrompt(template, "DIRECT", dictionary).length()) - childHeading;
        if (capacity < 1) throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.INPUT,
                "WIKI_REQUEST_BUDGET", true);
        int leafSlots = limits.maxCalls() == 1 ? 1 : limits.maxCalls() - 1;
        // A compact card is bounded independently of its number of source facts. Reserve an
        // intermediate request when the worst-case leaf card fan-out cannot fit the root.
        int mergeSlots = limits.maxCalls() >= 4
                && leafSlots * WikiTopicProtocol.MAX_CARDS * (80 + 500 + 128) + 1600 + overheadChars > limits.requestChars() ? 1 : 0;
        leafSlots -= mergeSlots;
        Comparator<Fact> locality = Comparator.comparing((Fact f) -> groupById.get(f.id()))
                .thenComparingInt(f -> layer(full.period(), f)).thenComparing(Fact::app).thenComparing(Fact::id);
        List<Fact> eligible = facts.stream().filter(f -> costs.get(f.id()) <= capacity).sorted(locality).toList();
        List<List<Fact>> packed = pack(eligible, costs, bundles, capacity);
        if (packed.size() > leafSlots) {
            List<Fact> fair = fairOrder(groups, full.period(), costs, checkDeadline);
            List<Fact> selected = new ArrayList<>();
            long used = 0, totalCapacity = (long) capacity * leafSlots;
            for (Fact fact : fair) {
                checkDeadline.run();
                int cost = costs.get(fact.id());
                if (cost <= capacity && used + cost <= totalCapacity) { selected.add(fact); used += cost; }
            }
            packed = pack(selected.stream().sorted(locality).toList(), costs, bundles, capacity);
            while (packed.size() > leafSlots && !selected.isEmpty()) {
                checkDeadline.run();
                selected.removeLast();
                packed = pack(selected.stream().sorted(locality).toList(), costs, bundles, capacity);
            }
        }
        Set<String> processed = new TreeSet<>();
        List<Batch> batches = new ArrayList<>();
        for (List<Fact> batch : packed) {
            checkDeadline.run();
            batch.forEach(f -> processed.add(f.id()));
            batches.add(new Batch(List.copyOf(batch), dictionary + batch.stream().map(f -> rows.get(f.id()))
                    .collect(java.util.stream.Collectors.joining())));
        }
        List<String> omitted = catalog.keySet().stream().filter(id -> !processed.contains(id)).sorted().toList();
        String fingerprint = WikiTopicProtocol.hash(WikiTopicProtocol.json(batches.stream()
                .map(batch -> batch.facts().stream().map(Fact::id).toList()).toList()));
        return new Plan(List.copyOf(batches), omitted, groups.size(), mergeSlots, fingerprint);
    }

    private static void addChildCosts(WikiFacts full, Map<String, Fact> catalog, Map<String, Integer> costs,
                                      Map<String, String> bundles, Runnable checkDeadline) {
        var reader = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String child : full.childSummaries()) {
            checkDeadline.run();
            try {
                var decoded = reader.readTree(child);
                if (decoded == null) continue;
                var refs = decoded.path("evidenceFactIds");
                if (!refs.isArray() || refs.isEmpty()) continue;
                List<String> ids = new ArrayList<>(); refs.forEach(id -> ids.add(id.asText()));
                if (ids.stream().anyMatch(id -> !catalog.containsKey(id))) continue;
                int share = (child.length() + 1 + ids.size() - 1) / ids.size();
                ids.forEach(id -> costs.merge(id, share, Integer::sum));
                // A multi-application child task keeps its dated narrative when a complete
                // plan is feasible. Scarce plans still report any incomplete child explicitly.
                if (ids.stream().allMatch(id -> "wiki".equals(catalog.get(id).source()))) {
                    String bundle = WikiTopicProtocol.hash(child);
                    ids.forEach(id -> bundles.put(id, bundle));
                }
            } catch (java.io.IOException ignored) { /* The input filter also rejects malformed child records. */ }
        }
    }

    private static List<List<Fact>> pack(List<Fact> facts, Map<String, Integer> costs,
                                         Map<String, String> bundles, int capacity) {
        List<List<Fact>> result = new ArrayList<>();
        List<Fact> batch = new ArrayList<>(); int used = 0;
        Map<String, List<Fact>> units = new LinkedHashMap<>();
        for (Fact fact : facts) units.computeIfAbsent(bundles.getOrDefault(fact.id(), fact.id()), ignored -> new ArrayList<>()).add(fact);
        for (List<Fact> unit : units.values()) {
            int cost = unit.stream().mapToInt(fact -> costs.get(fact.id())).sum();
            if (cost > capacity) continue;
            if (used + cost > capacity && !batch.isEmpty()) { result.add(batch); batch = new ArrayList<>(); used = 0; }
            batch.addAll(unit); used += cost;
        }
        if (!batch.isEmpty()) result.add(batch);
        return result;
    }

    private static List<Fact> fairOrder(SortedMap<String, List<Fact>> groups, WikiPeriod period,
                                      Map<String, Integer> costs, Runnable checkDeadline) {
        List<ArrayDeque<Fact>> candidates = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            checkDeadline.run();
            Map<Integer, SortedMap<String, ArrayDeque<Fact>>> strata = new TreeMap<>();
            entry.getValue().stream().sorted(Comparator.comparing(Fact::id)).forEach(fact -> strata
                    .computeIfAbsent(layer(period, fact), ignored -> new TreeMap<>())
                    .computeIfAbsent(fact.source() + "|" + fact.app(), ignored -> new ArrayDeque<>()).add(fact));
            ArrayDeque<Fact> ordered = new ArrayDeque<>();
            int startLayer = Math.floorMod(entry.getKey().hashCode(), 4), round = 0;
            while (strata.values().stream().flatMap(layer -> layer.values().stream()).anyMatch(queue -> !queue.isEmpty())) {
                checkDeadline.run();
                for (int offset = 0; offset < 4; offset++) {
                    int layer = (startLayer + offset) % 4;
                    if (!strata.containsKey(layer)) continue;
                    List<ArrayDeque<Fact>> queues = List.copyOf(strata.get(layer).values());
                    for (int step = 0; step < queues.size(); step++) {
                        var queue = queues.get((round + layer + step) % queues.size());
                        if (!queue.isEmpty()) { ordered.add(queue.removeFirst()); break; }
                    }
                }
                round++;
            }
            candidates.add(ordered);
        }
        // Give affordable representatives from more distinct candidates a chance before
        // one unusually long title consumes the whole request. Complete plans keep both.
        candidates.sort(Comparator.<ArrayDeque<Fact>>comparingInt(queue -> costs.get(queue.getFirst().id()))
                .thenComparing(queue -> queue.getFirst().id()));
        List<Fact> ordered = new ArrayList<>();
        while (candidates.stream().anyMatch(q -> !q.isEmpty())) {
            checkDeadline.run();
            for (var group : candidates) if (!group.isEmpty()) ordered.add(group.removeFirst());
        }
        return ordered;
    }

    static int layer(WikiPeriod period, Fact fact) {
        if (fact.intervals().isEmpty()) return 0;
        try {
            long length = Math.max(1, Duration.between(period.start(), period.end()).toMillis());
            long offset = Duration.between(period.start(), Instant.parse(fact.intervals().getFirst().start())).toMillis();
            return (int) Math.max(0, Math.min(3, 4d * offset / length));
        } catch (RuntimeException ignored) { return 0; }
    }

    private static Set<String> tokens(String title) {
        String value = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> result = new TreeSet<>();
        var words = LATIN.matcher(value);
        while (words.find()) for (String word : words.group().split("[._/-]")) {
            if (word.length() >= 2 && !word.chars().allMatch(Character::isDigit)) result.add(word);
        }
        var han = HAN.matcher(value);
        while (han.find()) {
            String word = han.group();
            for (int i = 0; i + 1 < word.length(); i++) result.add(word.substring(i, i + 2));
        }
        return result;
    }
}
