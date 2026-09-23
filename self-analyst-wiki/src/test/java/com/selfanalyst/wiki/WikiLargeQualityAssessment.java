package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 报告结构覆盖与词面代理，不提供把引用合法率转换为语义质量的合成分数。 */
public final class WikiLargeQualityAssessment {
    private static final ObjectMapper JSON = new ObjectMapper();

    private WikiLargeQualityAssessment() {}

    public static Map<String, Object> assess(WikiLargeQualityCorpus.Case fixture,
                                             WikiSummarizer.SummaryResult result) {
        Set<String> known = fixture.goldTopicByFactId().keySet();
        Set<String> displayed = new LinkedHashSet<>();
        result.taskSegments().forEach(task -> displayed.addAll(task.evidenceFactIds()));
        Set<String> members = new LinkedHashSet<>();
        JsonNode cards = JSON.valueToTree(result.metrics().extra().getOrDefault("topicCards", List.of()));
        cards.forEach(card -> card.path("memberInputIds").forEach(id -> members.add(id.asText())));

        String narrative = result.summary() + "\n" + result.primaryTask() + "\n" + result.taskSegments().stream()
                .map(task -> task.title() + "\n" + task.summary()).reduce("", (a, b) -> a + "\n" + b);
        List<String> hit = new ArrayList<>(), missed = new ArrayList<>();
        fixture.goldTopics().values().forEach(topic -> {
            boolean found = narrative.contains(topic.name()) || topic.titleAliases().stream().anyMatch(narrative::contains);
            (found ? hit : missed).add(topic.name());
        });
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("caseId", fixture.id());
        report.put("inputFacts", known.size());
        report.put("memberFacts", members.size());
        report.put("memberCoverage", (double) members.size() / known.size());
        report.put("displayedReferences", displayed.size());
        report.put("displayReferenceCoverage", (double) displayed.size() / known.size());
        report.put("referenceValidity", known.containsAll(displayed) && known.containsAll(members));
        report.put("narrativeTopicKeywordsProxy", Map.of("hit", hit, "missed", missed,
                "rate", (double) hit.size() / fixture.goldTopics().size(),
                "meaning", "lexical-proxy-only; excludes evidence, apps and reference IDs"));
        report.put("semanticQuality", "not-established-by-automation");
        report.put("manualTruthfulnessReview", Map.of("status", "pending",
                "criteria", List.of("跨应用同主题是否合理归并", "同应用的不同主题是否仍可区分",
                        "短任务与重要主题遗漏", "无词面锚点的关联是否得到支持", "是否存在无依据的动作或成果断言")));
        return report;
    }
}
