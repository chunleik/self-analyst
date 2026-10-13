package com.selfanalyst.wiki;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Local-only repair workflow. No constructor or method can call a language model. */
public final class WikiRepairService {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final String MISSING_TEXT = "原失败文本未保存，将依据现有记录重新整理。";
    private final WikiStore store;
    private final Function<WikiEntry, WikiFactBuilder.WikiFacts> factsProvider;
    private final Supplier<WikiPrivacyPolicy> privacy;
    private final Supplier<String> configurationStamp;
    private final Clock clock;

    public record Candidate(String summary, String primaryTask, List<WikiEntry.TaskSegment> taskSegments) {
        public Candidate { taskSegments = taskSegments == null ? List.of() : List.copyOf(taskSegments); }
    }
    public record Statement(String id, String targetId, String sourceId, String text, int taskIndex,
                            long draftRevision, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant confirmedAt) {}
    public record Issue(String field, String code) {}
    public record Target(String entryId, WikiLevel level,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodStart,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodEnd,
                         String timezone, long revision) {}
    public record Draft(String id, String sessionId, String targetId, long targetRevision, long draftRevision,
                        String state, Candidate candidate, List<Statement> statements, List<Issue> issues,
                        String fingerprint, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt, Long committedRevision) {
        public Draft { statements = List.copyOf(statements); issues = List.copyOf(issues); }
    }
    public record View(Draft draft, Target target, List<WikiEvidencePolicy.EvidenceFact> evidence,
                       boolean failureTextAvailable, String message, WikiEntry currentEntry) {}

    public WikiRepairService(WikiStore store, Function<WikiEntry, WikiFactBuilder.WikiFacts> factsProvider,
                             Supplier<WikiPrivacyPolicy> privacy, Supplier<String> configurationStamp) {
        this(store, factsProvider, privacy, configurationStamp, Clock.systemUTC());
    }

    public WikiRepairService(WikiStore store, Function<WikiEntry, WikiFactBuilder.WikiFacts> factsProvider,
                             Supplier<WikiPrivacyPolicy> privacy, Supplier<String> configurationStamp, Clock clock) {
        this.store = Objects.requireNonNull(store); this.factsProvider = Objects.requireNonNull(factsProvider);
        this.privacy = Objects.requireNonNull(privacy); this.configurationStamp = Objects.requireNonNull(configurationStamp);
        this.clock = Objects.requireNonNull(clock);
    }

    public static boolean repairable(WikiEntry entry, Instant now) {
        return entry != null && entry.currentStatistics() && entry.level() == WikiLevel.HOUR
                && !entry.periodEnd().isAfter(now) && entry.status() == WikiStatus.FAILED
                && ("quality".equals(WikiGenerationProgress.state(entry)) || qualityError(entry.lastError()));
    }

    private static boolean qualityError(String code) {
        return code != null && (code.startsWith("WIKI_EVIDENCE_") || code.startsWith("WIKI_NARRATIVE_")
                || code.startsWith("WIKI_RESPONSE_"));
    }

    public Target target(String entryId) {
        WikiStore.VersionedEntry entry = store.versionedEntry(entryId);
        return entry == null ? null : target(entry);
    }

    public View open(String sessionId, String targetId) { return open(sessionId, targetId, -1); }

    public View open(String sessionId, String targetId, long expectedTargetRevision) {
        boundedId(sessionId); boundedId(targetId);
        synchronized (store) {
            Draft existing = store.repairDraftBySession(sessionId);
            if (existing != null) {
                if (!existing.targetId().equals(targetId)) throw new WikiRepairException("WIKI_REPAIR_SESSION_TARGET");
                return view(existing);
            }
            WikiStore.VersionedEntry target = requireTarget(targetId);
            if (expectedTargetRevision >= 0 && target.revision() != expectedTargetRevision)
                throw new WikiRepairException("WIKI_REPAIR_TARGET_CONFLICT");
            WikiFactBuilder.WikiFacts facts = facts(target.entry());
            Instant now = clock.instant();
            Draft draft = new Draft(UUID.randomUUID().toString(), sessionId, targetId, target.revision(), 0,
                    "unvalidated", new Candidate("", "", List.of()), List.of(), List.of(), fingerprint(facts), now, now, null);
            store.saveRepairDraft(draft, -1);
            return view(draft, target, facts);
        }
    }

    public View bySession(String sessionId) {
        boundedId(sessionId);
        synchronized (store) {
            Draft draft = store.repairDraftBySession(sessionId);
            return draft == null ? null : view(draft);
        }
    }

