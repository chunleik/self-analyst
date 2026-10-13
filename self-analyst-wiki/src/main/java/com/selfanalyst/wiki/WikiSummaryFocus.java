package com.selfanalyst.wiki;

import com.selfanalyst.wiki.WikiFactBuilder.WikiFacts;
import com.selfanalyst.wiki.WikiTitleSampler.Fact;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local ordering and truncation after a validated model response. */
final class WikiSummaryFocus {
    static final int MAX_TOPICS = 5;
    static final int SUMMARY_LIMIT = 300;
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?]");

    private WikiSummaryFocus() {}

    static WikiSummarizer.SummaryResult apply(WikiSummarizer.SummaryResult result, WikiFacts facts) {
        List<WikiTopicProtocol.TopicCard> cards = new ArrayList<>(WikiTopicProtocol.cards(result));
        if (cards.isEmpty() || facts.sampledTitles() == null) return result;
        Map<String, Fact> byId = WikiTopicProtocol.catalog(facts.sampledTitles().facts());
        boolean parent = cards.stream().flatMap(card -> card.memberInputIds().stream()).map(byId::get)
                .filter(Objects::nonNull).allMatch(fact -> "wiki".equals(fact.source()));
        int topicLimit = parent ? 3 : MAX_TOPICS;
        cards.sort(Comparator.comparingDouble((WikiTopicProtocol.TopicCard card) -> weight(card, byId, parent)).reversed()
                .thenComparing(card -> earliest(card))
                .thenComparing(WikiTopicProtocol.TopicCard::id));
        int folded = Math.max(0, cards.size() - topicLimit);
        List<WikiTopicProtocol.TopicCard> kept = new ArrayList<>(cards.subList(0, Math.min(topicLimit, cards.size())));
        boolean confirmed = cards.stream().anyMatch(card -> "user_confirmed".equals(card.claimType()));
        if (confirmed) {
            // A confirmed assertion is indivisible. Pack only whole descriptions/references,
            // then apply the usual topic selection rather than constructing an oversized card.
            List<WikiTopicProtocol.TopicCard> candidates = new ArrayList<>(boundedConfirmedCards(cards, byId, parent));
            cards.stream().filter(card -> !"user_confirmed".equals(card.claimType())).forEach(candidates::add);
            candidates.sort(Comparator.comparingDouble((WikiTopicProtocol.TopicCard card) -> weight(card, byId, parent)).reversed()
                    .thenComparing(WikiSummaryFocus::earliest).thenComparing(WikiTopicProtocol.TopicCard::id));
            kept = new ArrayList<>();
            // A self-report must never silently disappear into automatic observations.
            kept.add(candidates.stream().filter(card -> "user_confirmed".equals(card.claimType())).findFirst().orElseThrow());
            for (WikiTopicProtocol.TopicCard card : candidates) {
                if (kept.size() >= topicLimit) break;
                int length = kept.stream().mapToInt(value -> value.summary().codePointCount(0, value.summary().length())).sum();
                if (!kept.contains(card) && length + kept.size() + card.summary().codePointCount(0, card.summary().length()) <= 1200)
                    kept.add(card);
            }
            kept.sort(Comparator.comparingDouble((WikiTopicProtocol.TopicCard card) -> weight(card, byId, parent)).reversed()
                    .thenComparing(WikiSummaryFocus::earliest).thenComparing(WikiTopicProtocol.TopicCard::id));
        } else if (folded > 0) kept.add(fold(cards.subList(topicLimit, cards.size()), byId, parent));
        List<WikiEntry.TaskSegment> segments = kept.stream().map(card -> segment(card, facts, byId)).toList();
        String primary = kept.getFirst().title();
        String summary = result.summary() != null && result.summary().length() > SUMMARY_LIMIT
                ? composedSummary(kept) : result.summary();
        if (WikiEvidencePolicy.hasConfirmed(segments))
            summary = WikiEvidencePolicy.text(WikiEvidencePolicy.composeConfirmedSummary(segments), "summary", 1200);
        else if (parent) summary = firstSentence(summary, kept);
        Map<String, Object> extra = new LinkedHashMap<>(result.metrics().extra());
        extra.put("topicCards", List.copyOf(kept));
        extra.put("foldedTopicCards", folded);
        if (confirmed) {
            Set<String> retained = new HashSet<>(); kept.forEach(card -> retained.addAll(card.memberInputIds()));
            List<WikiTopicProtocol.TopicCard> omitted = cards.stream().filter(card -> "user_confirmed".equals(card.claimType()))
                    .filter(card -> !retained.containsAll(card.memberInputIds())).toList();
            extra.put("omittedConfirmedTopicCards", omitted.size());
            extra.put("omittedConfirmedTopicIds", omitted.stream().map(WikiTopicProtocol.TopicCard::id).toList());
            extra.put("omittedConfirmedInputIds", omitted.stream().flatMap(card -> card.memberInputIds().stream()).distinct().toList());
        }
        return new WikiSummarizer.SummaryResult(summary, primary, segments,
                new WikiEntry.WikiMetrics(result.metrics().activeSeconds(), result.metrics().afkSeconds(),
                        result.metrics().switchCount(), result.metrics().topApps(), extra));
    }

    private static List<WikiTopicProtocol.TopicCard> boundedConfirmedCards(List<WikiTopicProtocol.TopicCard> cards,
                                                                             Map<String, Fact> byId, boolean parent) {
        List<WikiTopicProtocol.TopicCard> groups = new ArrayList<>();
        List<WikiTopicProtocol.TopicCard> batch = new ArrayList<>();
        for (WikiTopicProtocol.TopicCard card : cards) {
            if (!"user_confirmed".equals(card.claimType())) continue;
            List<WikiTopicProtocol.TopicCard> next = new ArrayList<>(batch); next.add(card);
            String composed = next.stream().map(WikiTopicProtocol.TopicCard::summary).distinct()
                    .collect(java.util.stream.Collectors.joining("\n"));
            int chars = composed.codePointCount(0, composed.length());
            long refs = next.stream().flatMap(value -> value.representativeFactIds().stream()).distinct().count();
            if (!batch.isEmpty() && (chars > 500 || refs > WikiEvidencePolicy.MAX_REFERENCES)) {
                groups.add(batch.size() == 1 ? batch.getFirst() : fold(batch, byId, parent));
                batch = new ArrayList<>();
            }
            batch.add(card);
        }
        if (!batch.isEmpty()) groups.add(batch.size() == 1 ? batch.getFirst() : fold(batch, byId, parent));
        return groups;
    }

    private static WikiTopicProtocol.TopicCard fold(List<WikiTopicProtocol.TopicCard> rest, Map<String, Fact> byId,
                                                      boolean parent) {
        List<String> members = rest.stream().flatMap(card -> card.memberInputIds().stream()).distinct().toList();
        List<String> refs = new ArrayList<>();
        for (WikiTopicProtocol.TopicCard card : rest) {
            if (refs.size() == 3) break;
            card.representativeFactIds().stream().filter(members::contains).findFirst().ifPresent(refs::add);
        }
        if (refs.isEmpty()) refs = WikiTopicProtocol.representatives(members, byId, 3);
        boolean confirmed = rest.stream().anyMatch(card -> "user_confirmed".equals(card.claimType()));
        if (confirmed) refs = rest.stream().flatMap(card -> card.representativeFactIds().stream()).distinct().toList();
        boolean inferred = rest.stream().anyMatch(card -> "inferred".equals(card.claimType()));
        int confidence = rest.stream().mapToInt(card -> rank(card.confidence())).min().orElse(0);
        if (inferred) confidence = Math.min(confidence, 1);
        String title = confirmed ? WikiEvidencePolicy.CONFIRMED_GROUP_TITLE : parent ? "其余活动" : "其他零散活动";
        String summary = confirmed ? rest.stream().map(WikiTopicProtocol.TopicCard::summary).distinct()
                .collect(java.util.stream.Collectors.joining("\n")) : parent ? "其余活动不再展开。" : otherSummary(rest);
        return new WikiTopicProtocol.TopicCard(confirmed ? "t-confirmed-" + WikiTopicProtocol.hash(String.join("|", refs)).substring(0, 20) : "t-other", title, summary, members, refs, List.of(),
                confirmed ? "user_confirmed" : inferred ? "inferred" : "observed", List.of("low", "medium", "high").get(confidence));
    }

    private static String otherSummary(List<WikiTopicProtocol.TopicCard> rest) {
        StringBuilder summary = new StringBuilder("涉及：");
        for (WikiTopicProtocol.TopicCard card : rest) {
            String next = summary.length() == 3 ? card.title() : "、" + card.title();
            if (summary.length() + next.length() > 500) break;
            summary.append(next);
        }
        return summary.toString();
    }

    private static String composedSummary(List<WikiTopicProtocol.TopicCard> kept) {
        List<String> titles = kept.stream().map(WikiTopicProtocol.TopicCard::title)
                .filter(title -> !title.equals("其他零散活动") && !title.equals("其余活动")).limit(3).toList();
        if (titles.isEmpty()) return "该时段没有可归纳的主要主题。";
        if (titles.size() == 1) return "主要涉及" + titles.getFirst() + "。";
        if (titles.size() == 2) return "主要涉及" + titles.get(0) + "和" + titles.get(1) + "。";
        return "主要涉及" + titles.get(0) + "、" + titles.get(1) + "和" + titles.get(2) + "。";
    }

    private static WikiEntry.TaskSegment segment(WikiTopicProtocol.TopicCard card, WikiFacts facts, Map<String, Fact> byId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", card.title());
        input.put("summary", card.summary());
        input.put("evidenceFactIds", card.representativeFactIds());
        input.put("claimType", card.claimType());
        input.put("confidence", card.confidence());
        WikiEntry.TaskSegment parsed = WikiEvidencePolicy.parseSegments(List.of(input),
                WikiEvidencePolicy.facts(facts)).getFirst();
        List<String> apps = card.memberInputIds().stream().map(byId::get).filter(Objects::nonNull)
                .map(Fact::app).filter(app -> app != null && !app.isBlank()).distinct().sorted().toList();
        return new WikiEntry.TaskSegment(parsed.title(), parsed.summary(), parsed.evidence(), apps,
                parsed.confidence(), card.representativeFactIds(), parsed.claimType());
    }

    private static double weight(WikiTopicProtocol.TopicCard card, Map<String, Fact> byId, boolean parent) {
        boolean timed = card.memberInputIds().stream().map(byId::get).filter(Objects::nonNull)
                .anyMatch(fact -> fact.activeSeconds() != null);
        if (parent && !timed) return card.memberInputIds().size();
        return card.memberInputIds().stream().map(byId::get).filter(Objects::nonNull)
                .mapToDouble(fact -> fact.activeSeconds() == null ? 0 : fact.activeSeconds()).sum();
    }

    private static String earliest(WikiTopicProtocol.TopicCard card) {
        return card.memberInputIds().stream().min(String::compareTo).orElse("");
    }

    private static int rank(String confidence) {
        return "high".equals(confidence) ? 2 : "medium".equals(confidence) ? 1 : 0;
    }

    private static String firstSentence(String summary, List<WikiTopicProtocol.TopicCard> kept) {
        String text = summary == null ? "" : summary.strip();
        Matcher end = SENTENCE_END.matcher(text);
        if (end.find()) text = text.substring(0, end.end()).strip();
        else if (!text.isEmpty()) text = text + "。";
        return text.length() <= 1 ? composedSummary(kept) : text;
    }
}
