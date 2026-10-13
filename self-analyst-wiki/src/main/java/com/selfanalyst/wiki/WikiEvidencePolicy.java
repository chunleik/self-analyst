package com.selfanalyst.wiki;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared checks for new title-based summaries; a valid citation is not proof of task completion. */
public final class WikiEvidencePolicy {
    public static final int MAX_SEGMENTS = 24;
    public static final int MAX_REFERENCES = 16;
    private static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");
    private static final String QUOTED_TITLE = "[「“\"][^」”\"\\n]{1,180}[」”\"]";
    private static final Pattern OBSERVED_OBJECT = Pattern.compile(
            "(?:查看|浏览|阅读|检索|观察到|看到|出现|涉及)(?:了)?\\s*"
                    + "(?:(?:标题|页面|文档)(?:为|是|[:：])?\\s*)?(?:" + QUOTED_TITLE + "|"
                    + "(?:已完成|已解决|已发布)[^，。；;!?\\n]{0,40}?(?:列表|页面|文档|标题|记录|文章|说明))"
                    + "|(?:(?:窗口|页面|文档|文章)(?:的)?)?标题(?:为|是|[:：])\\s*" + QUOTED_TITLE
                    + "|\\b(?:view(?:ed|ing)?|review(?:ed|ing)?|read|observed|saw)\\s+"
                    + "(?:(?:a|the)\\s+)?(?:(?:title|page|document)\\s*(?:is|was|:)?\\s*)?" + QUOTED_TITLE,
            Pattern.CASE_INSENSITIVE);
    private static final String KNOWLEDGE_PREDICATE = "(?:确认|确定|认定|证实|证明|判断|推断|断言|"
            + "表示|表明|说明|反映|代表|意味着|显示|支持|得出|得知|清楚|知道|视为|认为|当作)";
    private static final String BASIS = "(?:(?:据此|由此|因此)|(?:仅凭|仅根据|仅依据)"
            + "(?:(?!但|并|随后|然后)[^，。；;!?\\n]){1,24}?)?";
    // A compositional grammar: negative modality + epistemic/evidence predicate + complement.
    // The complement may contain subjects and coordinated alternatives; it is not limited to
    // whichever action happened to be the first one recognized by the unsupported-claim patterns.
    private static final Pattern NON_ASSERTIVE_HEAD = Pattern.compile(
            "(?:不(?:足以|能|可|会|应|宜|该)?|无法|无从|未能|尚未|并未|未|没有)" + BASIS + KNOWLEDGE_PREDICATE
                    + "|(?:没有|缺乏|未见|尚无|无)(?:足够的?)?证据(?:可以|能够|足以|能)?(?:" + KNOWLEDGE_PREDICATE + ")?"
                    + "|\\b(?:cannot|can't|could not|unable to|does not|do not|did not|doesn't|don't|didn't|not sufficient to)"
                    + "\\s+(?:confirm|determine|establish|verify|indicate|show|prove|mean|imply)"
                    + "|\\bno\\s+(?:evidence|proof)(?:\\s+(?:that|of))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_DENIAL = Pattern.compile(
            "(?:并未|未曾|没有)\\s*(?:实际|真的)?\\s*[「“\"]?\\s*$");
    private static final Pattern NEGATED_EVIDENCE_HEAD = Pattern.compile(
            "(?:不(?:能|可|足以)?|无法|未能|尚未|并未|未)" + BASIS + "(?:构成|形成|成为|作为)"
                    + "|不是|并非|不属于");
    private static final Pattern EVIDENCE_TAIL = Pattern.compile("的[^的，。；;!?\\n]{0,12}?(?:证据|依据|证明)");
    private static final Pattern NEGATED_PREFIX = Pattern.compile(
            "(?:不是|并非|并不是|并不|不能说|\\bnot)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIM_BOUNDARY = Pattern.compile(
            "[，。,;；!?！？\\n]|但是?|然而|不过|随后|接着|然后|而后|后又|并(?!未|非|不)|\\b(?:but|however|then|and)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_WORDING = Pattern.compile(
            "(?:带有?|含有?|包含)([^，。；;!?！？\\n]{1,180}?)字样(?:的)?(?:窗口|页面|文档|文章)?标题");
    private static final Pattern REASSERTED_ALTERNATIVE = Pattern.compile(
            "(?:或(?:者)?|、|\\bor\\b)[^，。；;!?\\n]{0,24}?(?:实际(?:上)?|\\bactually\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTUAL = Pattern.compile("实际(?:上)?|\\bactually\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBSERVED_INTERFACE_HEAD = Pattern.compile("(?:查看|浏览|观察到|看到|涉及)(?:了)?\\s*");
    private static final Pattern INTERFACE_SUFFIX = Pattern.compile("(?:窗口|界面)");
    private static final Pattern INTERFACE_CONTINUATION = Pattern.compile("\\s*(?:、|以及|及|和|与|[，,]\\s*(?:以及|及|和|与))");
    private static final Pattern INTERFACE_ASSERTION = Pattern.compile("实际(?:上)?|已经|正在|确实|的确|真的|而是|却|否认|并不");
    private static final Pattern COMPLETED = Pattern.compile(
            "(?:已经|已|成功)(?:完成|解决|发布|部署|修复|上线|提交)|(?:完成|解决|发布|部署|修复|上线)了"
                    + "|(?:^|[，。；;!?]|并|随后)\\s*(?:用户|本时段)?\\s*(?:完成|解决)[^，。；;!?\\n]{1,40}"
                    + "|\\b(?:successfully\\s+(?:completed|resolved|published|deployed|fixed|released)"
                    + "|(?:have|has)\\s+(?:completed|resolved|published|deployed|fixed|released))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSUPPORTED_ACTION = Pattern.compile(
            "(?:参与|参加|加入)(?:了)?[^，。；;!?\\n]{0,20}?(?:会议|通话)"
                    + "|(?:发起|创建|添加)(?:了)?[^，。；;!?\\n]{0,12}?(?:群聊|成员)"
                    + "|(?:发送|回复)(?:了)?(?:消息|邮件)"
                    + "|(?:^|[，。；;!?]|并|随后)\\s*(?:主要|已经|正在)?(?:运行|执行|启动|部署|配置)"
                    + "[^，。；;!?\\n]{0,30}(?:项目|流水线|服务)"
                    + "|\\b(?:attended|joined)\\s+(?:a\\s+|the\\s+)?meeting\\b",
            Pattern.CASE_INSENSITIVE);

    private WikiEvidencePolicy() {}

    public static final String USER_STATEMENT_PREFIX = "user_statement:";
    public static final String PROVENANCE_KEY = "userStatementProvenance";
    public static final String CONFIRMED_GROUP_TITLE = "用户确认的活动";

    /** Constructed only by the repair service from an explicit user confirmation. */
    public record UserStatement(String id, String targetId, String sourceId, String text,
                                int taskIndex, long draftRevision, Instant confirmedAt) {}

    /** Local provenance, never accepted from model output. IDs survive every aggregation layer. */
    public record ConfirmedSource(String title, String summary, List<String> statementIds,
                                  List<String> entryIds) {
        public ConfirmedSource {
            statementIds = statementIds == null ? List.of() : List.copyOf(statementIds);
            entryIds = entryIds == null ? List.of() : List.copyOf(entryIds);
            text(title, "provenance.title", 80); text(summary, "provenance.summary", 500);
            if (statementIds.isEmpty() || statementIds.size() > 384 || entryIds.isEmpty() || entryIds.size() > 384
                    || statementIds.stream().anyMatch(id -> id == null || !id.startsWith(USER_STATEMENT_PREFIX) || id.length() > 160)
                    || entryIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 160))
                throw failure("PROVENANCE", "input");
        }
    }