    public View read(String sessionId, String repairId) {
        synchronized (store) { return view(requireDraft(sessionId, repairId, null)); }
    }

    /** Fail known conflicts locally before acquiring a model session or reserving any quota. */
    public View preflightModel(String sessionId, String repairId, long draftRevision) {
        synchronized (store) {
            Draft draft = requireEditable(sessionId, repairId, draftRevision);
            WikiStore.VersionedEntry target = requireUnchangedTarget(draft);
            WikiFactBuilder.WikiFacts currentFacts = facts(target.entry());
            ensureFingerprint(draft, currentFacts);
            return view(draft, target, currentFacts);
        }
    }

    public View edit(String sessionId, String repairId, long draftRevision, Candidate candidate) {
        synchronized (store) {
            Draft old = requireEditable(sessionId, repairId, draftRevision);
            WikiStore.VersionedEntry target = requireUnchangedTarget(old);
            Candidate normalized = normalize(candidate);
            Draft changed = copy(old, old.draftRevision() + 1, "unvalidated", normalized, List.of(), List.of(),
                    fingerprint(facts(target.entry())));
            store.saveRepairDraft(changed, draftRevision);
            return view(changed);
        }
    }

    /** Model proposals have no route to confirmations or source IDs, even if they contain forged fields. */
    public View editModelCandidate(String sessionId, String repairId, long draftRevision, Candidate candidate) {
        return edit(sessionId, repairId, draftRevision, candidate);
    }

    /** Explicit reload after a retry/input change preserves wording but discards all stale authority. */
    public View rebase(String sessionId, String repairId, long draftRevision) {
        return rebase(sessionId, repairId, draftRevision, null);
    }

    public View rebase(String sessionId, String repairId, long draftRevision, Candidate candidate) {
        synchronized (store) {
            Draft old = requireEditable(sessionId, repairId, draftRevision);
            WikiStore.VersionedEntry target = requireTarget(old.targetId());
            WikiFactBuilder.WikiFacts facts = facts(target.entry());
            Draft changed = new Draft(old.id(), old.sessionId(), old.targetId(), target.revision(), draftRevision + 1,
                    "unvalidated", normalize(candidate == null ? old.candidate() : candidate), List.of(), List.of(), fingerprint(facts), old.createdAt(), clock.instant(), null);
            store.saveRepairDraft(changed, draftRevision);
            return view(changed, target, facts);
        }
    }

    public View confirm(String sessionId, String repairId, long draftRevision, List<Integer> taskIndexes) {
        if (taskIndexes == null || taskIndexes.isEmpty() || taskIndexes.size() > WikiEvidencePolicy.MAX_SEGMENTS)
            throw new WikiRepairException("WIKI_REPAIR_CONFIRMATION_REQUIRED");
        synchronized (store) {
            Draft old = requireEditable(sessionId, repairId, draftRevision);
            requireUnchangedTarget(old);
            long nextRevision = draftRevision + 1;
            List<WikiEntry.TaskSegment> tasks = new ArrayList<>(old.candidate().taskSegments());
            Map<Integer, Statement> statements = new LinkedHashMap<>();
            rebindStatements(old.statements(), tasks, nextRevision)
                    .forEach(statement -> statements.put(statement.taskIndex(), statement));
            for (Integer index : taskIndexes) {
                if (index == null || index < 0 || index >= tasks.size()) throw new WikiRepairException("WIKI_REPAIR_TASK_INDEX");
                WikiEntry.TaskSegment task = tasks.get(index);
                if (task.title().isBlank() || task.summary().isBlank()) throw new WikiRepairException("WIKI_REPAIR_EMPTY_TASK");
                Statement statement = new Statement("user_statement:" + UUID.randomUUID(), old.targetId(),
                        "local:" + old.id() + ":" + nextRevision + ":" + index,
                        WikiEvidencePolicy.confirmedText(task.title(), task.summary(), privacy.get()), index,
                        nextRevision, clock.instant());
                statements.put(index, statement);
                List<String> ids = new ArrayList<>(task.evidenceFactIds().stream().filter(id -> !id.startsWith("user_statement:")).toList());
                ids.add(statement.id());
                tasks.set(index, new WikiEntry.TaskSegment(task.title(), task.summary(), List.of(), List.of(),
                        "high".equals(task.confidence()) ? "medium" : task.confidence(), ids, "user_confirmed"));
            }
            // Provenance and locally composed prose are part of the preview and need the same CAS protection as text edits.
            Candidate candidate = new Candidate(WikiEvidencePolicy.composeConfirmedSummary(tasks), tasks.getFirst().title(), tasks);
            Draft changed = copy(old, nextRevision, "unvalidated", candidate, List.copyOf(statements.values()), List.of(), old.fingerprint());
            store.saveRepairDraft(changed, draftRevision);
            return view(changed);
        }
    }

