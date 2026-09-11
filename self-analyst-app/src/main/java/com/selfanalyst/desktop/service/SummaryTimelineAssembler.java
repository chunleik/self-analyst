package com.selfanalyst.desktop.service;

import com.selfanalyst.wiki.WikiEntry;
import com.selfanalyst.wiki.WikiLevel;
import com.selfanalyst.wiki.WikiStatus;
import com.selfanalyst.wiki.WikiStore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles closed and span timeline slots from Wiki, falling back to local facts.
 */
public class SummaryTimelineAssembler {

    private final com.selfanalyst.i18n.Lang lang;
    private final WikiStore wikiStore;
    private final SummaryFactSource facts;
    private final SummaryPromptService prompts;

    public SummaryTimelineAssembler(WikiStore wikiStore, SummaryFactSource facts, SummaryPromptService prompts) {
        this(wikiStore, facts, prompts, com.selfanalyst.i18n.Lang.chinese());
    }

    private SummaryTimelineAssembler(WikiStore wikiStore, SummaryFactSource facts, SummaryPromptService prompts, com.selfanalyst.i18n.Lang lang) {
        this.lang = lang;
        this.wikiStore = wikiStore;
        this.facts = facts;
        this.prompts = prompts;
    }

    public Map<String, Object> assembleClosed(SummaryWindowClassifier.Slot slot, com.selfanalyst.i18n.Lang lang) { return new SummaryTimelineAssembler(wikiStore, facts, prompts, lang).assembleClosed(slot); }
    private String duration(double seconds) { return SummaryService.formatDuration(seconds, lang); }

    public Map<String, Object> assembleClosed(SummaryWindowClassifier.Slot slot) {
        if (slot.span()) {
            return assembleSpan(slot);
        }
        WikiEntry entry = summarized(slot.start(), slot.end(), slot.wikiLevel());
        if (entry != null) {
            return fromWiki(slot, entry, "wiki", false);
        }
        return fromLocal(slot, "local");
    }

    private Map<String, Object> assembleSpan(SummaryWindowClassifier.Slot slot) {
        WikiEntry parent = summarized(slot.start(), slot.end(), slot.wikiLevel());
        if (parent != null) {
            return fromWiki(slot, parent, "wiki", false);
        }
        WikiLevel childLevel = childLevel(slot.wikiLevel());
        List<WikiEntry> children = childLevel == null
                ? List.of()
                : summarizedList(slot.start(), slot.end(), childLevel).stream()
                .filter(entry -> entry.periodEnd() != null && entry.periodEnd().isBefore(slot.end()))
                .sorted(Comparator.comparing(WikiEntry::periodStart))
                .toList();
        if (!children.isEmpty()) {
            return compose(slot, children);
        }
        return fromLocal(slot, "local");
    }

    private WikiLevel childLevel(WikiLevel parent) {
        if (parent == WikiLevel.WEEK || parent == WikiLevel.BIWEEK || parent == WikiLevel.MONTH) {
            return WikiLevel.DAY;
        }
        return null;
    }

    private Map<String, Object> compose(SummaryWindowClassifier.Slot slot, List<WikiEntry> children) {
        List<String> parts = new ArrayList<>();
        for (WikiEntry child : children) {
            String task = child.primaryTask() != null && !child.primaryTask().isBlank()
                    ? child.primaryTask()
                    : child.summary();
            if (task != null && !task.isBlank()) {
                parts.add(labelFor(child) + task);
            }
        }
        String insight = String.join("；", parts);
        if (insight.length() > 400) {
            insight = insight.substring(0, 399) + "…";
        }
        Map<String, Object> map = base(slot);
        map.put("headline", com.selfanalyst.i18n.Messages.text(lang, "local.partial").formatted(slot.label()));
        map.put("insight", insight);
        map.put("confidence", "medium");
        map.put("source", "wiki-partial");
        map.put("incomplete", true);
        return map;
    }

    private String labelFor(WikiEntry child) {
        if (child.periodStart() == null) {
            return "";
        }
        LocalDate day = child.periodStart().atZone(ZoneId.systemDefault()).toLocalDate();
        return day + "：";
    }

    private Map<String, Object> fromWiki(SummaryWindowClassifier.Slot slot, WikiEntry entry,
                                         String source, boolean incomplete) {
        Map<String, Object> map = base(slot);
        String headline = entry.primaryTask() != null && !entry.primaryTask().isBlank()
                ? entry.primaryTask()
                : (entry.summary() != null ? entry.summary() : slot.label());
        map.put("headline", headline);
        map.put("insight", entry.summary() == null ? "" : entry.summary());
        map.put("confidence", "high");
        map.put("source", source);
        map.put("incomplete", incomplete);
        if (entry.metrics() != null) {
            map.put("activeTime", duration(entry.metrics().activeSeconds()));
            map.put("afkTime", duration(entry.metrics().afkSeconds()));
            map.put("switchCount", entry.metrics().switchCount());
            if (entry.metrics().topApps() != null) {
                map.put("topApps", entry.metrics().topApps().stream()
                        .map(app -> app.app() + " " + duration(app.seconds()))
                        .toList());
            }
        }
        return map;
    }

    private Map<String, Object> fromLocal(SummaryWindowClassifier.Slot slot, String source) {
        SummaryService.LocalFacts local = facts.factsFor(slot.start(), slot.end(), slot.label());
        SummaryPromptService.EnhancedSummary enhanced = prompts.localOnly(local);
        Map<String, Object> map = fromEnhanced(slot.key(), slot.label(), enhanced);
        map.put("source", source);
        map.put("incomplete", wikiStore == null);
        return map;
    }

    static Map<String, Object> fromEnhanced(String key, String label,
                                            SummaryPromptService.EnhancedSummary enhanced) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("label", label);
        map.put("headline", enhanced.headline());
        map.put("insight", enhanced.insight());
        map.put("confidence", enhanced.confidence());
        map.put("topApps", enhanced.topApps());
        map.put("activeTime", enhanced.activeTime());
        map.put("afkTime", enhanced.afkTime());
        map.put("switchCount", enhanced.switchCount());
        map.put("evidence", enhanced.evidence());
        return map;
    }

    static Map<String, Object> fromEnhanced(String key, String label,
                                            SummaryPromptService.EnhancedSummary enhanced,
                                            SummaryService.LocalFacts liveFacts) {
        Map<String, Object> map = fromEnhanced(key, label, enhanced);
        if (liveFacts != null) {
            map.put("topApps", liveFacts.topApps());
            map.put("activeTime", liveFacts.activeTime());
            map.put("afkTime", liveFacts.afkTime());
            map.put("switchCount", liveFacts.switchCount());
            map.put("evidence", liveFacts.evidence());
        }
        return map;
    }

    private Map<String, Object> base(SummaryWindowClassifier.Slot slot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", slot.key());
        map.put("label", slot.label());
        map.put("topApps", List.of());
        map.put("activeTime", "");
        map.put("afkTime", "");
        map.put("switchCount", 0);
        map.put("evidence", List.of());
        return map;
    }

    private WikiEntry summarized(Instant start, Instant end, WikiLevel level) {
        return summarizedList(start, end, level).stream().findFirst().orElse(null);
    }

    private List<WikiEntry> summarizedList(Instant start, Instant end, WikiLevel level) {
        if (wikiStore == null || level == null) {
            return List.of();
        }
        try {
            return wikiStore.query(start, end, level).stream()
                    .filter(entry -> entry.status() == WikiStatus.SUMMARIZED)
                    .filter(entry -> entry.summary() != null && !entry.summary().isBlank())
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