    public record EvidenceFact(String id, String app, String title, boolean observationOnly,
                               ConfirmedSource confirmedSource) {
        public EvidenceFact(String id, String app, String title, boolean observationOnly) {
            this(id, app, title, observationOnly, null);
        }
    }

    public static String normalizeText(String value, WikiPrivacyPolicy privacy) {
        if (value == null) return "";
        return privacy.redactText(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replaceAll("[\\p{Z}\\s]+", " ").strip();
    }

    public static String confirmedText(String title, String summary, WikiPrivacyPolicy privacy) {
        return normalizeText(title, privacy) + "\n" + normalizeText(summary, privacy);
    }

    public static Map<String, ConfirmedSource> provenance(Map<String, Object> extra) {
        if (extra == null || !(extra.get(PROVENANCE_KEY) instanceof Map<?, ?> values)) return Map.of();
        Map<String, ConfirmedSource> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            ConfirmedSource source;
            if (value instanceof ConfirmedSource typed) source = typed;
            else if (value instanceof Map<?, ?> map) {
                source = new ConfirmedSource(text(map.get("title"), "provenance.title", 80),
                        text(map.get("summary"), "provenance.summary", 500),
                        strings(map.get("statementIds"), "provenance.statementIds", 384, 160, true),
                        strings(map.get("entryIds"), "provenance.entryIds", 384, 160, true));
            } else throw failure("PROVENANCE", "input");
            if (source.statementIds().isEmpty() || source.statementIds().stream()
                    .anyMatch(id -> !id.startsWith(USER_STATEMENT_PREFIX))) throw failure("PROVENANCE", "input");
            result.put(text(key, "provenance.id", 160), source);
        });
        return Map.copyOf(result);
    }

    public static List<EvidenceFact> facts(WikiFactBuilder.WikiFacts facts) {
        Map<String, ConfirmedSource> sources = provenance(facts.statistics());
        return facts(facts.sampledTitles()).stream().map(fact -> new EvidenceFact(fact.id(), fact.app(),
                fact.title(), fact.observationOnly(), sources.get(fact.id()))).toList();
    }

    /** Safe local composition contains no new assertion and never truncates an assertion. */
    public static String composeConfirmedSummary(List<WikiEntry.TaskSegment> segments) {
        return segments.stream().map(WikiEntry.TaskSegment::summary).distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** Parent narrative selection only; every unselected complete task remains in the card catalog. */
    public static String composeBoundedSummary(List<WikiEntry.TaskSegment> segments) {
        List<WikiEntry.TaskSegment> selected = new ArrayList<>();
        segments.stream().filter(segment -> "user_confirmed".equals(segment.claimType())).findFirst().ifPresent(selected::add);
        for (WikiEntry.TaskSegment segment : segments) {
            if (selected.contains(segment)) continue;
            List<WikiEntry.TaskSegment> next = new ArrayList<>(selected); next.add(segment);
            String candidate = composeConfirmedSummary(next);
            if (candidate.codePointCount(0, candidate.length()) <= 1200) selected.add(segment);
        }
        selected.sort(java.util.Comparator.comparingInt(segments::indexOf));
        return text(composeConfirmedSummary(selected), "summary", 1200);
    }

    public static boolean hasConfirmed(List<WikiEntry.TaskSegment> segments) {
        return segments.stream().anyMatch(segment -> "user_confirmed".equals(segment.claimType()));
    }

    public static WikiSummarizer.SummaryResult validateRepair(
            String summary, String primaryTask, List<WikiEntry.TaskSegment> tasks,
            WikiFactBuilder.WikiFacts facts, String targetId, long draftRevision,
            List<UserStatement> statements, WikiPrivacyPolicy privacy) {
        Objects.requireNonNull(privacy);
        if (statements != null && statements.size() > MAX_SEGMENTS) throw failure("STRUCTURE", "statements");
        if (tasks == null || tasks.isEmpty() || tasks.size() > MAX_SEGMENTS)
            throw failure("STRUCTURE", "taskSegments");
        String cleanSummary = text(normalizeText(summary, privacy), "summary", 1200);
        String cleanPrimary = text(normalizeText(primaryTask, privacy), "primaryTask", 160);
        List<EvidenceFact> catalog = new ArrayList<>(facts(facts));
        Map<String, UserStatement> trusted = new LinkedHashMap<>();
        for (UserStatement statement : statements == null ? List.<UserStatement>of() : statements) {
            if (statement == null || statement.id() == null || !statement.id().startsWith(USER_STATEMENT_PREFIX)
                    || statement.id().length() > 160 || !Objects.equals(targetId, statement.targetId())
                    || statement.draftRevision() != draftRevision || statement.confirmedAt() == null
                    || statement.sourceId() == null || statement.sourceId().isBlank() || statement.sourceId().length() > 200
                    || statement.taskIndex() < 0 || statement.taskIndex() >= tasks.size()
                    || trusted.putIfAbsent(statement.id(), statement) != null)
                throw failure("USER_CONFIRMATION", "statements");
            WikiEntry.TaskSegment task = tasks.get(statement.taskIndex());
            if (!Objects.equals(statement.text(), confirmedText(task.title(), task.summary(), privacy)))
                throw failure("CONFIRMATION_CHANGED", "taskSegments[" + statement.taskIndex() + "]");
            ConfirmedSource source = new ConfirmedSource(normalizeText(task.title(), privacy),
                    normalizeText(task.summary(), privacy), List.of(statement.id()), List.of(targetId));
            catalog.add(new EvidenceFact(statement.id(), null, null, false, source));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < tasks.size(); index++) {
            WikiEntry.TaskSegment task = tasks.get(index);
            for (String id : task.evidenceFactIds()) {
                UserStatement source = trusted.get(id);
                if (source != null && source.taskIndex() != index)
                    throw failure("CONFIRMATION_TASK", "taskSegments[" + index + "].evidenceFactIds");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", normalizeText(task.title(), privacy));
            row.put("summary", normalizeText(task.summary(), privacy));
            row.put("evidenceFactIds", task.evidenceFactIds());
            row.put("claimType", task.claimType()); row.put("confidence", task.confidence());
            rows.add(row);
        }
        List<WikiEntry.TaskSegment> parsed = parseSegments(rows, catalog);
        if (privacy.excludedTitle(cleanSummary) || privacy.excludedTitle(cleanPrimary)
                || parsed.stream().anyMatch(task -> privacy.excludedTitle(task.title()) || privacy.excludedTitle(task.summary())))
            throw failure("PRIVACY", "summary");
        if (hasConfirmed(parsed)) {
            String composed = composeConfirmedSummary(parsed);
            if (!cleanSummary.equals(normalizeText(composed, privacy))) throw failure("CONFIRMED_SUMMARY", "summary");
            cleanSummary = text(composed, "summary", 1200);
            if (!cleanPrimary.equals(parsed.getFirst().title())) throw failure("CONFIRMED_PRIMARY", "primaryTask");
        } else {
            validateNarrative("summary", cleanSummary); validateNarrative("primaryTask", cleanPrimary);
        }
        rejectPlaceholder("summary", cleanSummary);
        rejectPlaceholder("primaryTask", cleanPrimary);
        parsed.forEach(task -> { rejectPlaceholder("taskSegments.title", task.title()); rejectPlaceholder("taskSegments.summary", task.summary()); });
        Map<String, Object> extra = new LinkedHashMap<>(facts.statistics());
        Map<String, ConfirmedSource> provenance = new LinkedHashMap<>();
        Set<String> cited = new LinkedHashSet<>(); parsed.forEach(task -> cited.addAll(task.evidenceFactIds()));
        catalog.stream().filter(fact -> cited.contains(fact.id()) && fact.confirmedSource() != null)
                .forEach(fact -> provenance.put(fact.id(), fact.confirmedSource()));
        if (!provenance.isEmpty()) extra.put(PROVENANCE_KEY, provenance);
        if (facts.sampledTitles() != null) extra.put("evidenceFacts", facts.sampledTitles().facts().stream()
                .filter(fact -> cited.contains(fact.id())).toList());
        return new WikiSummarizer.SummaryResult(cleanSummary, cleanPrimary, parsed,
                new WikiEntry.WikiMetrics(facts.activeSeconds(), facts.afkSeconds(), facts.switchCount(), facts.topApps(), extra));
    }

    private static void rejectPlaceholder(String field, String text) {
        if (text.matches("(?is).*(?:无(?:已记录)?(?:活跃)?活动|没有(?:可归纳的?|任何)?(?:主要)?(?:活跃)?(?:活动|主题)|无可归纳主题|no (?:recorded |active )?activit(?:y|ies)).*"))
            throw failure("EMPTY_ACTIVITY", field);
    }

    static boolean confirmedDescription(String title, String summary, List<ConfirmedSource> sources) {
        if (!sources.isEmpty() && sources.stream().allMatch(source -> source.title().equals(title) && source.summary().equals(summary))) return true;
        return CONFIRMED_GROUP_TITLE.equals(title) && summary.equals(sources.stream().map(ConfirmedSource::summary)
                .distinct().collect(java.util.stream.Collectors.joining("\n")));
    }

    public static List<EvidenceFact> facts(WikiTitleSampler.Selection selection) {
        if (selection == null) return List.of();
        return selection.facts().stream().map(fact -> new EvidenceFact(fact.id(), fact.app(), fact.title(),
                fact.activeSeconds() == null || fact.activeSeconds() <= 0 || "inferred".equals(fact.kind())))
                .toList();
    }

    public static void validateNarrative(String field, String text) {
        WikiNarrativePolicy.validate(field, text);
        if (text == null || text.isBlank()) return;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // A viewed object's name can contain a completed state without asserting a user outcome.
        String claims = OBSERVED_OBJECT.matcher(withoutTitleWording(normalized)).replaceAll("标题观察");
        List<Scope> qualifications = new ArrayList<>(nonAssertiveScopes(claims));
        qualifications.addAll(observedInterfaceScopes(claims));
        if (hasUnsupportedClaim(COMPLETED, claims, qualifications)
                || hasUnsupportedClaim(UNSUPPORTED_ACTION, claims, qualifications)) {
            throw failure("UNSUPPORTED_CLAIM", field);
        }
    }

    private record Scope(int start, int end) {
        boolean contains(int from, int to) { return start <= from && to <= end; }
    }

    private static String withoutTitleWording(String text) {
        var mentions = TITLE_WORDING.matcher(text);
        StringBuilder result = new StringBuilder();
        while (mentions.find()) {
            // Only the wording inside a title modifier is quoted data. Its scope cannot cross
            // an independent clause, and an assertion following the title must still be checked.
            if (!CLAIM_BOUNDARY.matcher(mentions.group(1)).find()) {
                mentions.appendReplacement(result, "标题观察");
            }
        }
        return mentions.appendTail(result).toString();
    }

    private static List<Scope> observedInterfaceScopes(String text) {
        List<Scope> scopes = new ArrayList<>();
        var heads = OBSERVED_INTERFACE_HEAD.matcher(text);
        while (heads.find()) {
            int start = heads.end(), previousEnd = start;
            var suffixes = INTERFACE_SUFFIX.matcher(text).region(start, Math.min(text.length(), start + 240));
            while (suffixes.find()) {
                // A second object must continue the same noun enumeration. The first suffix
                // closes the initial scope even if an independent assertion follows it.
                if (previousEnd > start && !INTERFACE_CONTINUATION.matcher(text.substring(previousEnd, suffixes.start())).lookingAt()) break;
                String objects = text.substring(start, suffixes.start());
                String bounded = objects.replaceAll("[，,]\\s*(?=以及|及|和|与)", "、");
                if (objects.isBlank() || CLAIM_BOUNDARY.matcher(bounded).find()
                        || INTERFACE_ASSERTION.matcher(objects).find()
                        || NON_ASSERTIVE_HEAD.matcher(objects).find()
                        || NEGATED_EVIDENCE_HEAD.matcher(objects).find()
                        || explicitlyExecutedObject(objects)) break;
                scopes.add(new Scope(start, suffixes.end()));
                previousEnd = suffixes.end();
            }
        }
        return scopes;
    }

    private static boolean explicitlyExecutedObject(String objects) {
        var actions = UNSUPPORTED_ACTION.matcher(objects);
        while (actions.find()) {
            if (actions.group().contains("了") || objects.substring(0, actions.start()).matches("(?s).*已\\s*")) return true;
        }
        return COMPLETED.matcher(objects).find();
    }

    private static boolean hasUnsupportedClaim(Pattern pattern, String text, List<Scope> qualifications) {
        var claims = pattern.matcher(text);
        while (claims.find()) {
            // Only a directly governing uncertainty/negation qualifies. Do not exempt a whole
            // sentence: later claims after a conjunction or punctuation must be checked again.
            boolean qualified = qualifications.stream().anyMatch(scope -> scope.contains(claims.start(), claims.end()));
            if (!qualified) {
                String prefix = text.substring(Math.max(0, claims.start() - 40), claims.start());
                var direct = DIRECT_DENIAL.matcher(prefix);
                qualified = direct.find() && !NEGATED_PREFIX.matcher(prefix.substring(0, direct.start())).find();
            }
            if (!qualified) return true;
        }
        return false;
    }

    private static List<Scope> nonAssertiveScopes(String text) {
        List<Scope> scopes = new ArrayList<>();
        var heads = NON_ASSERTIVE_HEAD.matcher(text);
        while (heads.find()) {
            if (!negatesHead(text, heads.start())) scopes.add(new Scope(heads.end(), complementEnd(text, heads.end())));
        }
        var evidence = NEGATED_EVIDENCE_HEAD.matcher(text);
        while (evidence.find()) {
            if (negatesHead(text, evidence.start())) continue;
            int end = complementEnd(text, evidence.end());
            String complement = text.substring(evidence.end(), end).stripLeading();
            // “并非不构成…的证据” negates the evidence denial itself, not the outcome.
            if (NEGATED_EVIDENCE_HEAD.matcher(complement).lookingAt()
                    || NON_ASSERTIVE_HEAD.matcher(complement).lookingAt()) continue;
            var tail = EVIDENCE_TAIL.matcher(text).region(evidence.end(), end);
            if (tail.find()) scopes.add(new Scope(evidence.end(), tail.start()));
        }
        return List.copyOf(scopes);
    }

    private static boolean negatesHead(String text, int start) {
        return NEGATED_PREFIX.matcher(text.substring(Math.max(0, start - 40), start)).find();
    }

    private static int complementEnd(String text, int start) {
        int end = Math.min(text.length(), start + 240);
        var boundary = CLAIM_BOUNDARY.matcher(text).region(start, end);
        if (boundary.find()) end = boundary.start();
        var alternative = REASSERTED_ALTERNATIVE.matcher(text).region(start, end);
        if (alternative.find()) end = alternative.start();
        // An immediately qualified “实际参加” remains inside the complement. A renewed
        // “实际” following an earlier outcome ends it, even without punctuation.
        var actual = ACTUAL.matcher(text).region(start, end);
        while (actual.find()) {
            String preceding = text.substring(start, actual.start());
            if (COMPLETED.matcher(preceding).find() || UNSUPPORTED_ACTION.matcher(preceding).find()) return actual.start();
        }
        return end;
    }

    public static List<WikiEntry.TaskSegment> parseSegments(Object raw, List<EvidenceFact> facts) {
        Map<String, EvidenceFact> byId = new LinkedHashMap<>();
        for (EvidenceFact fact : facts) {
            if (fact.id() == null || fact.id().startsWith(USER_STATEMENT_PREFIX) && fact.confirmedSource() == null
                    || byId.putIfAbsent(fact.id(), fact) != null) {
                throw failure("FACT_ID", "input");
            }
        }
        if (!(raw instanceof List<?> list) || list.size() > MAX_SEGMENTS
                || (!facts.isEmpty() && list.isEmpty())) {
            throw failure("STRUCTURE", "taskSegments");
        }
        List<WikiEntry.TaskSegment> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> segment)) throw failure("STRUCTURE", "taskSegments");
            String title = text(segment.get("title"), "taskSegments.title", 80);
            String summary = text(segment.get("summary"), "taskSegments.summary", 500);
            // Older model responses may include apps/evidence in any shape. They are not
            // authoritative fields; derive both exclusively from validated local references.
            List<String> ids = strings(segment.get("evidenceFactIds"), "taskSegments.evidenceFactIds",
                    MAX_REFERENCES, 160, true);
            String claimType = text(segment.get("claimType"), "taskSegments.claimType", 16);
            if (!Set.of("observed", "inferred", "user_confirmed").contains(claimType)) {
                throw failure("CLAIM_TYPE", "taskSegments.claimType");
            }
            String confidence = text(segment.get("confidence"), "taskSegments.confidence", 8);
            if (!CONFIDENCES.contains(confidence)) throw failure("CONFIDENCE", "taskSegments.confidence");
            List<EvidenceFact> cited = new ArrayList<>();
            for (String id : ids) {
                EvidenceFact fact = byId.get(id);
                if (fact == null) throw failure("UNKNOWN_FACT", "taskSegments.evidenceFactIds");
                cited.add(fact);
            }
            List<ConfirmedSource> confirmed = cited.stream().map(EvidenceFact::confirmedSource)
                    .filter(Objects::nonNull).distinct().toList();
            if ("user_confirmed".equals(claimType)) {
                if (confirmed.isEmpty() || !confirmedDescription(title, summary, confirmed))
                    throw failure("USER_CONFIRMATION", "taskSegments.summary");
                WikiNarrativePolicy.validate("taskSegments.title", title);
                WikiNarrativePolicy.validate("taskSegments.summary", summary);
                if ("high".equals(confidence)) confidence = "medium";
            } else {
                if (!confirmed.isEmpty()) throw failure("CONFIRMED_SOURCE", "taskSegments.claimType");
                validateNarrative("taskSegments.title", title);
                validateNarrative("taskSegments.summary", summary);
            }
            Set<String> citedApps = new LinkedHashSet<>();
            cited.stream().map(EvidenceFact::app).filter(app -> app != null && !app.isBlank())
                    .forEach(citedApps::add);
            List<String> apps = List.copyOf(citedApps);
            boolean observationsOnly = cited.stream().allMatch(EvidenceFact::observationOnly);
            if (observationsOnly && confirmed.isEmpty()) confidence = "low";
            else if ("inferred".equals(claimType) && "high".equals(confidence)) confidence = "medium";
            List<String> evidence = cited.stream().map(WikiEvidencePolicy::evidenceText).toList();
            result.add(new WikiEntry.TaskSegment(title, summary, evidence, apps, confidence, ids, claimType));
        }
        return List.copyOf(result);
    }

    private static String evidenceText(EvidenceFact fact) {
        if (fact.confirmedSource() != null) return "用户确认陈述（未经独立核实）：" + fact.confirmedSource().summary();
        String title = evidenceTitle(fact);
        return title == null ? "观察到相关应用的标题线索。" : "观察到标题：「" + title + "」";
    }

    /** A bounded, title-only value for localized evidence rendering; null means use a generic label. */
    public static String evidenceTitle(EvidenceFact fact) {
        String title = fact.title() == null ? "" : fact.title().replaceAll("[\\p{Cntrl}]", " ");
        if (title.codePointCount(0, title.length()) > 160) title = title.substring(0, title.offsetByCodePoints(0, 160));
        try {
            // Do not reproduce statistical reports from a source title in the user-facing evidence.
            WikiNarrativePolicy.validate("taskSegments.evidence", title);
            return title.isBlank() ? null : title;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String text(Object value, String field, int maxChars) {
        if (!(value instanceof String text) || text.isBlank()
                || text.codePointCount(0, text.length()) > maxChars) throw failure("STRUCTURE", field);
        return text.strip();
    }

    private static List<String> strings(Object value, String field, int maxCount, int maxChars, boolean nonempty) {
        if (!(value instanceof List<?> list) || list.size() > maxCount || (nonempty && list.isEmpty())) {
            throw failure("STRUCTURE", field);
        }
        List<String> result = list.stream().map(item -> text(item, field, maxChars)).toList();
        if (new LinkedHashSet<>(result).size() != result.size()) throw failure("STRUCTURE", field);
        return result;
    }

    private static IllegalArgumentException failure(String reason, String field) {
        return new IllegalArgumentException("WIKI_EVIDENCE_" + reason + ":" + field);
    }
}
