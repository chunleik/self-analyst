package com.selfanalyst.ontology;

import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/** Manual-only coordinator. Construction and status never create a network client. */
public final class Neo4jSyncService {
    @FunctionalInterface
    public interface Writer {
        void write(Neo4jSyncConfig config, String password, Neo4jSnapshotWriter.Payload payload);
        default boolean pendingCleanup() { return false; }
    }
    private final OntologyService ontology;
    private final Supplier<Neo4jSyncConfig> configuration;
    private final Function<String, String> environment;
    private final Writer writer;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<String, Object> lastResult = Map.of();

    public Neo4jSyncService(OntologyService ontology, Supplier<Neo4jSyncConfig> configuration,
                            Function<String, String> environment) {
        this(ontology, configuration, environment, new Neo4jSnapshotWriter());
    }
    public Neo4jSyncService(OntologyService ontology, Supplier<Neo4jSyncConfig> configuration,
                            Function<String, String> environment, Writer writer) {
        this.ontology = ontology; this.configuration = Objects.requireNonNull(configuration);
        this.environment = Objects.requireNonNull(environment); this.writer = Objects.requireNonNull(writer);
    }
    public Map<String, Object> status() {
        Neo4jSyncConfig config;
        try { config = Objects.requireNonNull(configuration.get()); }
        catch (RuntimeException invalid) {
            return Map.of("state", "invalidConfig", "configured", false, "target", Map.of(),
                    "targetFingerprint", "", "lastResult", Map.of());
        }
        String state = "ready";
        boolean configured = true;
        try {
            config.validate();
            if (missingPassword(config)) { state = "missingPassword"; configured = false; }
        } catch (RuntimeException invalid) { state = "invalidConfig"; configured = false; }
        Map<String, Object> latest = lastResult;
        if (!config.fingerprint().equals(latest.get("targetFingerprint"))) latest = Map.of();
        if (!config.enabled()) state = "disabled";
        else if (running.get() || writer.pendingCleanup()) state = "running";
        else if (state.equals("ready") && !latest.isEmpty()) state = Boolean.TRUE.equals(latest.get("success")) ? "success" : "error";
        return Map.of("state", state, "configured", configured, "target", config.publicTarget(),
                "targetFingerprint", config.fingerprint(), "lastResult", latest);
    }
    private boolean missingPassword(Neo4jSyncConfig config) {
        String password = environment.apply(config.passwordEnv());
        return password == null || password.isBlank();
    }
    public Map<String, Object> sync(String confirmedTarget) {
        Neo4jSyncConfig config;
        try { config = Objects.requireNonNull(configuration.get()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("neo4j.invalidConfig"); }
        if (!config.enabled()) throw new IllegalStateException("neo4j.disabled");
        config.validate();
        if (!config.fingerprint().equals(confirmedTarget)) throw new IllegalArgumentException("neo4j.confirmationRequired");
        if (ontology == null) throw new IllegalStateException("neo4j.ontologyUnavailable");
        if (writer.pendingCleanup() || !running.compareAndSet(false, true)) throw new IllegalStateException("neo4j.busy");
        try {
            String password = environment.apply(config.passwordEnv());
            if (password == null || password.isBlank()) throw new IllegalArgumentException("neo4j.missingPassword");
            var snapshot = ontology.exportSnapshot();
            var payload = Neo4jSnapshotWriter.prepare(snapshot);
            writer.write(config, password, payload);
            lastResult = Map.of("success", true, "code", "neo4j.success", "at", Instant.now().toString(),
                    "entities", payload.entities().size(), "assertions", payload.assertions().size(),
                    "coverage", snapshot.coverage(), "bytes", payload.bytes(), "targetFingerprint", config.fingerprint());
            return lastResult;
        } catch (RuntimeException failure) {
            String code = errorCode(failure);
            lastResult = Map.of("success", false, "code", code, "at", Instant.now().toString(),
                    "targetFingerprint", config.fingerprint());
            // No exception cause: driver messages may contain a target or a query's user data.
            throw new IllegalStateException(code);
        } finally { running.set(false); }
    }
    private static String errorCode(RuntimeException error) {
        if (error instanceof AuthenticationException) return "neo4j.authenticationFailed";
        if (error instanceof ServiceUnavailableException || error instanceof SessionExpiredException) return "neo4j.connectionFailed";
        String code = error.getMessage();
        if (Set.of("neo4j.missingPassword", "neo4j.snapshotTooLarge", "neo4j.invalidSnapshot", "neo4j.timeout", "neo4j.busy").contains(code == null ? "" : code)) return code;
        return "neo4j.syncFailed";
    }
}
