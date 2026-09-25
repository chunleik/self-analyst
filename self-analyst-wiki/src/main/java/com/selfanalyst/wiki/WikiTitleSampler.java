package com.selfanalyst.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.statistics.ActivityStatistics;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Deterministic, metadata-only sampling. Statistics are supplied, never recomputed from samples. */
public final class WikiTitleSampler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REPRESENTATIVES = 4;
    private static final int TIME_LAYERS = 4;
    private static final long UNREACHABLE = Long.MAX_VALUE / 2;

    private WikiTitleSampler() {}

    public record Interval(String start, String end, boolean activityMatched,
                           List<Long> sourceEventIds, int omittedSourceIds) {}
    public record Fact(String id, String source, String app, String title, String kind,
                       Double activeSeconds, int occurrences, List<Interval> intervals,
                       int omittedIntervals) {}
    public record Selection(List<Fact> facts, String jsonLines, Map<String, Object> coverage) {}

    private record Key(String source, String app, String title, String kind) {}
    private record Span(Instant start, Instant end, SortedSet<Long> ids, boolean active) {}
    private record Candidate(Key key, Fact fact, Instant start, int timeMask, Set<String> tokens, String json) {}

    public static Selection sample(WikiPeriod period, List<Event> active,
                                   List<Event> windows, List<Event> contents, int maxChars) {
        return sample(period, active, windows, contents, maxChars, WikiPrivacyPolicy.none());
    }

    public static Selection sample(WikiPeriod period, List<Event> active,
                                   List<Event> windows, List<Event> contents, int maxChars,
                                   WikiPrivacyPolicy privacy) {
        Map<Key, List<Span>> groups = new HashMap<>();
        Map<String, EventIndex> originals = byIdentity(windows);
        Map<String, EventIndex> effective = byIdentity(active);
        int deduplicatedObservations = 0;
        for (Event event : active) {
            String app = text(event, "app"), title = text(event, "title");
            if (ActivityStatistics.unknown(title) || ActivityStatistics.unknown(app)) continue;
            if (Boolean.TRUE.equals(event.data().get("private_browsing"))) continue;
            SortedSet<Long> ids = new TreeSet<>();
            for (Event original : intersections(originals, app, title, event.timestamp(), ActivityStatistics.end(event))) {
                if (original.id() > 0) ids.add(original.id());
            }
            add(groups, new Key("window", app, title, "window"),
                    event.timestamp(), ActivityStatistics.end(event), ids, true);
        }
        for (Event event : contents) {
            if (!valid(event) || Boolean.TRUE.equals(event.data().get("private_browsing"))) continue;
            String app = text(event, "app"), windowTitle = text(event, "title");
            String context = text(event, "context_title");
            String title = context.isBlank() ? windowTitle : context;
            if (ActivityStatistics.unknown(title) || ActivityStatistics.unknown(app)) continue;
            String kind = context.isBlank() ? "window" : text(event, "context_kind");
            if (!Set.of("chat", "article", "document", "page", "window").contains(kind)) kind = "unknown";
            Instant start = max(period.start(), event.timestamp());
            Instant end = min(period.end(), ActivityStatistics.end(event));
            if (!start.isBefore(end)) continue;
            SortedSet<Long> ids = new TreeSet<>();
            if (event.id() > 0) ids.add(event.id());
            List<Event> matches = intersections(effective, app, windowTitle, start, end);
            boolean fallback = context.isBlank() || (context.equals(windowTitle)
                    && (kind.equals("window") || kind.equals("unknown")));
            if (fallback) {
                // Matched system-title intervals already have an authoritative window fact.
                List<Span> remainder = subtract(start, end, matches, ids);
                if (!matches.isEmpty()) deduplicatedObservations++;
                for (Span span : remainder) {
                    add(groups, new Key("content", app, title, "window"), span.start(), span.end(), ids, false);
                }
                continue;
            }
            for (Event window : matches) {
                Instant a = max(start, window.timestamp()), b = min(end, ActivityStatistics.end(window));
                if (!a.isBefore(b)) continue;
                add(groups, new Key("content", app, title, kind), a, b, ids, true);
            }
            // Even a partly matched semantic observation may have unmatched edges.
            for (Span span : subtract(start, end, matches, ids)) {
                add(groups, new Key("content", app, title, kind), span.start(), span.end(), ids, false);
            }
        }

        List<Map.Entry<Key, List<Span>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.comparing(e -> keyOrder(e.getKey())));
        List<Candidate> candidates = new ArrayList<>();
        int nextId = 1;
        for (var group : ordered) {
            List<Span> spans = new ArrayList<>();
            spans.addAll(union(group.getValue().stream().filter(Span::active).toList()));
            spans.addAll(union(group.getValue().stream().filter(s -> !s.active()).toList()));
            spans.sort(Comparator.comparing(Span::start).thenComparing(Span::end).thenComparing(Span::active));
            Key key = group.getKey();
            Double activeSeconds = spans.stream().anyMatch(Span::active)
                    ? spans.stream().filter(Span::active).mapToDouble(s -> seconds(s.start(), s.end())).sum() : null;
            List<Interval> intervals = representatives(period, spans).stream().map(s -> new Interval(
                    s.start().toString(), s.end().toString(), s.active(), s.ids().stream().limit(REPRESENTATIVES).toList(),
                    Math.max(0, s.ids().size() - REPRESENTATIVES))).toList();
            String shownTitle = privacy.redact(bounded(key.title()));
            if (privacy.excluded(key.app(), shownTitle)) continue;
            Fact fact = new Fact("f" + nextId++, key.source(), bounded(key.app()), shownTitle, key.kind(),
                    activeSeconds, spans.size(), intervals, spans.size() - intervals.size());
            candidates.add(new Candidate(key, fact, spans.getFirst().start(), timeMask(period, spans),
                    tokens(key.title()), compactJson(fact, period)));
        }
        candidates = removeCommonTokens(candidates);
        int noiseOmittedFacts = 0;
        List<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (WikiNoisePolicy.excluded(candidate.key().app(), candidate.key().title())) noiseOmittedFacts++;
            else kept.add(candidate);
        }
        candidates = kept;
        List<Candidate> windowCandidates = candidates.stream().filter(c -> c.key().source().equals("window")).toList();
        List<Candidate> contextCandidates = candidates.stream().filter(c -> c.key().source().equals("content")).toList();
        List<Candidate> semanticCandidates = contextCandidates.stream().filter(c -> !c.key().kind().equals("window")).toList();
        int budget = Math.max(0, maxChars);
        List<Candidate> selected = new ArrayList<>();
        long totalCost = candidates.stream().mapToLong(WikiTitleSampler::cost).sum();
        int used;
        if (totalCost <= budget) {
            // The complete-facts path must not run quadratic ranking or overflow an int budget.
            selected.addAll(candidates);
            used = (int) totalCost;
        } else {
            CoveragePlan plan = coveragePlan(candidates, budget);
            int windowBudget = semanticCandidates.isEmpty() ? budget : windowCandidates.isEmpty() ? 0 : budget / 2;
            used = select(windowCandidates, selected, windowBudget, budget, plan, true);
            used += select(semanticCandidates, selected, budget - windowBudget, budget - used, plan, true);
            // Both sources have their own opportunity, but every choice also reserves enough
            // total budget to complete the globally feasible time coverage across both sources.
            used += select(candidates, selected, budget - used, budget - used, plan, false);
        }
        selected.sort(Comparator.comparing(Candidate::start).thenComparing(c -> keyOrder(c.key())));
        StringBuilder lines = new StringBuilder();
        selected.forEach(c -> lines.append(c.json()).append('\n'));
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("candidateFacts", candidates.size());
        coverage.put("noiseOmittedFacts", noiseOmittedFacts);
        coverage.put("selectedFacts", selected.size());
        coverage.put("windowCandidates", windowCandidates.size());
        coverage.put("windowSelected", selected.stream().filter(c -> c.key().source().equals("window")).count());
        coverage.put("contextCandidates", contextCandidates.size());
        coverage.put("contextSelected", selected.stream().filter(c -> c.key().source().equals("content")).count());
        coverage.put("semanticContextCandidates", semanticCandidates.size());
        coverage.put("deduplicatedObservations", deduplicatedObservations);
        int totalIntervals = candidates.stream().mapToInt(c -> c.fact().occurrences()).sum();
        int shownIntervals = selected.stream().mapToInt(c -> c.fact().intervals().size()).sum();
        coverage.put("candidateIntervals", totalIntervals);
        coverage.put("omittedIntervals", totalIntervals - shownIntervals);
        coverage.put("budgetChars", budget);
        coverage.put("usedChars", used);
        coverage.put("candidateTimeMask", candidates.stream().mapToInt(Candidate::timeMask).reduce(0, (a, b) -> a | b));
        coverage.put("selectedTimeMask", selected.stream().mapToInt(Candidate::timeMask).reduce(0, (a, b) -> a | b));
        return new Selection(selected.stream().map(Candidate::fact).toList(), lines.toString(),
                Collections.unmodifiableMap(coverage));
    }

    /** Reprojects a fact subset without renumbering its evidence IDs or sampling it again. */
    public static Selection fromFacts(WikiPeriod period, List<Fact> facts, Map<String, Object> coverage) {
        List<Fact> retained = List.copyOf(facts);
        StringBuilder lines = new StringBuilder();
        retained.forEach(fact -> lines.append(compactJson(fact, period)).append('\n'));
        Map<String, Object> metadata = new LinkedHashMap<>(coverage);
        metadata.put("selectedFacts", retained.size());
        metadata.put("windowSelected", retained.stream().filter(f -> "window".equals(f.source())).count());
        metadata.put("contextSelected", retained.stream().filter(f -> "content".equals(f.source())).count());
        metadata.put("usedChars", lines.length());
        List<Span> intervals = retained.stream().flatMap(f -> f.intervals().stream()).map(interval ->
                new Span(Instant.parse(interval.start()), Instant.parse(interval.end()), new TreeSet<Long>(),
                        interval.activityMatched())).toList();
        metadata.put("selectedTimeMask", timeMask(period, intervals));
        if (metadata.get("candidateIntervals") instanceof Number total) {
            metadata.put("omittedIntervals", Math.max(0, total.intValue() - intervals.size()));
        }
        return new Selection(retained, lines.toString(), Collections.unmodifiableMap(metadata));
    }

    private record CoveragePlan(int targetMask, long[] completionCosts, long[] exactCosts) {}

    /** Sixteen states suffice: each candidate adds one or more of the four time layers. */
    private static CoveragePlan coveragePlan(List<Candidate> candidates, int budget) {
        return coveragePlan(candidates, budget, null, 0, budget);
    }

    private static CoveragePlan coveragePlan(List<Candidate> candidates, int budget,
                                             CoveragePlan global, int covered, int remainingTotal) {
        int states = 1 << TIME_LAYERS;
        long[] costs = new long[states];
        double[] values = new double[states];
        Arrays.fill(costs, UNREACHABLE);
        costs[0] = 0;
        for (Candidate candidate : candidates) {
            for (int mask = states - 1; mask >= 0; mask--) {
                int combined = mask | candidate.timeMask();
                if (combined == mask || costs[mask] == UNREACHABLE) continue;
                long nextCost = costs[mask] + cost(candidate);
                double nextValue = values[mask] + activityValue(candidate);
                if (nextCost < costs[combined] || (nextCost == costs[combined] && nextValue > values[combined])) {
                    costs[combined] = nextCost;
                    values[combined] = nextValue;
                }
            }
        }
        int target = 0;
        for (int mask = 1; mask < states; mask++) {
            if (costs[mask] > budget) continue;
            if (global != null && costs[mask] + global.completionCosts()[global.targetMask() & ~(covered | mask)]
                    > remainingTotal) continue;
            int layers = Integer.bitCount(mask), bestLayers = Integer.bitCount(target);
            if (layers > bestLayers || (layers == bestLayers && (values[mask] > values[target]
                    || (values[mask] == values[target] && costs[mask] < costs[target])))) target = mask;
        }
        long[] completion = costs.clone();
        for (int missing = 0; missing < states; missing++) {
            for (int mask = 0; mask < states; mask++) {
                if ((mask & missing) == missing) completion[missing] = Math.min(completion[missing], costs[mask]);
            }
        }
        return new CoveragePlan(target, completion, costs);
    }

    private static int select(List<Candidate> candidates, List<Candidate> selected, int budget,
                              int remainingTotal, CoveragePlan plan, boolean reserveOwnCoverage) {
        int used = 0;
        Set<String> selectedIds = new HashSet<>();
        selected.forEach(c -> selectedIds.add(c.fact().id()));
        int covered = selected.stream().mapToInt(Candidate::timeMask).reduce(0, (a, b) -> a | b);
        CoveragePlan own = reserveOwnCoverage ? coveragePlan(candidates, budget, plan, covered, remainingTotal) : null;
        int ownCovered = 0;
        while (true) {
            Candidate best = null;
            double bestScore = -1;
            for (Candidate candidate : candidates) {
                if (selectedIds.contains(candidate.fact().id()) || cost(candidate) > budget - used) continue;
                int missing = plan.targetMask() & ~(covered | candidate.timeMask());
                if (cost(candidate) + plan.completionCosts()[missing] > (long) remainingTotal - used) continue;
                if (own != null && !canCompleteBothSources(candidate, own, ownCovered, covered, plan,
                        budget - used, remainingTotal - used)) continue;
                double score = score(candidate, selected);
                // Full identities order candidates, making score ties deterministic.
                if (score > bestScore) { best = candidate; bestScore = score; }
            }
            if (best == null) break;
            selected.add(best);
            selectedIds.add(best.fact().id());
            covered |= best.timeMask();
            ownCovered |= best.timeMask();
            used += cost(best);
        }
        return used;
    }

    private static boolean canCompleteBothSources(Candidate candidate, CoveragePlan own, int ownCovered,
                                                  int covered, CoveragePlan global, int budget, int remainingTotal) {
        int missing = own.targetMask() & ~(ownCovered | candidate.timeMask());
        for (int mask = 0; mask < own.exactCosts().length; mask++) {
            long sourceCost = cost(candidate) + own.exactCosts()[mask];
            if ((mask & missing) != missing || sourceCost > budget) continue;
            int globallyMissing = global.targetMask() & ~(covered | candidate.timeMask() | mask);
            if (sourceCost + global.completionCosts()[globallyMissing] <= remainingTotal) return true;
        }
        return false;
    }

    private static int cost(Candidate candidate) { return candidate.json().length() + 1; }

    private static double score(Candidate candidate, List<Candidate> selected) {
        int appCount = 0;
        double similarity = 0;
        for (Candidate prior : selected) {
            if (!prior.key().app().equals(candidate.key().app())) continue;
            appCount++;
            long intersection = candidate.tokens().stream().filter(prior.tokens()::contains).count();
            int union = candidate.tokens().size() + prior.tokens().size() - (int) intersection;
            if (union > 0) similarity = Math.max(similarity, (double) intersection / union);
        }
        return activityValue(candidate) * (1 - 0.75 * similarity) / (1 + 0.35 * appCount);
    }

    private static double activityValue(Candidate candidate) {
        return candidate.fact().activeSeconds() == null ? 0.25
                : Math.sqrt(Math.max(0, candidate.fact().activeSeconds()));
    }

    private static int timeMask(WikiPeriod period, List<Span> spans) {
        double length = seconds(period.start(), period.end());
        if (length <= 0) return 1;
        int mask = 0;
        for (Span span : spans) {
            double a = seconds(period.start(), span.start()), b = seconds(period.start(), span.end());
            if (!span.active()) {
                // Observation duration is not confirmed activity. Its start is a time anchor,
                // not a cheap claim to every layer crossed by a long observation interval.
                if (a >= 0 && a < length) mask |= 1 << Math.min(TIME_LAYERS - 1, (int) (a * TIME_LAYERS / length));
                continue;
            }
            for (int i = 0; i < TIME_LAYERS; i++) {
                if (a < length * (i + 1) / TIME_LAYERS && b > length * i / TIME_LAYERS) mask |= 1 << i;
            }
        }
        return mask;
    }

    private static Set<String> tokens(String title) {
        Set<String> tokens = new TreeSet<>();
        int[] points = bounded(title).toLowerCase(Locale.ROOT).codePoints().toArray();
        for (int start = 0; start < points.length;) {
            if (!Character.isLetterOrDigit(points[start])) { start++; continue; }
            boolean han = Character.UnicodeScript.of(points[start]) == Character.UnicodeScript.HAN;
            int end = start + 1;
            while (end < points.length && Character.isLetterOrDigit(points[end])
                    && (Character.UnicodeScript.of(points[end]) == Character.UnicodeScript.HAN) == han) end++;
            if (han) {
                if (end - start == 1) tokens.add("han:" + new String(points, start, 1));
                else for (int i = start; i + 1 < end; i++) tokens.add("han:" + new String(points, i, 2));
            } else if (end - start > 1) {
                tokens.add("word:" + new String(points, start, end - start));
            }
            start = end;
        }
        return tokens;
    }

    private static List<Candidate> removeCommonTokens(List<Candidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Map<String, Integer>> frequencies = new HashMap<>();
        for (Candidate c : candidates) {
            counts.merge(c.key().app(), 1, Integer::sum);
            Map<String, Integer> freq = frequencies.computeIfAbsent(c.key().app(), k -> new HashMap<>());
            c.tokens().forEach(t -> freq.merge(t, 1, Integer::sum));
        }
        return candidates.stream().map(c -> {
            Set<String> informative = new TreeSet<>(c.tokens());
            informative.removeIf(t -> frequencies.get(c.key().app()).get(t)
                    >= Math.max(3, Math.ceil(counts.get(c.key().app()) * 0.8)));
            return new Candidate(c.key(), c.fact(), c.start(), c.timeMask(), informative, c.json());
        }).toList();
    }

    private static List<Span> subtract(Instant start, Instant end, List<Event> matches, SortedSet<Long> ids) {
        List<Span> result = new ArrayList<>();
        Instant cursor = start;
        for (Event match : matches) {
            Instant a = max(start, match.timestamp()), b = min(end, ActivityStatistics.end(match));
            if (cursor.isBefore(a)) result.add(new Span(cursor, a, ids, false));
            cursor = max(cursor, b);
            if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) result.add(new Span(cursor, end, ids, false));
        return result;
    }

    private static String compactJson(Fact fact, WikiPeriod period) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", fact.id());
        row.put("src", fact.source());
        row.put("app", fact.app());
        row.put("title", fact.title());
        row.put("kind", fact.kind());
        row.put("s", fact.activeSeconds() == null ? null : roundSeconds(fact.activeSeconds()));
        row.put("n", fact.occurrences());
        row.put("r", fact.intervals().stream().map(interval -> List.of(
                roundSeconds(seconds(period.start(), Instant.parse(interval.start()))),
                roundSeconds(seconds(period.start(), Instant.parse(interval.end()))),
                interval.activityMatched() ? 1 : 0)).toList());
        row.put("omit", fact.omittedIntervals());
        try { return JSON.writeValueAsString(row); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize title facts", e); }
    }

    private static double roundSeconds(double seconds) {
        return Math.round(seconds * 1000) / 1000.0;
    }

    private static List<Span> union(List<Span> input) {
        input = new ArrayList<>(input);
        input.sort(Comparator.comparing(Span::start).thenComparing(Span::end));
        List<Span> result = new ArrayList<>();
        for (Span span : input) {
            if (result.isEmpty() || result.getLast().end().isBefore(span.start())) {
                result.add(new Span(span.start(), span.end(), new TreeSet<>(span.ids()), span.active()));
            } else {
                Span prior = result.removeLast();
                SortedSet<Long> ids = prior.ids();
                ids.addAll(span.ids());
                result.add(new Span(prior.start(), max(prior.end(), span.end()), ids, prior.active()));
            }
        }
        return result;
    }

    private static List<Span> representatives(WikiPeriod period, List<Span> items) {
        if (items.size() <= REPRESENTATIVES) return items;
        int[] masks = items.stream().mapToInt(s -> timeMask(period, List.of(s))).toArray();
        int target = Arrays.stream(masks).reduce(0, (a, b) -> a | b);
        SortedSet<Integer> chosen = new TreeSet<>();
        int longest = 0;
        for (int i = 1; i < items.size(); i++) {
            Span best = items.get(longest), candidate = items.get(i);
            if ((candidate.active() && !best.active()) || (candidate.active() == best.active()
                    && seconds(candidate.start(), candidate.end()) > seconds(best.start(), best.end()))) longest = i;
        }
        chosen.add(longest);
        int covered = masks[longest];
        for (int endpoint : new int[]{0, items.size() - 1}) {
            if (chosen.contains(endpoint)) continue;
            int missing = target & ~(covered | masks[endpoint]);
            if (Integer.bitCount(missing) <= REPRESENTATIVES - chosen.size() - 1) {
                chosen.add(endpoint);
                covered |= masks[endpoint];
            }
        }
        while (chosen.size() < REPRESENTATIVES) {
            int best = -1, bestNewLayers = -1;
            double bestDistance = -1;
            for (int i = 0; i < items.size(); i++) {
                if (chosen.contains(i)) continue;
                int missing = target & ~(covered | masks[i]);
                if (Integer.bitCount(missing) > REPRESENTATIVES - chosen.size() - 1) continue;
                int newLayers = Integer.bitCount(masks[i] & ~covered);
                Instant at = items.get(i).start();
                double distance = chosen.stream().mapToDouble(n -> Math.abs(seconds(at, items.get(n).start())))
                        .min().orElse(0);
                if (newLayers > bestNewLayers || (newLayers == bestNewLayers && distance > bestDistance)) {
                    best = i;
                    bestNewLayers = newLayers;
                    bestDistance = distance;
                }
            }
            if (best < 0) break;
            chosen.add(best);
            covered |= masks[best];
        }
        return chosen.stream().map(items::get).toList();
    }

    private static Map<String, EventIndex> byIdentity(List<Event> events) {
        Map<String, List<Event>> grouped = new HashMap<>();
        for (Event event : events) {
            if (valid(event)) grouped.computeIfAbsent(identity(text(event, "app"), text(event, "title")),
                    k -> new ArrayList<>()).add(event);
        }
        Map<String, EventIndex> result = new HashMap<>();
        grouped.forEach((key, values) -> result.put(key, new EventIndex(values)));
        return result;
    }

    /** Prefix maximum end bounds avoid rescanning a full day's repeated windows for every segment. */
    private static final class EventIndex {
        private final List<Event> events;
        private final List<Instant> ends = new ArrayList<>();

        private EventIndex(List<Event> events) {
            this.events = events;
            events.sort(Comparator.comparing(Event::timestamp).thenComparingLong(Event::id));
            Instant end = Instant.MIN;
            for (Event event : events) {
                end = max(end, ActivityStatistics.end(event));
                ends.add(end);
            }
        }

        private List<Event> intersecting(Instant start, Instant end) {
            int low = 0, high = events.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (!ends.get(mid).isAfter(start)) low = mid + 1;
                else high = mid;
            }
            List<Event> matches = new ArrayList<>();
            for (int i = low; i < events.size() && events.get(i).timestamp().isBefore(end); i++) {
                if (ActivityStatistics.end(events.get(i)).isAfter(start)) matches.add(events.get(i));
            }
            return matches;
        }
    }

    private static List<Event> intersections(Map<String, EventIndex> index, String app, String title,
                                            Instant start, Instant end) {
        EventIndex events = index.get(identity(app, title));
        return events == null ? List.of() : events.intersecting(start, end);
    }

    private static void add(Map<Key, List<Span>> groups, Key key, Instant start, Instant end,
                            SortedSet<Long> ids, boolean active) {
        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new Span(start, end, ids, active));
    }

    private static boolean valid(Event event) {
        return event != null && event.timestamp() != null && event.data() != null
                && Double.isFinite(event.duration()) && event.duration() > 0;
    }

    private static String text(Event event, String key) {
        return event.data().get(key) instanceof String value ? value.strip() : "";
    }

    private static String identity(String app, String title) { return app + "\u0000" + title; }
    private static String keyOrder(Key key) {
        return key.source() + "\u0000" + identity(key.app(), key.title()) + "\u0000" + key.kind();
    }

    private static String bounded(String value) {
        return value.codePointCount(0, value.length()) <= 160 ? value
                : value.substring(0, value.offsetByCodePoints(0, 96)) + "..."
                + value.substring(value.offsetByCodePoints(value.length(), -61));
    }

    private static double seconds(Instant a, Instant b) { return Duration.between(a, b).toNanos() / 1e9; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
