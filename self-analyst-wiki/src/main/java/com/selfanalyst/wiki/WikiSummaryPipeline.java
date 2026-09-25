package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.wiki.WikiFactBuilder.WikiFacts;
import com.selfanalyst.wiki.WikiTitleSampler.Fact;
import com.selfanalyst.wiki.WikiTopicProtocol.TopicCard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Budgeted metadata-only synthesis. One lease fixes the model across an entire tree. */
public final class WikiSummaryPipeline extends WikiSummarizer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String VERSION = "wiki-tree-v5-focus";
    private static final int CHECKPOINT_SCHEMA = 2;

    /** Null usage means that the durable reservation must be retained conservatively. */
    public record Completion(String text, Long inputTokens, Long outputTokens) {
        public Completion {
            if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0)
                throw new IllegalArgumentException("WIKI_INVALID_USAGE");
        }
    }

    public enum FailureKind {
        CONFIGURATION, GLOBAL_BUDGET, PERIOD_BUDGET, TRANSPORT, TIMEOUT, QUALITY, INPUT, LOCAL_STORAGE, INTERRUPTED
    }

    /** Safe classification only: never attach a provider exception, request or response. */
    public static final class CallFailure extends IllegalStateException {
        private final FailureKind kind;
        private final boolean beforeSend;
        private final Long inputTokens;
        private final Long outputTokens;

        public CallFailure(FailureKind kind, String code, boolean beforeSend) {
            this(kind, code, beforeSend, null, null);
        }

        public CallFailure(FailureKind kind, String code, boolean beforeSend, Long inputTokens, Long outputTokens) {
            super(safeCode(code));
            this.kind = Objects.requireNonNull(kind);
            this.beforeSend = beforeSend;
            this.inputTokens = inputTokens != null && inputTokens >= 0 ? inputTokens : null;
            this.outputTokens = outputTokens != null && outputTokens >= 0 ? outputTokens : null;
        }

        public FailureKind kind() { return kind; }
        public String code() { return getMessage(); }
        public boolean beforeSend() { return beforeSend; }
        public Long inputTokens() { return inputTokens; }
        public Long outputTokens() { return outputTokens; }

        private static String safeCode(String code) {
            return code != null && code.matches("[A-Za-z0-9_:.-]{1,120}") ? code : "WIKI_MODEL_FAILURE";
        }
    }

    public interface Session extends AutoCloseable {
        String complete(String prompt, Duration timeout);
        String identity();
        default String configurationRevision() { return identity(); }
        default Completion completeDetailed(String prompt, Duration timeout) {
            return new Completion(complete(prompt, timeout), null, null);
        }
        default void preflight() {}
        default int overheadChars() { return 512; }
        default long estimateInputTokens(String prompt) {
            return Math.max(1, ((long) prompt.length() + overheadChars() + 2) / 3);
        }
        @Override default void close() {}
    }

    public record Limits(int factChars, int requestChars, int maxCalls) {
        public Limits {
            if (factChars < 1000 || requestChars < 4000 || maxCalls < 1 || maxCalls > 16)
                throw new IllegalArgumentException("Invalid Wiki synthesis limits");
        }
    }

    private record Strength(boolean inferred, int confidence) {
        Strength combine(Strength other) {
            return new Strength(inferred || other.inferred, Math.min(confidence, other.confidence));
        }
    }
    private final Supplier<Session> sessions;
    private final Limits limits;
    private final LongSupplier nanoTime;
    private final WikiGenerationStore generationStore;
    private final WikiGenerationStore.BudgetLimits budgetLimits;
    private final Set<CountDownLatch> inFlight = ConcurrentHashMap.newKeySet();

    public WikiSummaryPipeline(Supplier<Session> sessions, Limits limits) {
        this(sessions, limits, WikiGenerationStore.inMemory(), WikiGenerationStore.BudgetLimits.defaults());
    }

    public WikiSummaryPipeline(Supplier<Session> sessions, Limits limits, WikiGenerationStore generationStore,
                               WikiGenerationStore.BudgetLimits budgetLimits) {
        this(sessions, limits, generationStore, budgetLimits, System::nanoTime);
    }

    WikiSummaryPipeline(Supplier<Session> sessions, Limits limits, LongSupplier nanoTime) {
        this(sessions, limits, WikiGenerationStore.inMemory(), WikiGenerationStore.BudgetLimits.defaults(), nanoTime);
    }

    WikiSummaryPipeline(Supplier<Session> sessions, Limits limits, WikiGenerationStore generationStore,
                        WikiGenerationStore.BudgetLimits budgetLimits, LongSupplier nanoTime) {
        super(prompt -> { throw new IllegalStateException("Use a Wiki generation session"); });
        this.sessions = Objects.requireNonNull(sessions);
        this.limits = Objects.requireNonNull(limits);
        this.generationStore = Objects.requireNonNull(generationStore);
        this.budgetLimits = Objects.requireNonNull(budgetLimits);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public WikiGenerationStore.BudgetSnapshot progress(WikiPeriod period) {
        return generationStore.snapshot(WikiGenerationStore.periodKey(period));
    }

    public WikiGenerationStore.BudgetLimits budgetLimits() { return budgetLimits; }

    /** Used only to awaken configuration pauses, never as a checkpoint identity. */
    public String configurationStamp() {
        try (Session session = sessions.get()) {
            return hash(session.identity() + "|" + session.configurationRevision());
        }
    }

    public boolean canResume(WikiPeriod period) {
        return generationStore.canResume(WikiGenerationStore.periodKey(period), budgetLimits);
    }

    public void markPublished(SummaryResult result) {
        if (result.metrics().extra().get("generation") instanceof Map<?, ?> generation
                && generation.get("generationKey") instanceof String key) {
            generationStore.markPublished(key);
            generationStore.cleanupPublished(Duration.ofDays(7));
        }
    }

    /** Wait for cancelled request receipts before the owner closes the ledger. */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (CountDownLatch completion : List.copyOf(inFlight)) {
            try {
                if (!completion.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @Override public String promptVersion() { return super.promptVersion() + ":" + VERSION; }

    @Override public String buildPrompt(WikiFacts facts) {
        // Map.of iteration order can change across JVM restarts. The exact prompt is part
        // of the node identity, so render its metadata maps in a canonical order.
        WikiTitleSampler.Selection selection = facts.sampledTitles() == null ? null
                : new WikiTitleSampler.Selection(facts.sampledTitles().facts(), facts.sampledTitles().jsonLines(),
                        new TreeMap<>(facts.sampledTitles().coverage()));
        return super.buildPrompt(new WikiFacts(facts.period(), facts.activeSeconds(), facts.afkSeconds(),
                facts.switchCount(), facts.topApps(), facts.titleSamples(), facts.contextTitleSamples(),
                facts.childSummaries(), facts.factBuilderVersion(), facts.projectorVersion(),
                new TreeMap<>(facts.sourceCoverage()), new TreeMap<>(facts.statistics()), selection));
    }

    @Override public SummaryResult summarize(WikiFacts facts, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("WIKI_SYNTHESIS_TIMEOUT");
        long start = nanoTime.getAsLong();
        Run run = new Run(sessions.get(), timeout, start, facts);
        try {
            if (facts.sampledTitles() == null)
                return decorate(run.cachedCall(facts, "DIRECT", buildPrompt(facts), List.of()), facts, facts, run, 0, 0);
            run.plan = WikiTopicPlanner.plan(facts, limits, run.session.overheadChars(), run::checkDeadline);
            facts = canonicalFacts(facts);
            run.fullFacts = facts;
            if (run.plan.leaves().isEmpty() && !facts.sampledTitles().facts().isEmpty())
                throw new CallFailure(FailureKind.INPUT, "WIKI_FACT_TOO_LARGE", true);
            if (run.plan.leaves().size() <= 1) {
                var batch = run.plan.leaves().isEmpty() ? new WikiTopicPlanner.Batch(List.of(), "") : run.plan.leaves().getFirst();
                WikiFacts direct = input(facts, batch.facts(), facts.childSummaries(), run);
                batch.facts().forEach(f -> run.processedIds.add(f.id()));
                var result = run.cachedCall(direct, "DIRECT", WikiTopicProtocol.factPrompt(direct, "DIRECT", batch.projection()), List.of());
                return decorate(result, facts, direct, run, run.plan.omittedFactIds().size(), 0);
            }
            List<TopicCard> cards = new ArrayList<>();
            for (var batch : run.plan.leaves()) {
                run.checkDeadline();
                WikiFacts leaf = input(facts, batch.facts(), facts.childSummaries(), run);
                batch.facts().forEach(f -> run.processedIds.add(f.id()));
                var value = run.cachedCall(leaf, "LEAF", WikiTopicProtocol.factPrompt(leaf, "LEAF", batch.projection()), List.of());
                cards.addAll(WikiTopicProtocol.cards(value));
            }
            cards.sort(Comparator.comparing(TopicCard::id));
            String prompt = mergePrompt(facts, "FINAL", cards, run);
            while (prompt == null && run.nodes + 1 < limits.maxCalls() && cards.size() > 1) {
                List<TopicCard> group = fittingCards(facts, "MERGE", cards, run);
                if (group.size() < 2) break;
                WikiFacts mergedInput = cardFacts(facts, group, run);
                var merged = run.cachedCall(mergedInput, "MERGE", mergePrompt(facts, "MERGE", group, run), group);
                cards.removeAll(group); cards.addAll(WikiTopicProtocol.cards(merged));
                cards.sort(Comparator.comparing(TopicCard::id));
                prompt = mergePrompt(facts, "FINAL", cards, run);
            }
            if (prompt == null) {
                List<TopicCard> selected = fittingCards(facts, "FINAL", cards, run);
                for (TopicCard card : cards) if (!selected.contains(card)) {
                    run.omittedCards.add(card.id()); run.mergeOmittedIds.addAll(card.memberInputIds());
                }
                cards = selected;
                prompt = mergePrompt(facts, "FINAL", cards, run);
            }
            if (prompt == null) throw new CallFailure(FailureKind.INPUT, "WIKI_REQUEST_BUDGET", true);
            WikiFacts finalInput = cardFacts(facts, cards, run);
            var result = run.cachedCall(finalInput, "FINAL", prompt, cards);
            return decorate(result, facts, finalInput, run, run.plan.omittedFactIds().size(), run.mergeOmittedIds.size());
        } finally { run.releaseSession(); }
    }

    private final class Run {
        private final Session session;
        private final AtomicInteger sessionUsers = new AtomicInteger(1);
        private final long start;
        private final long timeoutNanos;
        private WikiFacts fullFacts;
        private final String periodKey;
        private String generationKey;
        private final Set<String> originalChildren;
        private final Set<String> consumedChildren = new HashSet<>();
        private final Map<String, Strength> strengths = new HashMap<>();
        private final Set<String> processedIds = new TreeSet<>();
        private final Set<String> omittedCards = new TreeSet<>();
        private final Set<String> unresolvedCards = new TreeSet<>();
        private final Set<String> mergeOmittedIds = new TreeSet<>();
        private final Map<String, TopicCard> cardCatalog = new LinkedHashMap<>();
        private WikiTopicPlanner.Plan plan;
        private int cardsCreated;
        private int cardsPresented;
        private int calls;
        private int nodes;
        private int cacheHits;
        private long inputChars;
        private Run(Session session, Duration timeout, long start, WikiFacts fullFacts) {
            this.session = session;
            this.start = start;
            this.fullFacts = fullFacts;
            periodKey = WikiGenerationStore.periodKey(fullFacts.period());
            timeoutNanos = timeout.toNanos();
            originalChildren = new HashSet<>(fullFacts.childSummaries());
        }

        private long checkDeadline() {
            if (Thread.currentThread().isInterrupted())
                throw new CallFailure(FailureKind.INTERRUPTED, "WIKI_SYNTHESIS_INTERRUPTED", false);
            long remaining = timeoutNanos - (nanoTime.getAsLong() - start);
            if (remaining <= 0) throw new CallFailure(FailureKind.TIMEOUT, "WIKI_SYNTHESIS_TIMEOUT", false);
            return remaining;
        }

        private void releaseSession() {
            if (sessionUsers.decrementAndGet() == 0) session.close();
        }

        private String generationKey() {
            checkDeadline();
            if (generationKey == null) {
                // Lazy so that planning can stop at its deadline before walking a large input.
                generationKey = hash(session.identity() + "|" + promptVersion() + "|" + CHECKPOINT_SCHEMA
                        + "|" + WikiTopicPlanner.VERSION + "|" + WikiTopicProtocol.VERSION
                        + "|" + limits + "|" + session.overheadChars() + "|" + fullFacts.factBuilderVersion()
                        + "|" + fullFacts.projectorVersion()
                        + "|" + com.selfanalyst.events.statistics.ActivityStatistics.VERSION
                        + "|" + com.selfanalyst.events.statistics.ActivityCalendar.VERSION
                        + "|" + JSON.valueToTree(fullFacts));
            }
            checkDeadline();
            return generationKey;
        }

        private SummaryResult cachedCall(WikiFacts facts, String stage, String prompt, List<TopicCard> sourceCards) {
            checkDeadline();
            if (prompt.length() + session.overheadChars() > limits.requestChars())
                throw new CallFailure(FailureKind.INPUT, "WIKI_REQUEST_BUDGET", true);
            if (nodes >= limits.maxCalls()) throw new CallFailure(FailureKind.INPUT, "WIKI_CALL_BUDGET", true);
            nodes++;
            String generation = generationKey();
            String nodeKey = hash(stage + "|" + prompt + "|" + JSON.valueToTree(facts));
            SummaryResult value;
            try {
                var checkpoint = generationStore.loadCheckpoint(generation, nodeKey);
                if (checkpoint.isPresent()) {
                    var stored = checkpoint.get();
                    try {
                        var payload = JSON.readTree(stored.payloadJson());
                        if (!stage.equals(stored.stage()) || !payload.path("schemaVersion").canConvertToInt()
                                || payload.path("schemaVersion").intValue() != CHECKPOINT_SCHEMA)
                            throw new IllegalArgumentException();
                        // The disk result is untrusted. Reparse against today's exact reference
                        // catalog, derive apps/evidence again, and restore all ancestor ceilings.
                        value = facts.sampledTitles() == null ? capStrength(parseResponse(stored.payloadJson(), facts))
                                : WikiTopicProtocol.parse(stored.payloadJson(), fullFacts, facts.sampledTitles().facts(), sourceCards, true);
                    } catch (Exception error) {
                        throw new CallFailure(FailureKind.LOCAL_STORAGE, "WIKI_CHECKPOINT_INVALID", true);
                    }
                    cacheHits++;
                } else {
                    value = call(facts, prompt, generation, nodeKey, stage, sourceCards);
                }
            } catch (CallFailure | WikiPeriodBudgetException error) {
                throw error;
            } catch (RuntimeException error) {
                throw new CallFailure(FailureKind.LOCAL_STORAGE, "WIKI_GENERATION_STORE_FAILURE", true);
            }
            consumedChildren.addAll(facts.childSummaries());
            rememberStrength(value);
            List<TopicCard> produced = WikiTopicProtocol.cards(value);
            produced.forEach(card -> cardCatalog.put(card.id(), card));
            if (stage.equals("LEAF") || stage.equals("MERGE")) cardsCreated += produced.size();
            cardsPresented += sourceCards.size();
            if (value.metrics().extra().get("unresolvedTopicIds") instanceof List<?> unresolved)
                unresolved.forEach(id -> unresolvedCards.add(id.toString()));
            checkDeadline();
            return value;
        }

        private SummaryResult call(WikiFacts facts, String prompt, String generation, String nodeKey, String stage,
                                   List<TopicCard> sourceCards) {
            checkDeadline();
            try { session.preflight(); }
            catch (RuntimeException error) { throw classify(error); }
            var reservation = generationStore.reserve(periodKey, generation, nodeKey,
                    session.estimateInputTokens(prompt), budgetLimits);
            long remaining;
            try { remaining = checkDeadline(); }
            catch (CallFailure error) {
                generationStore.cancelBeforeSend(reservation.callId());
                throw error;
            }
            calls++;
            inputChars += prompt.length() + session.overheadChars();
            CountDownLatch finished = new CountDownLatch(1);
            inFlight.add(finished);
            sessionUsers.incrementAndGet();
            FutureTask<SummaryResult> task = new FutureTask<>(() -> {
                Completion completion;
                try {
                    completion = session.completeDetailed(prompt, Duration.ofNanos(remaining));
                } catch (RuntimeException error) {
                    CallFailure failure = classify(error);
                    try {
                        if (failure.beforeSend()) generationStore.cancelBeforeSend(reservation.callId());
                        else generationStore.settle(reservation.callId(), failure.inputTokens(), failure.outputTokens());
                    } catch (RuntimeException storageError) {
                        throw new CallFailure(FailureKind.LOCAL_STORAGE, "WIKI_GENERATION_STORE_FAILURE", false);
                    }
                    throw failure;
                }
                SummaryResult result;
                try {
                    result = facts.sampledTitles() == null ? capStrength(parseResponse(completion == null ? null : completion.text(), facts))
                            : WikiTopicProtocol.parse(completion == null ? null : completion.text(), fullFacts,
                                    facts.sampledTitles().facts(), sourceCards, false);
                }
                catch (IllegalArgumentException error) {
                    generationStore.settle(reservation.callId(), completion == null ? null : completion.inputTokens(),
                            completion == null ? null : completion.outputTokens());
                    throw new CallFailure(FailureKind.QUALITY, error.getMessage(), false,
                            completion == null ? null : completion.inputTokens(),
                            completion == null ? null : completion.outputTokens());
                }
                var payload = JSON.createObjectNode();
                payload.put("schemaVersion", CHECKPOINT_SCHEMA);
                payload.put("summary", result.summary());
                payload.put("primaryTask", result.primaryTask());
                if (facts.sampledTitles() != null) {
                    payload.set("topicCards", JSON.valueToTree(WikiTopicProtocol.cards(result)));
                    payload.set("unresolvedInputIds", JSON.valueToTree(result.metrics().extra().get("unresolvedInputIds")));
                    payload.set("unresolvedTopicIds", JSON.valueToTree(result.metrics().extra().get("unresolvedTopicIds")));
                }
                if (facts.sampledTitles() == null) {
                    var segments = payload.putArray("taskSegments");
                    for (WikiEntry.TaskSegment segment : result.taskSegments()) {
                        var item = segments.addObject();
                        item.put("title", segment.title()); item.put("summary", segment.summary());
                        item.put("confidence", segment.confidence()); item.put("claimType", segment.claimType());
                        item.set("evidenceFactIds", JSON.valueToTree(segment.evidenceFactIds()));
                        item.set("apps", JSON.valueToTree(segment.apps()));
                        item.set("evidence", JSON.valueToTree(segment.evidence()));
                    }
                }
                // A validated result and its real usage commit together, including a late
                // response after cancellation. No later model call is started by this task.
                try {
                    generationStore.completeCheckpoint(reservation.callId(), generation, nodeKey, stage,
                            payload.toString(), completion.inputTokens(), completion.outputTokens());
                } catch (RuntimeException error) {
                    try { generationStore.settle(reservation.callId(), completion.inputTokens(), completion.outputTokens()); }
                    catch (RuntimeException ignored) { /* Existing reservation remains conservative. */ }
                    throw new CallFailure(FailureKind.LOCAL_STORAGE, "WIKI_GENERATION_STORE_FAILURE", false);
                }
                return result;
            });
            Thread.ofVirtual().name("wiki-synthesis-call").start(() -> {
                try { task.run(); }
                finally {
                    try { releaseSession(); }
                    finally { finished.countDown(); inFlight.remove(finished); }
                }
            });
            try {
                SummaryResult result = task.get(remaining, TimeUnit.NANOSECONDS);
                checkDeadline();
                return result;
            } catch (TimeoutException error) {
                task.cancel(true);
                generationStore.settle(reservation.callId(), null, null);
                throw new CallFailure(FailureKind.TIMEOUT, "WIKI_SYNTHESIS_TIMEOUT", false);
            } catch (InterruptedException error) {
                task.cancel(true); Thread.currentThread().interrupt();
                generationStore.settle(reservation.callId(), null, null);
                throw new CallFailure(FailureKind.INTERRUPTED, "WIKI_SYNTHESIS_INTERRUPTED", false);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof CallFailure failure) throw failure;
                throw new CallFailure(FailureKind.TRANSPORT, "WIKI_MODEL_FAILURE", false);
            }
        }

        private void rememberStrength(SummaryResult result) {
            for (WikiEntry.TaskSegment segment : result.taskSegments()) {
                Strength strength = new Strength(!"observed".equals(segment.claimType()), confidenceRank(segment.confidence()));
                for (String id : segment.evidenceFactIds()) strengths.merge(id, strength, Strength::combine);
            }
        }

        private SummaryResult capStrength(SummaryResult result) {
            List<WikiEntry.TaskSegment> segments = new ArrayList<>();
            for (WikiEntry.TaskSegment segment : result.taskSegments()) {
                if (segment.evidenceFactIds().isEmpty()) {
                    segments.add(segment);
                    continue;
                }
                Strength strength = new Strength(!"observed".equals(segment.claimType()), confidenceRank(segment.confidence()));
                for (String id : segment.evidenceFactIds()) {
                    Strength previous = strengths.get(id);
                    if (previous != null) strength = strength.combine(previous);
                }
                int confidence = strength.inferred() ? Math.min(1, strength.confidence()) : strength.confidence();
                segments.add(new WikiEntry.TaskSegment(segment.title(), segment.summary(), segment.evidence(), segment.apps(),
                        List.of("low", "medium", "high").get(confidence), segment.evidenceFactIds(),
                        strength.inferred() ? "inferred" : "observed"));
            }
            return new SummaryResult(result.summary(), result.primaryTask(), List.copyOf(segments), result.metrics());
        }
    }

    private WikiFacts canonicalFacts(WikiFacts facts) {
        List<Fact> ordered = facts.sampledTitles().facts().stream().sorted(Comparator.comparing(Fact::id)).toList();
        return facts.withInput(WikiTitleSampler.fromFacts(facts.period(), ordered, facts.sampledTitles().coverage()),
                facts.childSummaries().stream().sorted().toList());
    }

    private String mergePrompt(WikiFacts full, String stage, List<TopicCard> cards, Run run) {
        for (int compactness = 0; compactness < 2; compactness++) {
            run.checkDeadline();
            String prompt = WikiTopicProtocol.mergePrompt(full, stage, cards, compactness);
            if (prompt.length() + run.session.overheadChars() <= limits.requestChars()) return prompt;
        }
        return null;
    }

    private WikiFacts cardFacts(WikiFacts full, List<TopicCard> cards, Run run) {
        Set<String> ids = new HashSet<>(); cards.forEach(card -> ids.addAll(card.memberInputIds()));
        return input(full, full.sampledTitles().facts().stream().filter(fact -> ids.contains(fact.id())).toList(), List.of(), run);
    }

    /** Admit complete cards across source/time strata. Never truncate a card's narrative. */
    private List<TopicCard> fittingCards(WikiFacts full, String stage, List<TopicCard> cards, Run run) {
        Map<String, Fact> catalog = WikiTopicProtocol.catalog(full.sampledTitles().facts());
        Map<String, List<TopicCard>> strata = new TreeMap<>();
        for (TopicCard card : cards) {
            run.checkDeadline();
            Fact first = catalog.get(card.memberInputIds().getFirst());
            String key = WikiTopicPlanner.layer(full.period(), first) + "|" + first.source() + "|" + first.app();
            strata.computeIfAbsent(key, ignored -> new ArrayList<>()).add(card);
        }
        List<ArrayDeque<TopicCard>> queues = new ArrayList<>();
        for (List<TopicCard> stratum : strata.values()) {
            stratum.sort(Comparator.comparing(TopicCard::id));
            ArrayDeque<TopicCard> queue = new ArrayDeque<>();
            for (int index : spreadOrder(stratum.size(), run)) queue.add(stratum.get(index));
            queues.add(queue);
        }
        List<TopicCard> selected = new ArrayList<>();
        while (queues.stream().anyMatch(queue -> !queue.isEmpty())) {
            for (var queue : queues) if (!queue.isEmpty()) {
                run.checkDeadline();
                TopicCard card = queue.removeFirst();
                selected.add(card);
                if (mergePrompt(full, stage, selected, run) == null) selected.removeLast();
            }
        }
        return List.copyOf(selected);
    }

    private WikiFacts input(WikiFacts base, List<Fact> facts, List<String> children, Run run) {
        run.checkDeadline();
        Set<String> ids = new HashSet<>(); facts.forEach(f -> ids.add(f.id()));
        List<String> relevant = new ArrayList<>();
        for (String child : children) {
            run.checkDeadline();
            String text = restrictChild(child, ids);
            if (text != null) relevant.add(text);
        }
        Map<String, Object> coverage = new LinkedHashMap<>(base.sampledTitles().coverage());
        coverage.put("candidateFacts", base.sampledTitles().facts().size());
        coverage.put("omittedFacts", base.sampledTitles().facts().size() - facts.size());
        coverage.put("candidateChildSummaries", children.size());
        coverage.put("omittedChildSummaries", children.size() - relevant.size());
        WikiFacts result = base.withInput(WikiTitleSampler.fromFacts(base.period(), List.copyOf(facts), coverage), relevant);
        run.checkDeadline();
        return result;
    }

    private String restrictChild(String text, Set<String> ids) {
        try {
            var item = JSON.readTree(text);
            var refs = item.get("evidenceFactIds");
            if (refs == null || !refs.isArray()) return null;
            for (var ref : refs) if (!ids.contains(ref.asText())) return null;
            return text;
        } catch (Exception ignored) { return null; }
    }

    private SummaryResult decorate(SummaryResult result, WikiFacts full, WikiFacts finalInput,
                                   Run run, int omitted, int mergeOmitted) {
        run.checkDeadline();
        result = WikiSummaryFocus.apply(result, full);
        Map<String, Object> extra = new LinkedHashMap<>(result.metrics().extra());
        Object folded = extra.remove("foldedTopicCards");
        Object disclaimers = extra.remove("disclaimerClausesRemoved");
        int inputCount = full.sampledTitles() == null ? 0 : full.sampledTitles().facts().size();
        int availableCount = finalInput.sampledTitles() == null ? 0 : finalInput.sampledTitles().facts().size();
        long referenced = result.taskSegments().stream().flatMap(task -> task.evidenceFactIds().stream()).distinct().count();
        List<TopicCard> finalCards = WikiTopicProtocol.cards(result);
        Set<String> assigned = new TreeSet<>(); finalCards.forEach(card -> assigned.addAll(card.memberInputIds()));
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("mode", run.calls + run.cacheHits > 1 ? "tree" : "direct");
        generation.put("generationKey", run.generationKey());
        generation.put("modelIdentity", hash(run.session.identity()));
        generation.put("calls", run.calls);
        generation.put("cacheHits", run.cacheHits);
        generation.put("inputChars", run.inputChars);
        generation.put("inputFacts", inputCount);
        generation.put("processedFacts", inputCount - omitted);
        generation.put("omittedFacts", omitted);
        generation.put("assignedFacts", assigned.size());
        generation.put("unresolvedFacts", inputCount - omitted - assigned.size());
        generation.put("assignmentMeaning", "model_classification_not_semantic_validation");
        generation.put("topicCardsCreated", run.cardsCreated);
        generation.put("topicCardsPresented", run.cardsPresented);
        generation.put("omittedTopicCards", run.omittedCards.size());
        generation.put("unresolvedTopicCards", run.unresolvedCards.size());
        generation.put("finalTopicCards", finalCards.size());
        generation.put("foldedTopicCards", folded instanceof Number number ? number.intValue() : 0);
        generation.put("disclaimerClausesRemoved", disclaimers instanceof Number count ? count.intValue() : 0);
        generation.put("candidateGroups", run.plan == null ? 0 : run.plan.candidateGroups());
        generation.put("planFingerprint", run.plan == null ? "legacy" : run.plan.fingerprint());
        generation.put("reservedMergeCalls", run.plan == null ? 0 : run.plan.reservedMergeCalls());
        generation.put("intermediateUnreferencedFacts", Math.max(0, inputCount - omitted - availableCount - mergeOmitted));
        generation.put("mergeOmittedFacts", mergeOmitted);
        generation.put("finalAvailableFacts", availableCount);
        generation.put("finalReferencedFacts", referenced);
        generation.put("finalUnreferencedFacts", availableCount - referenced);
        generation.put("omittedChildSummaries", run.originalChildren.stream().filter(child -> !run.consumedChildren.contains(child)).count());
        generation.put("omittedIntermediateSummaries", (long) run.omittedCards.size() + run.unresolvedCards.size());
        generation.put("maxCalls", limits.maxCalls());
        generation.put("requestChars", limits.requestChars());
        var progress = generationStore.snapshot(run.periodKey);
        generation.put("periodCalls", progress.calls());
        generation.put("periodTokens", progress.tokens());
        generation.put("maxPeriodCalls", budgetLimits.maxCalls());
        generation.put("maxPeriodTokens", budgetLimits.maxTokens());
        extra.put("generation", generation);
        if (full.sampledTitles() != null) {
            extra.put("topicCardCatalog", List.copyOf(run.cardCatalog.values()));
            extra.put("topicFactCatalog", full.sampledTitles().facts().stream().filter(f -> run.processedIds.contains(f.id())).toList());
            extra.put("unresolvedInputIds", run.processedIds.stream().filter(id -> !assigned.contains(id)).toList());
            extra.put("omittedTopicIds", List.copyOf(run.omittedCards));
        }
        return new SummaryResult(result.summary(), result.primaryTask(), result.taskSegments(),
                new WikiEntry.WikiMetrics(full.activeSeconds(), full.afkSeconds(), full.switchCount(), full.topApps(), extra));
    }

    private static int confidenceRank(String value) {
        return "high".equals(value) ? 2 : "medium".equals(value) ? 1 : 0;
    }

    private static CallFailure classify(RuntimeException error) {
        if (error instanceof CallFailure failure) return failure;
        if (error instanceof com.selfanalyst.wiki.usage.BudgetExceededException)
            return new CallFailure(FailureKind.GLOBAL_BUDGET, "WIKI_GLOBAL_BUDGET", true);
        if (error instanceof com.selfanalyst.wiki.usage.LlmUnavailableException)
            return new CallFailure(FailureKind.CONFIGURATION, "WIKI_MODEL_UNAVAILABLE", true);
        return new CallFailure(FailureKind.TRANSPORT, "WIKI_MODEL_FAILURE", false);
    }

    private List<Integer> spreadOrder(int size, Run run) {
        if (size == 0) return List.of();
        List<Integer> order = new ArrayList<>();
        order.add(0);
        if (size == 1) return order;
        order.add(size - 1);
        ArrayDeque<int[]> ranges = new ArrayDeque<>();
        ranges.add(new int[]{1, size - 2});
        while (!ranges.isEmpty()) {
            run.checkDeadline();
            int[] range = ranges.removeFirst();
            if (range[0] > range[1]) continue;
            int middle = (range[0] + range[1]) >>> 1;
            order.add(middle);
            ranges.addLast(new int[]{range[0], middle - 1});
            ranges.addLast(new int[]{middle + 1, range[1]});
        }
        return order;
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