    public View validate(String sessionId, String repairId, long draftRevision) {
        synchronized (store) {
            Draft old = requireEditable(sessionId, repairId, draftRevision);
            WikiStore.VersionedEntry target = requireUnchangedTarget(old);
            WikiFactBuilder.WikiFacts facts = facts(target.entry());
            ensureFingerprint(old, facts);
            Draft checked;
            try {
                WikiSummarizer.SummaryResult result = validateCandidate(old, facts);
                Candidate candidate = new Candidate(result.summary(), result.primaryTask(), result.taskSegments());
                long nextRevision = candidate.equals(old.candidate()) ? draftRevision : draftRevision + 1;
                checked = copy(old, nextRevision, "valid", candidate,
                        rebindStatements(old.statements(), candidate.taskSegments(), nextRevision), List.of(), old.fingerprint());
            } catch (IllegalArgumentException error) {
                checked = copy(old, draftRevision, "invalid", old.candidate(), old.statements(), List.of(issue(error)), old.fingerprint());
            }
            store.saveRepairDraft(checked, draftRevision);
            return view(checked, target, facts);
        }
    }

    public View commit(String sessionId, String repairId, long draftRevision, String idempotencyKey) {
        boundedId(idempotencyKey);
        synchronized (store) {
            Draft draft = requireDraft(sessionId, repairId, draftRevision);
            if ("committed".equals(draft.state())) return view(draft);
            WikiStore.VersionedEntry target = requireUnchangedTarget(draft);
            WikiFactBuilder.WikiFacts facts = facts(target.entry());
            ensureFingerprint(draft, facts);
            if (!"valid".equals(draft.state())) throw new WikiRepairException("WIKI_REPAIR_VALIDATION_REQUIRED");
            WikiSummarizer.SummaryResult result = validateCandidate(draft, facts);
            Draft committed = store.commitRepair(copy(draft, draftRevision, draft.state(), draft.candidate(),
                    draft.statements(), List.of(), draft.fingerprint()), result, idempotencyKey);
            return view(committed);
        }
    }

    public void deleteSession(String sessionId) { boundedId(sessionId); store.deleteUncommittedRepairs(sessionId); }

    public String sanitizeUserText(String value) {
        return normalizeText(value, 4000, false);
    }

