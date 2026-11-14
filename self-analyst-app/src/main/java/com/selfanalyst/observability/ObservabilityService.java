package com.selfanalyst.observability;

import com.selfanalyst.integrations.*;

import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.trace.samplers.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Optional developer tracing. Disabled by default; grants and content never survive a restart. */
public final class ObservabilityService implements AutoCloseable {
    public static final Set<String> OPERATIONS =
            Set.of(
                    "selfanalyst.turn",
                    "agent.reason",
                    "model.generation",
                    "tool.call",
                    "summary.generate",
                    "context.compact",
                    "mcp.discover",
                    "mcp.delete");

    public record Content(
            String input,
            String output,
            String category,
            Set<String> toolSources,
            Set<String> mailAccounts,
            boolean mailDerived,
            boolean imageDerived,
            boolean unknownSources) {
        public Content {
            toolSources = toolSources == null ? Set.of() : Set.copyOf(toolSources);
            mailAccounts = mailAccounts == null ? Set.of() : Set.copyOf(mailAccounts);
        }
    }

    private record Grant(
            long epoch,
            ObservabilityConfig config,
            ObservabilityEndpointPolicy.ApprovedEndpoint endpoint,
            String authorization,
            String secret) {}

    private record Pending(long epoch, SpanData span) {}

    private final ObservabilityStore store;
    private final CredentialStore credentials;
    private final boolean persistentSupported;
    private final ObservabilityEndpointPolicy endpoints;
    private final ObservabilityTransport transport;
    private ObservabilityStore.Saved saved;
    private String storageError = "";
    private final Set<String> cleanup = new HashSet<>();

    private record Preview(
            long revision,
            long expires,
            String token,
            ObservabilityEndpointPolicy.ApprovedEndpoint endpoint) {}

