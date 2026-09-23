package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.statistics.ActivityStatistics;
import com.selfanalyst.wiki.WikiFactBuilder.WikiFacts;
import com.selfanalyst.wiki.WikiTitleSampler.Fact;
import com.selfanalyst.wiki.WikiTitleSampler.Selection;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit synthetic evaluation entry point. Ordinary tests never enable its HTTP transport. */
public final class WikiQualityEvaluation {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant START = Instant.parse("2026-09-21T04:00:00Z");
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final String BUILD_FINGERPRINT = buildFingerprint();

    private WikiQualityEvaluation() {}

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help) { usage(); return; }
            Api api = options.live ? Api.fromEnvironment() : null;
            Map<String, Object> report = evaluate(options, api);
            Path output = options.output.toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            JsonNode outputReport = JSON.valueToTree(report);
            if (api != null) outputReport = redactSecret(outputReport, api.key());
            String encoded = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(outputReport) + "\n";
            Files.writeString(output, encoded, StandardCharsets.UTF_8);
            System.out.println("Wiki synthetic evaluation report: " + output);
            System.out.println("Mode: " + (options.live ? "live" : "offline")
                    + "; records: " + ((List<?>) report.get("runs")).size()
                    + "; HTTP calls: " + report.get("totalCalls"));
        } catch (Exception error) {
            // Configuration and transport errors may contain endpoints or provider text.
            System.err.println("Wiki evaluation failed: " + errorCode(error));
            System.exit(2);
        }
    }

    private static Map<String, Object> evaluate(Options options, Api api) throws IOException {
        List<Map<String, Object>> runs = new ArrayList<>();
        AtomicInteger totalCalls = new AtomicInteger();
        int matchedCases = 0;
        for (JsonNode name : resource("index.json")) {
            JsonNode fixture = resource(name.asText());
            String caseId = fixture.get("id").asText();
            if (options.caseId != null && !options.caseId.equals(caseId)) continue;
            matchedCases++;
            Fixture input = Fixture.read(fixture);
            List<Integer> budgets = options.budgets.isEmpty()
                    ? List.of(options.fullHistory ? Math.max(1000, fixture.get("budgetChars").asInt())
                    : fixture.get("budgetChars").asInt()) : options.budgets;
            for (int budget : budgets) {
                for (String strategy : options.strategies()) {
                    Selection selection = "baseline".equals(strategy) ? prefix(input, budget)
                            : options.fullHistory ? input.complete() : WikiTitleSampler.sample(input.period(), input.statistics().activeEvents(),
                            input.windows(), input.contents(), budget);
                    WikiFacts facts = input.facts(selection);
                    for (int repeat = 1; repeat <= options.repeat; repeat++) {
                        runs.add(run(options, api, totalCalls, fixture, facts, strategy, budget, repeat));
                    }
                }
            }
        }
        if (matchedCases == 0) throw failure("EVAL_CASE_NOT_FOUND");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 2);
        report.put("mode", options.live ? "live-synthetic" : "offline-synthetic");
        report.put("model", api == null ? null : api.model());
        report.put("temperature", TEMPERATURE);
        report.put("transport", options.transport);
        report.put("formalConfiguration", "formal".equals(options.transport));
        report.put("sdkMaxAttempts", "formal".equals(options.transport) ? 1 : null);
        report.put("maxOutputTokens", options.outputLimit());
        report.put("responseFormat", "formal".equals(options.transport) ? null : "json_object");
        report.put("systemPromptPolicy", "formal".equals(options.transport) ? "production-plain-system" : "user-only-diagnostic");
        report.put("historicalComparisonWarning", "Earlier temporary three-day scripts used chat temperature 0.7; those runs are not formal plain 0.2 baselines.");
        report.put("repeat", options.repeat);
        report.put("strategies", options.strategies());
        report.put("inputMode", options.fullHistory ? "full-history-tree" : "controlled-sampling-comparison");
        report.put("factBuilderVersion", WikiFactBuilder.FACT_BUILDER_VERSION);
        report.put("buildFingerprint", BUILD_FINGERPRINT);
        report.put("promptVersion", new WikiSummarizer(prompt -> "").promptVersion());
        report.put("baselineDefinition", "Chronological grouped-fact prefix under the same prompt, evidence contract, model, and limits; not an exact historical implementation replay.");
        report.put("comparisonScope", options.fullHistory
                ? "Complete grouped facts enter the current production tree; this is not a controlled sampling-baseline comparison."
                : "Controlled fact-selection comparison; this does not measure full-history tree synthesis benefits.");
        report.put("qualityLimit", "Topic keyword hits are a lexical proxy. Valid references and accepted JSON do not prove claims true.");
        report.put("budgetMeaning", "Formal mode uses production prompt characters plus system overhead; diagnostic HTTP mode additionally checks serialized JSON characters.");
        report.put("providerDiagnostics", "Formal mode preserves SDK receipts and safe HTTP failure codes; finishReason and success HTTP status are unavailable, never inferred.");
        report.put("topicProtocolVersion", WikiTopicProtocol.VERSION);
        report.put("maxTotalCalls", options.maxTotalCalls);
        report.put("totalCalls", totalCalls.get());
        report.put("runs", runs);
        return report;
    }

    private static Map<String, Object> run(Options options, Api api, AtomicInteger totalCalls,
                                           JsonNode fixture, WikiFacts facts, String strategy, int budget, int repeat) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", fixture.get("id").asText());
        row.put("strategy", options.fullHistory ? "current-full-history-tree" : strategy);
        row.put("repeat", repeat);
        row.put("budgetChars", budget);
        row.put("requestChars", options.requestChars);
        row.put("maxCalls", options.maxCalls);
        row.put("timeoutSeconds", options.timeoutSeconds);
        row.put("temperature", TEMPERATURE);
        row.put("transport", options.transport);
        row.put("maxOutputTokens", options.outputLimit());
        row.put("inputSampling", facts.sampledTitles().coverage());
        row.put("inputTopicKeywordsProxy", topicHits(fixture, facts.sampledTitles().facts().stream()
                .map(Fact::title).toList().toString()));
        row.put("manualTruthfulnessReview", Map.of("status", "pending", "unsupportedAssertions", "",
                "missedImportantTopics", "", "reviewerNotes", ""));
        row.put("referenceValidation", "not-run");
        row.put("topicKeywordHitsProxy", null);
        row.put("summary", null);
        row.put("primaryTask", null);
        row.put("taskSegments", null);
        row.put("errorCode", null);
        row.put("validationField", null);
        row.put("unvalidatedCandidate", null);
        row.put("candidateCounts", null);
        if (!options.live) {
            row.put("status", "offline-no-model");
            row.put("calls", 0);
            row.put("elapsedMillis", 0);
            row.put("inputTokens", null);
            row.put("outputTokens", null);
            row.put("usageComplete", false);
            row.put("requests", List.of());
            return row;
        }
        EvaluationTransport transport = "formal".equals(options.transport)
                ? new FormalTransport(api, options, totalCalls) : new DiagnosticHttpTransport(api, options, totalCalls);
        row.put("requestConfiguration", transport.metadata());
        // Each repeat gets a fresh pipeline so cached intermediate outputs cannot bias comparisons.
        WikiSummaryPipeline pipeline = new WikiSummaryPipeline(() -> transport,
                new WikiSummaryPipeline.Limits(Math.max(1000, budget), options.requestChars, options.maxCalls));
        row.put("promptVersion", pipeline.promptVersion());
        long started = System.nanoTime();
        try {
            var result = pipeline.summarize(facts, Duration.ofSeconds(options.timeoutSeconds));
            row.put("status", "accepted");
            row.put("referenceValidation", "passed-production-validation");
            row.put("summary", result.summary());
            row.put("primaryTask", result.primaryTask());
            row.put("taskSegments", result.taskSegments());
            String narrative = result.summary() + "\n" + result.primaryTask() + "\n" + result.taskSegments().stream()
                    .map(task -> task.title() + "\n" + task.summary()).toList();
            // Evidence text is generated from input titles, so including it would inflate this proxy.
            row.put("topicKeywordHitsProxy", topicHits(fixture, narrative));
            row.put("generation", result.metrics().extra().get("generation"));
        } catch (Exception failure) {
            String code = errorCode(failure);
            row.put("status", code.startsWith("WIKI_RESPONSE_") || code.startsWith("WIKI_EVIDENCE_")
                    || code.startsWith("WIKI_NARRATIVE_") || code.startsWith("WIKI_UNSUPPORTED_")
                    || code.startsWith("WIKI_TOPIC_") ? "rejected" : "failed");
            row.put("referenceValidation", "not-established");
            row.put("errorCode", code);
            row.put("validationField", validationField(failure));
        } finally {
            // Pipeline cancellation can return before the request receipt is settled.
            boolean interrupted = Thread.interrupted();
            while (!pipeline.awaitIdle(Duration.ofSeconds(5))) interrupted |= Thread.interrupted();
            transport.closeEvaluation();
            if (interrupted) Thread.currentThread().interrupt();
        }
        row.put("elapsedMillis", Duration.ofNanos(System.nanoTime() - started).toMillis());
        List<Map<String, Object>> requests = transport.snapshots();
        row.put("calls", requests.stream().filter(request -> !Boolean.FALSE.equals(request.get("dispatched"))).count());
        row.put("requests", requests);
        Object candidate = requests.isEmpty() ? null : requests.getLast().get("unvalidatedCandidate");
        row.put("unvalidatedCandidate", candidate);
        row.put("candidateCounts", candidate instanceof Map<?, ?> fields ? fields.get("candidateCounts") : null);
        boolean inputKnown = !requests.isEmpty() && requests.stream().allMatch(r -> r.get("inputTokens") instanceof Number);
        boolean outputKnown = !requests.isEmpty() && requests.stream().allMatch(r -> r.get("outputTokens") instanceof Number);
        row.put("inputTokens", inputKnown ? requests.stream().mapToLong(r -> ((Number) r.get("inputTokens")).longValue()).sum() : null);
        row.put("outputTokens", outputKnown ? requests.stream().mapToLong(r -> ((Number) r.get("outputTokens")).longValue()).sum() : null);
        row.put("usageComplete", inputKnown && outputKnown);
        return row;
    }

    private static Map<String, Object> topicHits(JsonNode fixture, String text) {
        List<String> hit = new ArrayList<>(), missing = new ArrayList<>();
        for (JsonNode topic : fixture.path("expectations").path("topics")) {
            boolean found = false;
            for (JsonNode fragment : topic.path("titleFragments")) {
                if (text.contains(fragment.asText())) found = true;
            }
            (found ? hit : missing).add(topic.get("name").asText());
        }
        int count = hit.size() + missing.size();
        return Map.of("hits", hit, "missing", missing, "rate", count == 0 ? 1 : (double) hit.size() / count,
                "meaning", "lexical-proxy-only");
    }

    private static Selection prefix(Fixture input, int budget) {
        List<Fact> retained = new ArrayList<>();
        int used = 0;
        for (Fact fact : input.complete().facts()) {
            int cost = WikiTitleSampler.fromFacts(input.period(), List.of(fact), Map.of()).jsonLines().length();
            if (cost > budget - used) break;
            retained.add(fact);
            used += cost;
        }
        Map<String, Object> coverage = new LinkedHashMap<>(input.complete().coverage());
        coverage.put("budgetChars", budget);
        return WikiTitleSampler.fromFacts(input.period(), retained, coverage);
    }

    private record Fixture(WikiPeriod period, List<Event> windows, List<Event> contents,
                           ActivityStatistics.Result statistics, Selection complete) {
        private static Fixture read(JsonNode json) {
            WikiPeriod period = new WikiPeriod(WikiLevel.DAY, START, START.plusSeconds(json.get("periodSeconds").asLong()), "UTC");
            List<Event> windows = events(json.get("windows"), 1), contents = events(json.get("contents"), 10001);
            var statistics = ActivityStatistics.compute(windows, events(json.get("afk"), 20001), period.start(), period.end());
            return new Fixture(period, windows, contents, statistics,
                    WikiTitleSampler.sample(period, statistics.activeEvents(), windows, contents, Integer.MAX_VALUE));
        }

        private WikiFacts facts(Selection selection) {
            List<WikiEntry.AppDuration> apps = statistics.apps().entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(10)
                    .map(entry -> new WikiEntry.AppDuration(entry.getKey(), entry.getValue().longValue())).toList();
            Map<String, WikiEntry.SourceCoverage> coverage = new LinkedHashMap<>();
            for (String source : List.of("window", "afk", "content")) coverage.put(source,
                    new WikiEntry.SourceCoverage("afk".equals(source) && statistics.estimated() ? "partial" : "complete",
                            period.start(), period.end(), 0L));
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("unknownActivitySeconds", statistics.unknownSeconds());
            extra.put("uncoveredSeconds", statistics.uncoveredSeconds());
            extra.put("conflictSeconds", statistics.conflictSeconds());
            extra.put("activeSecondsExact", statistics.activeSeconds());
            extra.put("afkSecondsExact", statistics.afkSeconds());
            extra.put("appSecondsExact", statistics.apps());
            extra.put("titleSampling", selection.coverage());
            return new WikiFacts(period, (long) statistics.activeSeconds(), (long) statistics.afkSeconds(),
                    statistics.switchCount(), apps, List.of(), List.of(), List.of(), WikiFactBuilder.FACT_BUILDER_VERSION,
                    "synthetic-evaluation-v1", coverage, extra, selection);
        }
    }

    private static List<Event> events(JsonNode rows, long nextId) {
        List<Event> result = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (String key : List.of("app", "title", "status")) if (row.has(key)) data.put(key, row.get(key).asText());
            if (row.has("contextTitle")) data.put("context_title", row.get("contextTitle").asText());
            if (row.has("contextKind")) data.put("context_kind", row.get("contextKind").asText());
            result.add(new Event(nextId++, START.plusNanos(Math.round(row.get("offsetSeconds").asDouble() * 1e9)),
                    row.get("durationSeconds").asDouble(), data));
        }
        return result;
    }

    private record Api(URI endpoint, String key, String model) {
        private static Api fromEnvironment() {
            String key = requiredEnvironment("WIKI_EVAL_API_KEY"), base = requiredEnvironment("WIKI_EVAL_BASE_URL");
            String model = requiredEnvironment("WIKI_EVAL_MODEL");
            URI uri;
            String normalized = base.replaceAll("/+$", "");
            try { uri = URI.create(normalized + (normalized.endsWith("/chat/completions") ? "" : "/chat/completions")); }
            catch (IllegalArgumentException error) { throw failure("EVAL_INVALID_ENDPOINT"); }
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw failure("EVAL_INVALID_ENDPOINT");
            if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")) throw failure("EVAL_INVALID_MODEL");
            return new Api(uri, key, model);
        }
    }

    private interface EvaluationTransport extends WikiSummaryPipeline.Session {
        List<Map<String, Object>> snapshots();
        Map<String, Object> metadata();
        default void closeEvaluation() {}
        // The pipeline's lease ends before a cancelled request necessarily settles. The owner
        // invokes closeEvaluation only after awaitIdle, so resources stay valid for receipts.
        @Override default void close() {}
    }

    private static final class FormalTransport implements EvaluationTransport {
        private final WikiSummaryPipeline.Session delegate;
        private final Map<String, Object> metadata;
        private final Options options;
        private final AtomicInteger totalCalls;
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        @SuppressWarnings("unchecked")
        private FormalTransport(Api api, Options options, AtomicInteger totalCalls) {
            this.options = options;
            this.totalCalls = totalCalls;
            WikiSummaryPipeline.Session opened = null;
            try {
                Path parent = options.output.toAbsolutePath().normalize().getParent();
                Files.createDirectories(parent);
                Path isolated = Files.createTempDirectory(parent, "wiki-eval-isolated-");
                Class<?> type = Class.forName("com.selfanalyst.agent.WikiFormalEvaluationSession");
                String base = api.endpoint().toString().replaceFirst("/chat/completions$", "");
                opened = (WikiSummaryPipeline.Session) type.getConstructor(String.class, String.class, String.class, Path.class)
                        .newInstance(api.key(), base, api.model(), isolated);
                delegate = opened;
                metadata = (Map<String, Object>) type.getMethod("metadata").invoke(delegate);
            } catch (ReflectiveOperationException | IOException error) {
                if (opened != null) opened.close();
                throw failure("EVAL_FORMAL_RUNTIME_UNAVAILABLE");
            }
        }

        @Override public void preflight() {
            delegate.preflight();
            if (totalCalls.get() >= options.maxTotalCalls)
                throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.GLOBAL_BUDGET,
                        "EVAL_TOTAL_CALL_BUDGET", true);
        }
        @Override public String identity() { return delegate.identity(); }
        @Override public String configurationRevision() { return delegate.configurationRevision(); }
        @Override public int overheadChars() { return delegate.overheadChars(); }
        @Override public long estimateInputTokens(String prompt) { return delegate.estimateInputTokens(prompt); }
        @Override public String complete(String prompt, Duration timeout) { return completeDetailed(prompt, timeout).text(); }
        @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
            preflight();
            admitCall(options, totalCalls);
            Call call = new Call(prompt.length() + overheadChars());
            calls.add(call);
            long started = System.nanoTime();
            try {
                var result = delegate.completeDetailed(prompt, timeout);
                call.inputTokens = result.inputTokens();
                call.outputTokens = result.outputTokens();
                call.unvalidatedCandidate = candidateForReview(result.text());
                call.status = "received";
                return result;
            } catch (WikiSummaryPipeline.CallFailure error) {
                call.inputTokens = error.inputTokens();
                call.outputTokens = error.outputTokens();
                call.status = error.code();
                call.dispatched = !error.beforeSend();
                if (error.beforeSend()) totalCalls.decrementAndGet();
                var status = java.util.regex.Pattern.compile("WIKI_MODEL_HTTP_([0-9]{3})").matcher(error.code());
                if (status.matches()) call.httpStatus = Integer.parseInt(status.group(1));
                throw error;
            } finally {
                call.elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            }
        }
        @Override public List<Map<String, Object>> snapshots() { return calls.stream().map(Call::snapshot).toList(); }
        @Override public Map<String, Object> metadata() { return metadata; }
        @Override public void closeEvaluation() { delegate.close(); }
    }

    private static void admitCall(Options options, AtomicInteger totalCalls) {
        while (true) {
            int previous = totalCalls.get();
            if (previous >= options.maxTotalCalls)
                throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.GLOBAL_BUDGET,
                        "EVAL_TOTAL_CALL_BUDGET", true);
            if (totalCalls.compareAndSet(previous, previous + 1)) return;
        }
    }

    private static final class DiagnosticHttpTransport implements EvaluationTransport {
        private final Api api;
        private final Options options;
        private final AtomicInteger totalCalls;
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();

        private DiagnosticHttpTransport(Api api, Options options, AtomicInteger totalCalls) {
            this.api = api; this.options = options; this.totalCalls = totalCalls;
        }

        @Override public String identity() { return "quality-diagnostic-http:" + api.model(); }
        @Override public WikiSummaryPipeline.Completion completeDetailed(String prompt, Duration timeout) {
            try {
                String text = complete(prompt, timeout);
                Call call = calls.getLast();
                return new WikiSummaryPipeline.Completion(text, call.inputTokens, call.outputTokens);
            } catch (WikiSummaryPipeline.CallFailure failure) {
                throw failure;
            } catch (RuntimeException error) {
                Call call = calls.isEmpty() ? null : calls.getLast();
                throw new WikiSummaryPipeline.CallFailure(WikiSummaryPipeline.FailureKind.TRANSPORT, errorCode(error),
                        call == null, call == null ? null : call.inputTokens, call == null ? null : call.outputTokens);
            }
        }
        @Override public Map<String, Object> metadata() {
            return Map.of("transport", "diagnostic-http", "formalConfiguration", false,
                    "temperature", TEMPERATURE, "maxOutputTokens", options.outputLimit(),
                    "responseFormat", "json_object", "systemPrompt", "absent");
        }

        @Override public String complete(String prompt, Duration timeout) {
            String body;
            try {
                body = JSON.writeValueAsString(Map.of("model", api.model(), "temperature", TEMPERATURE,
                        "max_tokens", options.outputLimit(), "stream", false, "response_format", Map.of("type", "json_object"),
                        "messages", List.of(Map.of("role", "user", "content", prompt))));
            } catch (IOException error) { throw failure("EVAL_REQUEST_ENCODING"); }
            if (body.length() > options.requestChars) throw failure("EVAL_REQUEST_BUDGET");
            admitCall(options, totalCalls);
            Call call = new Call(body.length());
            calls.add(call);
            long started = System.nanoTime();
            try {
                var request = HttpRequest.newBuilder(api.endpoint()).timeout(timeout)
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + api.key())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                call.httpStatus = response.statusCode();
                try (var stream = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        throw failure("EVAL_HTTP_" + response.statusCode());
                    byte[] payload = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (payload.length > MAX_RESPONSE_BYTES) throw failure("EVAL_RESPONSE_TOO_LARGE");
                    JsonNode envelope = JSON.readTree(payload);
                    call.inputTokens = tokenCount(envelope.path("usage"), "prompt_tokens", "input_tokens");
                    call.outputTokens = tokenCount(envelope.path("usage"), "completion_tokens", "output_tokens");
                    JsonNode choice = envelope.path("choices").path(0);
                    call.finishReason = finishReason(choice.path("finish_reason"));
                    JsonNode content = choice.path("message").path("content");
                    if (content.isTextual()) call.unvalidatedCandidate = candidateForReview(content.asText());
                    if ("length".equals(call.finishReason)) throw failure("EVAL_OUTPUT_TRUNCATED");
                    if (!content.isTextual() || content.asText().isBlank()) throw failure("EVAL_EMPTY_RESPONSE");
                    call.status = "received";
                    return content.asText();
                }
            } catch (java.net.http.HttpTimeoutException error) {
                call.status = "EVAL_HTTP_TIMEOUT"; throw failure(call.status);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); call.status = "EVAL_INTERRUPTED"; throw failure(call.status);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                call.status = "EVAL_PROVIDER_JSON"; throw failure(call.status);
            } catch (IOException error) {
                call.status = "EVAL_HTTP_IO"; throw failure(call.status);
            } catch (RuntimeException error) {
                call.status = errorCode(error); throw failure(call.status);
            } finally {
                call.elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            }
        }

        @Override public List<Map<String, Object>> snapshots() { return calls.stream().map(Call::snapshot).toList(); }
    }

    private static final class Call {
        private final int requestChars;
        private volatile Integer httpStatus;
        private volatile Long inputTokens, outputTokens;
        private volatile long elapsedMillis;
        private volatile String status = "in-flight";
        private volatile String finishReason;
        private volatile Map<String, Object> unvalidatedCandidate;
        private volatile boolean dispatched = true;
        private Call(int requestChars) { this.requestChars = requestChars; }
        private Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status); result.put("httpStatus", httpStatus); result.put("requestChars", requestChars);
            result.put("dispatched", dispatched);
            result.put("inputTokens", inputTokens); result.put("outputTokens", outputTokens); result.put("elapsedMillis", elapsedMillis);
            result.put("finishReason", finishReason); result.put("unvalidatedCandidate", unvalidatedCandidate);
            return result;
        }
    }

    private static String finishReason(JsonNode value) {
        if (!value.isTextual()) return null;
        return Set.of("stop", "length", "content_filter", "tool_calls", "function_call").contains(value.asText())
                ? value.asText() : "other";
    }

    /** Diagnostic output is synthetic, bounded, unvalidated, and restricted to narrative fields. */
    private static Map<String, Object> candidateForReview(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("validationStatus", "unvalidated");
        result.put("manualReviewRequired", true);
        if (content.length() > 65536) {
            result.put("unavailableReason", "candidate-too-large");
            return result;
        }
        try {
            String json = content.strip();
            if (json.startsWith("```")) json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("```\\s*$", "");
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) throw failure("EVAL_CANDIDATE_STRUCTURE");
            copyText(root, result, "summary", 1200);
            copyText(root, result, "primaryTask", 1200);
            List<Map<String, Object>> tasks = new ArrayList<>();
            List<Map<String, Object>> taskCounts = new ArrayList<>();
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("taskSegments", root.path("taskSegments").isArray() ? root.get("taskSegments").size() : null);
            counts.put("topicCards", root.path("topicCards").isArray() ? root.get("topicCards").size() : null);
            String taskField = root.path("topicCards").isArray() ? "topicCards" : "taskSegments";
            if (root.path(taskField).isArray()) {
                int index = 0;
                for (JsonNode task : root.get(taskField)) {
                    if (index >= 24) break;
                    Map<String, Object> taskCount = new LinkedHashMap<>();
                    taskCount.put("index", index++);
                    for (String field : List.of("apps", "evidenceFactIds", "evidence",
                            "memberInputIds", "representativeFactIds", "sourceTopicIds")) {
                        taskCount.put(field, task.path(field).isArray() ? task.get(field).size() : null);
                    }
                    taskCounts.add(taskCount);
                    if (!task.isObject()) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    copyText(task, item, "title", 1200);
                    copyText(task, item, "summary", 1200);
                    copyText(task, item, "claimType", 24);
                    copyText(task, item, "confidence", 24);
                    copyTextArray(task, item, "apps", 160);
                    copyTextArray(task, item, "evidenceFactIds", 64);
                    copyTextArray(task, item, "memberInputIds", 64);
                    copyTextArray(task, item, "representativeFactIds", 64);
                    copyTextArray(task, item, "sourceTopicIds", 64);
                    tasks.add(item);
                }
            }
            result.put(taskField, tasks);
            result.put("membershipDiagnostics", "Member and source IDs are bounded samples; original counts are separate from displayed evidence and do not prove semantic completeness.");
            counts.put("tasks", taskCounts);
            result.put("candidateCounts", counts);
        } catch (IOException | IllegalArgumentException error) {
            result.put("unavailableReason", "candidate-json-invalid");
        }
        return result;
    }

    private static void copyText(JsonNode source, Map<String, Object> target, String field, int limit) {
        if (source.path(field).isTextual()) target.put(field, bounded(source.get(field).asText(), limit));
    }

    private static void copyTextArray(JsonNode source, Map<String, Object> target, String field, int limit) {
        if (!source.path(field).isArray()) return;
        List<String> values = new ArrayList<>();
        for (JsonNode value : source.get(field)) {
            if (values.size() >= 16) break;
            if (value.isTextual()) values.add(bounded(value.asText(), limit));
        }
        target.put(field, values);
    }

    private static String bounded(String text, int codePoints) {
        return text.codePointCount(0, text.length()) <= codePoints ? text
                : text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    private static Long tokenCount(JsonNode usage, String primary, String alternative) {
        JsonNode value = usage.has(primary) ? usage.get(primary) : usage.get(alternative);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : null;
    }

    private static JsonNode resource(String name) throws IOException {
        try (var stream = WikiQualityEvaluation.class.getResourceAsStream("/wiki-quality/" + name)) {
            if (stream == null) throw failure("EVAL_MISSING_FIXTURE");
            return JSON.readTree(stream);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw failure("EVAL_MISSING_ENVIRONMENT");
        return value.strip();
    }

    private static IllegalArgumentException failure(String code) { return new IllegalArgumentException(code); }

    private static String errorCode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String prefix = message.split(":", 2)[0];
            if (prefix.matches("(?:WIKI|EVAL)_[A-Z0-9_]{1,80}")) return prefix;
        }
        return "EVAL_UNEXPECTED_FAILURE";
    }

    /** Only stable production-validator paths are diagnostic data; arbitrary suffixes stay private. */
    private static String validationField(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String[] parts = message.split(":", 2);
            if (parts.length == 2 && parts[0].matches("WIKI_(?:EVIDENCE|NARRATIVE|RESPONSE|TOPIC)_[A-Z0-9_]{1,80}")
                    && parts[1].matches("(?:summary|primaryTask|input|response|root|(?:taskSegments|topicCards)(?:\\[\\d{1,3}\\])?"
                    + "(?:\\.(?:title|summary|apps|evidenceFactIds|evidence|claimType|confidence|memberInputIds|representativeFactIds|sourceTopicIds)(?:\\[\\d{1,3}\\])?)?)")) {
                return parts[1];
            }
        }
        return null;
    }

    /** Redact before JSON encoding so escaped credentials cannot evade a literal replacement. */
    private static JsonNode redactSecret(JsonNode node, String secret) {
        if (node.isTextual()) return JSON.getNodeFactory().textNode(node.asText().replace(secret, "[REDACTED]"));
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var fields = object.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                object.set(field.getKey(), redactSecret(field.getValue(), secret));
            }
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode array) {
            for (int i = 0; i < array.size(); i++) array.set(i, redactSecret(array.get(i), secret));
        }
        return node;
    }

    private static final class Options {
        private boolean live, baseline, current, help, fullHistory;
        private int repeat = 3, requestChars = 32000, maxCalls = 6, maxTotalCalls = 300, timeoutSeconds = 60, maxOutputTokens;
        private String transport = "formal";
        private List<Integer> budgets = List.of();
        private String caseId;
        private Path output = Path.of("target", "wiki-quality-evaluation.json");

        private List<String> strategies() {
            if (baseline && current) return List.of("baseline", "current");
            return baseline ? List.of("baseline") : List.of("current");
        }

        private Integer outputLimit() {
            return "formal".equals(transport) ? null : maxOutputTokens == 0 ? 4096 : maxOutputTokens;
        }

        private static Options parse(String[] args) {
            Options result = new Options();
            try {
                for (int i = 0; i < args.length; i++) {
                    switch (args[i]) {
                        case "--live" -> result.live = true;
                        case "--transport" -> result.transport = args[++i];
                        case "--baseline" -> result.baseline = true;
                        case "--current" -> result.current = true;
                        case "--help" -> result.help = true;
                        case "--full-history" -> result.fullHistory = true;
                        case "--repeat" -> result.repeat = Integer.parseInt(args[++i]);
                        case "--request-chars" -> result.requestChars = Integer.parseInt(args[++i]);
                        case "--max-calls" -> result.maxCalls = Integer.parseInt(args[++i]);
                        case "--max-total-calls" -> result.maxTotalCalls = Integer.parseInt(args[++i]);
                        case "--max-output-tokens" -> result.maxOutputTokens = Integer.parseInt(args[++i]);
                        case "--timeout-seconds" -> result.timeoutSeconds = Integer.parseInt(args[++i]);
                        case "--budgets" -> result.budgets = Arrays.stream(args[++i].split(",")).map(String::strip).map(Integer::parseInt).distinct().toList();
                        case "--case" -> result.caseId = args[++i];
                        case "--output" -> result.output = Path.of(args[++i]);
                        default -> throw failure("EVAL_INVALID_ARGUMENTS");
                    }
                }
            } catch (IndexOutOfBoundsException | NumberFormatException error) { throw failure("EVAL_INVALID_ARGUMENTS"); }
            if (result.repeat < 1 || result.repeat > 100 || result.requestChars < 4000 || result.requestChars > 1_000_000
                    || result.maxCalls < 1 || result.maxCalls > 16 || result.maxTotalCalls < 1 || result.maxTotalCalls > 10000
                    || !Set.of("formal", "diagnostic-http").contains(result.transport)
                    || result.maxOutputTokens < 0 || result.maxOutputTokens > 32768
                    || ("formal".equals(result.transport) && result.maxOutputTokens != 0)
                    || result.timeoutSeconds < 1 || result.timeoutSeconds > 3600 || result.budgets.size() > 20
                    || (result.fullHistory && (result.baseline || result.budgets.stream().anyMatch(b -> b < 1000)))
                    || result.budgets.stream().anyMatch(b -> b < 1 || b > 1_000_000)) throw failure("EVAL_INVALID_ARGUMENTS");
            return result;
        }
    }

    private static void usage() {
        System.out.println("WikiQualityEvaluation [--live] [--baseline] [--current] [--repeat 3] [--budgets 1000,2400]");
        System.out.println("  [--case fixture-id] [--request-chars 32000] [--max-calls 6] [--max-total-calls 300]");
        System.out.println("  [--timeout-seconds 60] [--output report.json]");
        System.out.println("  [--transport formal|diagnostic-http] (formal uses the app PlainTask; no provider output limit)");
        System.out.println("  [--max-output-tokens 4096] (diagnostic-http only; not a production-quality baseline)");
        System.out.println("  [--full-history] (current only; complete facts enter the production tree, minimum fact budget 1000)");
        System.out.println("Default: offline, current sampling, fixture budgets, three repeats; no model or user database access.");
        System.out.println("Live environment: WIKI_EVAL_API_KEY, WIKI_EVAL_BASE_URL (including /v1), WIKI_EVAL_MODEL.");
    }

    private static String buildFingerprint() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (Class<?> type : List.of(WikiTitleSampler.class, WikiSummarizer.class,
                    WikiEvidencePolicy.class, WikiNarrativePolicy.class, WikiSummaryPipeline.class,
                    WikiTopicPlanner.class, WikiTopicProtocol.class)) {
                try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                    if (input == null) throw new IllegalStateException("EVAL_BUILD_FINGERPRINT");
                    digest.update(input.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("EVAL_BUILD_FINGERPRINT");
        }
    }
}