    private List<Statement> rebindStatements(List<Statement> statements, List<WikiEntry.TaskSegment> tasks, long revision) {
        List<Statement> rebound = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement.taskIndex() < 0 || statement.taskIndex() >= tasks.size())
                throw new WikiRepairException("WIKI_REPAIR_CONFIRMATION_CHANGED");
            WikiEntry.TaskSegment task = tasks.get(statement.taskIndex());
            if (!statement.text().equals(WikiEvidencePolicy.confirmedText(task.title(), task.summary(), privacy.get())))
                throw new WikiRepairException("WIKI_REPAIR_CONFIRMATION_CHANGED");
            rebound.add(new Statement(statement.id(), statement.targetId(), statement.sourceId(), statement.text(),
                    statement.taskIndex(), revision, statement.confirmedAt()));
        }
        return List.copyOf(rebound);
    }

    private WikiSummarizer.SummaryResult validateCandidate(Draft draft, WikiFactBuilder.WikiFacts facts) {
        Candidate candidate = draft.candidate();
        if (candidate.summary().isBlank() || candidate.primaryTask().isBlank() || candidate.taskSegments().isEmpty())
            throw new IllegalArgumentException("WIKI_REPAIR_EMPTY:summary");
        if ((facts.activeSeconds() > 0 || !WikiEvidencePolicy.facts(facts.sampledTitles()).isEmpty())
                && (candidate.summary() + " " + candidate.primaryTask()).matches("(?is).*(?:没有可归纳的活跃活动|无活跃活动|无活动|no (?:active )?activity).*"))
            throw new IllegalArgumentException("WIKI_REPAIR_ACTIVITY_PLACEHOLDER:summary");
        List<WikiEvidencePolicy.UserStatement> statements = draft.statements().stream().map(statement ->
                new WikiEvidencePolicy.UserStatement(statement.id(), statement.targetId(), statement.sourceId(), statement.text(),
                        statement.taskIndex(), statement.draftRevision(), statement.confirmedAt())).toList();
        return WikiEvidencePolicy.validateRepair(candidate.summary(), candidate.primaryTask(), candidate.taskSegments(),
                facts, draft.targetId(), draft.draftRevision(), statements, privacy.get());
    }

    private Candidate normalize(Candidate candidate) {
        if (candidate == null || candidate.taskSegments().size() > WikiEvidencePolicy.MAX_SEGMENTS)
            throw new WikiRepairException("WIKI_REPAIR_STRUCTURE");
        List<WikiEntry.TaskSegment> tasks = new ArrayList<>();
        for (WikiEntry.TaskSegment task : candidate.taskSegments()) {
            if (task == null || task.evidenceFactIds().size() > WikiEvidencePolicy.MAX_REFERENCES)
                throw new WikiRepairException("WIKI_REPAIR_STRUCTURE");
            List<String> ids = task.evidenceFactIds().stream().map(id -> normalizeText(id, 160, false))
                    .filter(id -> !id.startsWith("user_statement:")).distinct().toList();
            String confidence = normalizeText(task.confidence(), 8, true);
            String claim = normalizeText(task.claimType(), 16, true);
            tasks.add(new WikiEntry.TaskSegment(normalizeText(task.title(), 80, true), normalizeText(task.summary(), 500, true),
                    List.of(), List.of(), confidence.isBlank() ? "medium" : confidence, ids, claim.isBlank() ? "observed" : claim));
        }
        return new Candidate(normalizeText(candidate.summary(), 1200, true), normalizeText(candidate.primaryTask(), 160, true), tasks);
    }

    private String normalizeText(String value, int limit, boolean allowEmpty) {
        if (value != null && value.length() > Math.max(32768, limit * 8)) throw new WikiRepairException("WIKI_REPAIR_TOO_LARGE");
        if (value != null && privacy.get().excludedTitle(value)) throw new WikiRepairException("WIKI_REPAIR_PRIVACY_EXCLUDED");
        String text = WikiEvidencePolicy.normalizeText(value == null ? "" : value, privacy.get());
        if (privacy.get().excludedTitle(text)) throw new WikiRepairException("WIKI_REPAIR_PRIVACY_EXCLUDED");
        if ((!allowEmpty && text.isBlank()) || text.codePointCount(0, text.length()) > limit)
            throw new WikiRepairException("WIKI_REPAIR_LENGTH");
        return text;
    }

    private Draft requireDraft(String sessionId, String repairId, Long expectedRevision) {
        boundedId(sessionId); boundedId(repairId);
        Draft draft = store.repairDraft(repairId);
        if (draft == null || !draft.sessionId().equals(sessionId)) throw new WikiRepairException("WIKI_REPAIR_NOT_FOUND");
        if (expectedRevision != null && draft.draftRevision() != expectedRevision) throw new WikiRepairException("WIKI_REPAIR_DRAFT_CONFLICT");
        return draft;
    }

    private Draft requireEditable(String sessionId, String repairId, long draftRevision) {
        Draft draft = requireDraft(sessionId, repairId, draftRevision);
        if ("committed".equals(draft.state())) throw new WikiRepairException("WIKI_REPAIR_ALREADY_COMMITTED");
        return draft;
    }

    private WikiStore.VersionedEntry requireTarget(String targetId) {
        WikiStore.VersionedEntry target = store.versionedEntry(targetId);
        if (target == null) throw new WikiRepairException("WIKI_REPAIR_TARGET_NOT_FOUND");
        if (!repairable(target.entry(), clock.instant())) throw new WikiRepairException("WIKI_REPAIR_TARGET_NOT_REPAIRABLE");
        return target;
    }

    private WikiStore.VersionedEntry requireUnchangedTarget(Draft draft) {
        WikiStore.VersionedEntry current = store.versionedEntry(draft.targetId());
        if (current == null || current.revision() != draft.targetRevision()) throw new WikiRepairException("WIKI_REPAIR_TARGET_CONFLICT");
        return requireTarget(draft.targetId());
    }

    private WikiFactBuilder.WikiFacts facts(WikiEntry entry) {
        WikiFactBuilder.WikiFacts facts = factsProvider.apply(entry);
        if (facts == null || !facts.period().start().equals(entry.periodStart()) || !facts.period().end().equals(entry.periodEnd())
                || facts.period().level() != entry.level() || !facts.period().timezone().equals(entry.timezone()))
            throw new WikiRepairException("WIKI_REPAIR_FACT_SCOPE");
        return facts;
    }

    private String fingerprint(WikiFactBuilder.WikiFacts facts) {
        try {
            // Configuration stamps must include privacy and generation settings. The current permitted evidence is hashed, never copied into audit.
            String content = JSON.writeValueAsString(facts) + "\n" + configurationStamp.get();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new WikiRepairException("WIKI_REPAIR_INPUT_UNAVAILABLE"); }
    }

    private void ensureFingerprint(Draft draft, WikiFactBuilder.WikiFacts facts) {
        if (!draft.fingerprint().equals(fingerprint(facts))) throw new WikiRepairException("WIKI_REPAIR_INPUT_CONFLICT");
    }

    private View view(Draft draft) {
        WikiStore.VersionedEntry target = store.versionedEntry(draft.targetId());
        if (target == null) throw new WikiRepairException("WIKI_REPAIR_TARGET_NOT_FOUND");
        if ("committed".equals(draft.state())) return view(draft, target, null);
        return view(draft, target, facts(target.entry()));
    }

    private View view(Draft draft, WikiStore.VersionedEntry target, WikiFactBuilder.WikiFacts facts) {
        String error = target.entry().lastError();
        String safeError = error != null && error.matches("WIKI_[A-Z0-9_]+(?::[A-Za-z.]+)?") ? error : "WIKI_QUALITY_FAILED";
        return new View(draft, target(target), facts == null ? List.of() : WikiEvidencePolicy.facts(facts.sampledTitles()), false,
                "committed".equals(draft.state()) ? "修订已保存，上级摘要将按正常调度重新检查。" : MISSING_TEXT + " " + safeError, safeCurrentEntry(target.entry()));
    }

    private static WikiEntry safeCurrentEntry(WikiEntry entry) {
        if (entry.status() != WikiStatus.SUMMARIZED) return null;
        Map<String, Object> extra = new LinkedHashMap<>(entry.metrics().extra() == null ? Map.of() : entry.metrics().extra());
        if (extra.containsKey("generationProgress")) extra.put("generationProgress", WikiGenerationProgress.from(entry));
        WikiEntry.WikiMetrics metrics = new WikiEntry.WikiMetrics(entry.metrics().activeSeconds(), entry.metrics().afkSeconds(),
                entry.metrics().switchCount(), entry.metrics().topApps(), extra);
        return new WikiEntry(entry.id(), entry.level(), entry.periodStart(), entry.periodEnd(), entry.timezone(), entry.status(),
                entry.summary(), entry.primaryTask(), entry.taskSegments(), metrics, entry.sourceEntryIds(), entry.model(), entry.promptVersion(),
                entry.retryCount(), null, null, entry.createdAt(), entry.updatedAt(), entry.summarizedAt(), entry.factBuilderVersion(),
                entry.projectorVersion(), entry.sourceCoverage(), entry.statisticsVersion(), entry.calendarVersion());
    }

    private static Target target(WikiStore.VersionedEntry snapshot) {
        WikiEntry entry = snapshot.entry();
        return new Target(entry.id(), entry.level(), entry.periodStart(), entry.periodEnd(), entry.timezone(), snapshot.revision());
    }

    private Draft copy(Draft old, long revision, String state, Candidate candidate, List<Statement> statements,
                       List<Issue> issues, String fingerprint) {
        return new Draft(old.id(), old.sessionId(), old.targetId(), old.targetRevision(), revision, state, candidate,
                statements, issues, fingerprint, old.createdAt(), clock.instant(), old.committedRevision());
    }

    private static Issue issue(IllegalArgumentException error) {
        String code = error.getMessage();
        if (code == null || !code.matches("WIKI_[A-Z0-9_]+(?::[A-Za-z0-9.\\[\\]]+)?")) return new Issue("candidate", "WIKI_REPAIR_INVALID");
        int separator = code.indexOf(':');
        return separator < 0 ? new Issue("candidate", code) : new Issue(code.substring(separator + 1), code.substring(0, separator));
    }

    private static void boundedId(String id) {
        if (id == null || id.isBlank() || id.length() > 160 || !id.matches("[A-Za-z0-9_.:-]+"))
            throw new WikiRepairException("WIKI_REPAIR_IDENTIFIER");
    }
}