    private Preview preview;
    private volatile Grant grant;
    private volatile boolean closed;
    private final AtomicLong epoch = new AtomicLong(),
            accepted = new AtomicLong(),
            dropped = new AtomicLong(),
            failed = new AtomicLong();
    private volatile String lastError = "";
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(2048);
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "observability-export");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<String, String> sessionAliases =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final SdkTracerProvider provider;
    private final Tracer tracer;

    public ObservabilityService(Path root) {
        this(
                root,
                new IntegrationCredentialStore(),
                com.sun.jna.Platform.isWindows(),
                new ObservabilityEndpointPolicy(),
                null);
    }

    public ObservabilityService(
            Path root,
            CredentialStore credentials,
            boolean persistentSupported,
            ObservabilityEndpointPolicy endpoints,
            ObservabilityTransport transport) {
        this.store = new ObservabilityStore(root);
        this.credentials = credentials;
        this.persistentSupported = persistentSupported;
        this.endpoints = endpoints;
        this.transport = transport == null ? new ObservabilityTransport(endpoints) : transport;
        try {
            saved = store.read();
        } catch (Exception invalid) {
            saved = new ObservabilityStore.Saved(ObservabilityConfig.defaults(), null);
            storageError = "config_unavailable";
        }
        SpanExporter exporter =
                new SpanExporter() {
                    @Override
                    public CompletableResultCode export(Collection<SpanData> spans) {
                        for (SpanData span : spans) {
                            Long version =
                                    span.getAttributes()
                                            .get(AttributeKey.longKey("selfanalyst.epoch"));
                            Grant live = grant;
                            if (live == null
                                    || version == null
                                    || version != live.epoch()
                                    || !queue.offer(new Pending(version, span)))
                                dropped.incrementAndGet();
                        }
                        return CompletableResultCode.ofSuccess();
                    }

                    @Override
                    public CompletableResultCode flush() {
                        return CompletableResultCode.ofSuccess();
                    }

                    @Override
                    public CompletableResultCode shutdown() {
                        queue.clear();
                        return CompletableResultCode.ofSuccess();
                    }
                };
        Sampler dynamic =
                new Sampler() {
                    @Override
                    public SamplingResult shouldSample(
                            Context parent,
                            String traceId,
                            String name,
                            SpanKind kind,
                            Attributes attrs,
                            List<io.opentelemetry.sdk.trace.data.LinkData> links) {
                        Grant current = grant;
                        return current == null
                                ? SamplingResult.drop()
                                : Sampler.traceIdRatioBased(current.config().sampleRate())
                                        .shouldSample(parent, traceId, name, kind, attrs, links);
                    }

                    @Override
                    public String getDescription() {
                        return "SelfAnalyst runtime consent sampler";
                    }
                };
        provider =
                SdkTracerProvider.builder()
                        .setResource(Resource.empty())
                        .setSampler(Sampler.parentBased(dynamic))
                        .setSpanLimits(
                                SpanLimits.builder()
                                        .setMaxNumberOfAttributes(32)
                                        .setMaxAttributeValueLength(16384)
                                        .build())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        tracer = provider.get("selfanalyst.observability");
        worker.scheduleWithFixedDelay(this::drainSafely, 1, 5, TimeUnit.SECONDS);
    }

    public synchronized Map<String, Object> snapshot() {
        refresh();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", saved.config());
        result.put("enabled", grant != null);
        result.put(
                "credentialConfigured",
                storageError.isEmpty()
                        && credentials.configured(saved.secretRef(), target(saved.config())));
        result.put("persistentCredentialsSupported", persistentSupported);
        result.put("contentExpiresOnRestart", true);
        result.put("credentialCleanupPending", !cleanup.isEmpty());
        result.put(
                "status",
                Map.of(
                        "state",
                        !storageError.isEmpty()
                                ? "unavailable"
                                : grant != null
                                        ? lastError.isEmpty() ? "enabled" : "error"
                                        : "disabled",
                        "accepted",
                        accepted.get(),
                        "dropped",
                        dropped.get(),
                        "failed",
                        failed.get(),
                        "lastError",
                        storageError.isEmpty() ? lastError : storageError));
        return result;
    }

    public synchronized void save(long expected, ObservabilityConfig config) throws IOException {
        check(expected);
        if (config.revision() != expected) throw new IOException("revision_conflict");
        revoke();
        String old = saved.secretRef();
        boolean sameTarget =
                saved.config().endpoint().equals(config.endpoint())
                        && saved.config().publicKey().equals(config.publicKey())
                        && saved.config().caCertificatePem().equals(config.caCertificatePem());
        saved =
                store.write(
                        expected,
                        new ObservabilityStore.Saved(
                                config.revision(expected + 1), sameTarget ? old : null));
        if (!sameTarget) clean(old);
    }

    public synchronized void credential(long expected, char[] secret) throws IOException {
        try {
            check(expected);
            if (!persistentSupported) throw new IOException("persistent_credentials_unavailable");
            if (saved.config().endpoint().isBlank() || saved.config().publicKey().isBlank())
                throw new IOException("incomplete_config");
            CredentialStore.validateSecret(secret);
            revoke();
            retryCleanup();
            String reference = credentials.write(target(saved.config()), secret, "windows"),
                    old = saved.secretRef();
            var candidate =
                    new ObservabilityStore.Saved(saved.config().revision(expected + 1), reference);
            try {
                saved = store.write(expected, candidate);
            } catch (IOException | RuntimeException failed) {
                ObservabilityStore.Saved current;
                try {
                    current = store.read();
                } catch (IOException reread) {
                    storageError = "config_unavailable";
                    throw new IOException("outcome_unknown");
                }
                if (current.equals(candidate)) saved = current;
                else {
                    clean(reference);
                    throw failed;
                }
            }
            clean(old);
        } finally {
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }

    public synchronized Map<String, Object> preview(long expected) throws IOException {
        check(expected);
        saved.config().validateActivation();
        var endpoint = endpoints.approve(saved.config());
        preview =
                new Preview(
                        expected,
                        System.nanoTime() + TimeUnit.MINUTES.toNanos(5),
                        UUID.randomUUID().toString(),
                        endpoint);
        String fingerprint = "system-trust";
        if (!endpoint.caPem().isBlank()) {
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                var certs =
                        java.security.cert.CertificateFactory.getInstance("X.509")
                                .generateCertificates(
                                        new ByteArrayInputStream(
                                                endpoint.caPem()
                                                        .getBytes(StandardCharsets.US_ASCII)));
                for (var cert : certs) digest.update(cert.getEncoded());
                fingerprint = java.util.HexFormat.of().formatHex(digest.digest());
            } catch (java.security.GeneralSecurityException failure) {
                throw new IOException("invalid_ca_certificate");
            }
        }
        return Map.of(
                "confirmationToken",
                preview.token(),
                "destination",
                Map.of(
                        "category",
                        endpoint.category(),
                        "addresses",
                        endpoint.addresses(),
                        "caFingerprint",
                        fingerprint,
                        "endpoint",
                        endpoint.uri().toASCIIString(),
                        "publicKey",
                        saved.config().publicKey()));
    }

    private ObservabilityEndpointPolicy.ApprovedEndpoint consumePreview(long expected, String token)
            throws IOException {
        Preview value = preview;
        if (value == null
                || value.revision() != expected
                || System.nanoTime() > value.expires()
                || !value.token().equals(token))
            throw new IOException("destination_confirmation_required");
        preview = null;
        return value.endpoint();
    }

    public synchronized void enable(long expected, int disclosureVersion, String confirmationToken)
            throws IOException {
        check(expected);
        if (disclosureVersion != 1) throw new IOException("disclosure_required");
        saved.config().validateActivation();
        if (!persistentSupported) throw new IOException("persistent_credentials_unavailable");
        var endpoint = consumePreview(expected, confirmationToken);
        revoke();
        grant = prepare(saved.config(), endpoint);
        lastError = "";
    }

    private Grant prepare(
            ObservabilityConfig config, ObservabilityEndpointPolicy.ApprovedEndpoint endpoint)
            throws IOException {
        char[] secret =
                credentials
                        .read(saved.secretRef(), target(config))
                        .orElseThrow(() -> new IOException("authentication_required"));
        try {
            String key = new String(secret);
            String auth =
                    "Basic "
                            + Base64.getEncoder()
                                    .encodeToString(
                                            (config.publicKey() + ":" + key)
                                                    .getBytes(StandardCharsets.UTF_8));
            return new Grant(epoch.get(), config, endpoint, auth, key);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    public synchronized void disable(long expected) throws IOException {
        revoke();
        check(expected);
        lastError = "";
    }

    public synchronized void disconnect(long expected) throws IOException {
        revoke();
        check(expected);
        String old = saved.secretRef();
        saved =
                store.write(
                        expected,
                        new ObservabilityStore.Saved(saved.config().revision(expected + 1), null));
        clean(old);
    }

    public boolean test(long expected, String confirmationToken) throws IOException {
        Grant test;
        synchronized (this) {
            check(expected);
            saved.config().validateActivation();
            if (!persistentSupported) throw new IOException("persistent_credentials_unavailable");
            test = prepare(saved.config(), consumePreview(expected, confirmationToken));
        }
        byte[] body = ObservabilityOtlp.synthetic();
        try {
            if (test.epoch() != epoch.get()) throw new IOException("cancelled");
            int status =
                    transport.send(
                            test.endpoint(),
                            test.authorization(),
                            body,
                            () -> test.epoch() == epoch.get() && !closed);
            if (status != 200)
                throw new IOException(
                        status == 401 || status == 403
                                ? "authentication_required"
                                : "receiver_rejected");
            return true;
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private void refresh() {
        if (!storageError.isEmpty()) return;
        try {
            var current = store.read();
            if (!current.equals(saved)) {
                revoke();
                saved = current;
            }
        } catch (IOException | RuntimeException invalid) {
            revoke();
            storageError = "config_unavailable";
        }
    }

    private void check(long expected) throws IOException {
        if (closed) throw new IOException("unavailable");
        if (!storageError.isEmpty()) throw new IOException(storageError);
        if (saved.config().revision() != expected || store.read().config().revision() != expected)
            throw new IOException("revision_conflict");
    }

    private static CredentialTarget target(ObservabilityConfig config) {
        return new CredentialTarget(
                "developer-langfuse",
                config.endpoint().isEmpty()
                        ? "unconfigured"
                        : config.endpoint() + " | project=" + config.publicKey());
    }

    private void clean(String reference) {
        if (reference == null) return;
        try {
            credentials.delete(reference);
            cleanup.remove(reference);
        } catch (Exception failure) {
            cleanup.add(reference);
        }
    }

    private void retryCleanup() {
        for (String reference : Set.copyOf(cleanup)) clean(reference);
    }

    /** Any source identity change invalidates consent before its persistence is attempted. */
    public synchronized void sourceConfigurationChanged() {
        preview = null;
        Grant live = grant;
        if (live != null && live.config().captureMode().equals("content")) revoke();
    }

    private void revoke() {
        preview = null;
        grant = null;
        epoch.incrementAndGet();
        dropped.addAndGet(queue.size());
        queue.clear();
        transport.cancel();
        sessionAliases.clear();
    }

    public SpanHandle begin(String operation, String sessionId, Context parent) {
        Grant live = grant;
        if (closed
                || live == null
                || !OPERATIONS.contains(operation)
                || !scope(live.config(), sessionId))
            return new SpanHandle(Span.getInvalid(), Context.root(), -1, sessionId);
        try {
            Span span =
                    tracer.spanBuilder(operation)
                            .setParent(parent == null ? Context.root() : parent)
                            .setSpanKind(
                                    Set.of("model.generation", "context.compact")
                                                    .contains(operation)
                                            ? SpanKind.CLIENT
                                            : SpanKind.INTERNAL)
                            .setAttribute("selfanalyst.epoch", live.epoch())
                            .setAttribute("selfanalyst.category", operation)
                            .setAttribute("langfuse.session.id", alias(sessionId))
                            .setAttribute("langfuse.release", releaseVersion())
                            .setAttribute(
                                    "langfuse.observation.type",
                                    Set.of("model.generation", "context.compact")
                                                    .contains(operation)
                                            ? "generation"
                                            : "span")
                            .startSpan();
            return new SpanHandle(
                    span,
                    (parent == null ? Context.root() : parent).with(span),
                    live.epoch(),
                    sessionId);
        } catch (RuntimeException ignored) {
            return new SpanHandle(Span.getInvalid(), Context.root(), -1, sessionId);
        }
    }

    private static String releaseVersion() {
        String version = ObservabilityService.class.getPackage().getImplementationVersion();
        return version != null && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?")
                ? version
                : "development";
    }

    private String alias(String session) {
        if (session == null || session.isBlank()) return "background";
        synchronized (sessionAliases) {
            if (sessionAliases.size() >= 1024 && !sessionAliases.containsKey(session))
                return "unlinked";
            return sessionAliases.computeIfAbsent(session, x -> UUID.randomUUID().toString());
        }
    }

    private static boolean scope(ObservabilityConfig config, String session) {
        return config.scope().equals("all-runtime") || Objects.equals(config.sessionId(), session);
    }

    public final class SpanHandle {
        private final Span span;
        private final Context context;
        private final long version;
        private final String session;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final long started = System.nanoTime();
        private final AtomicBoolean first = new AtomicBoolean();

        private SpanHandle(Span span, Context context, long version, String session) {
            this.span = span;
            this.context = context;
            this.version = version;
            this.session = session;
        }

        public Context context() {
            return context;
        }

        public void firstToken() {
            if (first.compareAndSet(false, true))
                span.setAttribute(
                        "selfanalyst.first_output_ms",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }

        public boolean allowContent(Content content) {
            Grant live = grant;
            return !ended.get()
                    && span.isRecording()
                    && live != null
                    && live.epoch() == version
                    && scope(live.config(), session)
                    && allowed(live.config(), content);
        }

        public void finish(
                String outcome, long input, long output, String usageSource, Content content) {
            if (!ended.compareAndSet(false, true)) return;
            try {
                Grant live = grant;
                if (live == null || live.epoch() != version) return;
                String status =
                        Set.of("success", "error", "cancelled", "timeout", "rejected")
                                        .contains(Objects.toString(outcome, ""))
                                ? outcome
                                : "error";
                span.setAttribute("selfanalyst.outcome", status);
                span.setStatus(status.equals("success") ? StatusCode.OK : StatusCode.ERROR);
                if (Set.of("actual", "estimated", "reserved")
                        .contains(Objects.toString(usageSource, ""))) {
                    span.setAttribute("selfanalyst.usage.source", usageSource);
                    if (input >= 0 && input <= 1000000000L)
                        span.setAttribute("selfanalyst.usage.input", input);
                    if (output >= 0 && output <= 1000000000L)
                        span.setAttribute("selfanalyst.usage.output", output);
                    if (usageSource.equals("actual")
                            && input >= 0
                            && output >= 0
                            && input <= 1000000000L
                            && output <= 1000000000L)
                        span.setAttribute(
                                "langfuse.observation.usage_details",
                                "{\"input\":" + input + ",\"output\":" + output + "}");
                }
                if (allowed(live.config(), content)) {
                    String in = safeText(content.input(), live.secret()),
                            out = safeText(content.output(), live.secret());
                    if (in != null) span.setAttribute("langfuse.observation.input", in);
                    if (out != null) span.setAttribute("langfuse.observation.output", out);
                } else if (content != null) span.setAttribute("selfanalyst.content_omitted", true);
            } catch (RuntimeException ignored) {
                dropped.incrementAndGet();
            } finally {
                span.end();
            }
        }
    }

    private static boolean allowed(ObservabilityConfig c, Content body) {
        if (body == null
                || !c.captureMode().equals("content")
                || body.imageDerived()
                || body.unknownSources()
                || !c.categories().contains(body.category())) return false;
        if (!body.toolSources().isEmpty() && !c.toolSources().containsAll(body.toolSources()))
            return false;
        if (!body.toolSources().isEmpty()
                && !body.category().equals("toolCatalog")
                && !c.categories().contains("toolText")) return false;
        if (Set.of("toolText", "toolCatalog").contains(body.category())
                && body.toolSources().isEmpty()
                && !body.mailDerived()) return false;
        if (!body.mailDerived()) return true;
        if (body.mailAccounts().isEmpty() || !c.mailAccounts().containsAll(body.mailAccounts()))
            return false;
        return body.category().equals("mailHeaders")
                ? c.categories().contains("mailHeaders")
                : c.categories().containsAll(Set.of("mailHeaders", "mailBody"));
    }

    private static String safeText(String input, String secret) {
        if (input == null) return null;
        if (input.contains("data:") || input.matches("(?s).*[A-Za-z0-9+/]{200,}={0,2}.*"))
            return "[binary content omitted]";
        String text = input.replace(secret, "[secret omitted]");
        // Header-shaped plain text is not necessarily JSON. Omit the entire value,
        // including Bearer/Basic credentials and all semicolon-separated cookies.
        text =
                text.replaceAll(
                        "(?im)(?:authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*[^\\r"
                                + "\\n"
                                + "]*",
                        "[authentication header omitted]");
        text =
                text.replaceAll(
                        "(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+",
                        "[authentication value omitted]");
        text =
                text.replaceAll(
                        "(?is)(?:authorization|cookie|api[_-]?key|secret[_-]?key|password|access[_-]?token|refresh[_-]?token|private[_-]?key)\\s*[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,}]+)",
                        "[credential omitted]");
        text =
                text.replaceAll(
                        "(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----",
                        "[private key omitted]");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 16384) return text;
        return new String(Arrays.copyOf(bytes, 16320), StandardCharsets.UTF_8) + " [truncated]";
    }

    private void drainSafely() {
        try {
            drain();
        } catch (Throwable failure) {
            lastError = "export_failed";
            failed.incrementAndGet();
        }
    }

    void drain() throws IOException {
        Grant live = grant;
        if (live == null || closed) {
            queue.clear();
            return;
        }
        List<SpanData> batch = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        queue.drainTo(pending, 128);
        for (Pending p : pending)
            if (p.epoch() == live.epoch()) batch.add(p.span());
            else dropped.incrementAndGet();
        if (batch.isEmpty()) return;
        byte[] bytes = ObservabilityOtlp.encode(batch);
        try {
            if (bytes.length > 1048576) {
                dropped.addAndGet(batch.size());
                return;
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                if (grant != live || closed) {
                    dropped.addAndGet(batch.size());
                    return;
                }
                try {
                    int status =
                            transport.send(
                                    live.endpoint(),
                                    live.authorization(),
                                    bytes,
                                    () -> grant == live && !closed);
                    if (status == 200) {
                        accepted.addAndGet(batch.size());
                        lastError = "";
                        return;
                    }
                    lastError =
                            status == 401 || status == 403
                                    ? "authentication_required"
                                    : "receiver_rejected";
                    if (!Set.of(429, 502, 503, 504).contains(status)) break;
                } catch (IOException failure) {
                    lastError = ObservabilityTransport.safe(failure);
                    if (!Set.of("timeout", "connection_failed").contains(lastError)) break;
                }
                if (attempt < 2)
                    try {
                        Thread.sleep(100L * (attempt + 1));
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        break;
                    }
            }
            failed.addAndGet(batch.size());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        revoke();
        worker.shutdownNow();
        provider.close();
        endpoints.close();
        transport.close();
        retryCleanup();
        credentials.close();
    }
}
