package com.selfanalyst.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.neo4j.driver.*;
import org.neo4j.driver.async.AsyncQueryRunner;
import org.neo4j.driver.async.AsyncSession;
import org.neo4j.driver.async.AsyncTransaction;
import org.neo4j.driver.async.ResultCursor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.selfanalyst.ontology.Ontology.*;

/** A disposable, parameterized and namespace-isolated Neo4j projection writer. */
public final class Neo4jSnapshotWriter implements Neo4jSyncService.Writer {
    private static final int CLEANUP_SECONDS = 5;
    // A failed/pending close keeps this gate held: never accumulate orphaned drivers on retry.
    private final AtomicBoolean active = new AtomicBoolean();
    @FunctionalInterface interface DriverFactory { Driver create(Neo4jSyncConfig config, String password); }
    private final DriverFactory drivers;
    public Neo4jSnapshotWriter() { this(Neo4jSnapshotWriter::createDriver); }
    Neo4jSnapshotWriter(DriverFactory drivers) { this.drivers = Objects.requireNonNull(drivers); }
    @Override public boolean pendingCleanup() { return active.get(); }
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int BATCH_SIZE = 500;
    private static final String ENTITY_CONSTRAINT = "CREATE CONSTRAINT selfanalyst_entity_identity IF NOT EXISTS "
            + "FOR (n:SelfAnalystEntity) REQUIRE (n.namespace, n.id) IS UNIQUE";
    private static final String SYNC_CONSTRAINT = "CREATE CONSTRAINT selfanalyst_sync_identity IF NOT EXISTS "
            + "FOR (n:SelfAnalystSync) REQUIRE n.namespace IS UNIQUE";

    /** All data is materialized and budget-checked before a driver can be created. */
    public record Payload(List<Map<String, Object>> entities, List<Map<String, Object>> assertions,
                          String coverageJson, int bytes) {}

    public static Payload prepare(ExportSnapshot snapshot) {
        Map<String, List<String>> merged = new HashMap<>();
        snapshot.redirects().forEach((from, to) -> merged.computeIfAbsent(to, ignored -> new ArrayList<>()).add(from));
        List<Map<String, Object>> entities = new ArrayList<>();
        List<Map<String, Object>> assertions = new ArrayList<>();
        long bytes = 0;
        for (Entity entity : snapshot.entities()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entity.id()); row.put("type", entity.type()); row.put("name", entity.name());
            row.put("description", entity.description()); row.put("aliases", entity.aliases());
            row.put("mergedIds", merged.getOrDefault(entity.id(), List.of()).stream().sorted().toList());
            row.put("source", entity.source()); row.put("sourceRef", entity.sourceRef());
            row.put("start", Objects.toString(entity.start(), "")); row.put("end", Objects.toString(entity.end(), ""));
            row.put("evidenceJson", json(entity.evidence())); row.put("attributesJson", json(entity.attributes()));
            bytes += json(row).getBytes(StandardCharsets.UTF_8).length;
            checkBytes(bytes); entities.add(Map.copyOf(row));
        }
        for (Assertion assertion : snapshot.assertions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", assertion.key()); row.put("subject", assertion.subject()); row.put("object", assertion.object());
            row.put("predicate", assertion.predicate()); row.put("claimType", assertion.claimType());
            row.put("source", assertion.source()); row.put("status", assertion.status());
            row.put("start", Objects.toString(assertion.start(), "")); row.put("end", Objects.toString(assertion.end(), ""));
            row.put("evidenceJson", json(assertion.evidence()));
            bytes += json(row).getBytes(StandardCharsets.UTF_8).length;
            checkBytes(bytes); assertions.add(Map.copyOf(row));
        }
        String coverage = json(snapshot.coverage());
        bytes += coverage.getBytes(StandardCharsets.UTF_8).length;
        checkBytes(bytes);
        return new Payload(List.copyOf(entities), List.copyOf(assertions), coverage, (int) bytes);
    }
    private static void checkBytes(long bytes) {
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("neo4j.snapshotTooLarge");
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("neo4j.invalidSnapshot"); }
    }

    @Override public void write(Neo4jSyncConfig config, String password, Payload payload) {
        config.validate();
        if (!active.compareAndSet(false, true)) throw new IllegalStateException("neo4j.busy");
        Driver driver = null;
        boolean completed = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
        try {
            driver = drivers.create(config, password);
            write(driver, config, payload, deadline);
            completed = true;
        } finally {
            if (driver == null) active.set(false);
            else closeDriver(driver, completed);
        }
    }

    private static Driver createDriver(Neo4jSyncConfig config, String password) {
        var settings = org.neo4j.driver.Config.builder()
                .withConnectionTimeout(config.timeoutSeconds(), TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(config.timeoutSeconds(), TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(0, TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(1).withEventLoopThreads(2)
                .withTelemetryDisabled(true).withLogging(Logging.none()).build();
        return GraphDatabase.driver(config.uri(), AuthTokens.basic(config.username(), password), settings);
    }

    private void closeDriver(Driver driver, boolean completed) {
        // Driver.close() itself blocks. Use the asynchronous API and a separate bounded cleanup budget.
        // Do not cancel this future: completion, not cancellation, proves the driver released its resources.
        boolean interrupted = Thread.interrupted();
        try {
            var closed = driver.closeAsync();
            closed.whenComplete((ignored, failure) -> { if (failure == null) active.set(false); });
            try { closed.toCompletableFuture().get(CLEANUP_SECONDS, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { interrupted = true; if (completed) throw timeout(); }
            catch (TimeoutException | ExecutionException failure) { if (completed) throw timeout(); }
        } catch (RuntimeException failure) {
            // A failed close must retain the gate, and must not replace a more useful write failure.
            if (completed) throw timeout();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    // Package-visible for synthetic integration tests using a caller-owned driver.
    void write(Driver driver, Neo4jSyncConfig config, Payload payload) {
        write(driver, config, payload, System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds()));
    }

    private void write(Driver driver, Neo4jSyncConfig config, Payload payload, long deadline) {
        String generation = UUID.randomUUID().toString();
        var transactionConfig = TransactionConfig.builder().withTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
        AsyncSession session = driver.session(AsyncSession.class, SessionConfig.forDatabase(config.database()));
        boolean completed = false;
        try {
            // Every wait shares one client deadline. There is no background transaction callback that can
            // continue to send batches or COMMIT after the request reports a timeout.
            requireTime(deadline);
            consume(session.runAsync(ENTITY_CONSTRAINT, transactionConfig), deadline);
            requireTime(deadline);
            consume(session.runAsync(SYNC_CONSTRAINT, transactionConfig), deadline);
            requireTime(deadline);
            var schema = await(session.runAsync("SHOW CONSTRAINTS YIELD name, labelsOrTypes, properties, type "
                    + "WHERE name IN ['selfanalyst_entity_identity', 'selfanalyst_sync_identity'] "
                    + "RETURN name, labelsOrTypes, properties, type", transactionConfig), deadline);
            requireTime(deadline);
            var constraints = await(schema.listAsync(), deadline);
            boolean entityIdentity = false, syncIdentity = false;
            for (var constraint : constraints) {
                if (!"UNIQUENESS".equals(constraint.get("type").asString())) continue;
                if ("selfanalyst_entity_identity".equals(constraint.get("name").asString())) {
                    entityIdentity = constraint.get("labelsOrTypes").asList().equals(List.of("SelfAnalystEntity"))
                            && constraint.get("properties").asList().equals(List.of("namespace", "id"));
                } else if ("selfanalyst_sync_identity".equals(constraint.get("name").asString())) {
                    syncIdentity = constraint.get("labelsOrTypes").asList().equals(List.of("SelfAnalystSync"))
                            && constraint.get("properties").asList().equals(List.of("namespace"));
                }
            }
            if (!entityIdentity || !syncIdentity) throw new IllegalStateException("neo4j.schemaConflict");
            requireTime(deadline);
            AsyncTransaction tx = await(session.beginTransactionAsync(transactionConfig), deadline);
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("namespace", config.namespace()); parameters.put("generation", generation);
            // The unique namespace node serializes writers before any rows are changed.
            requireTime(deadline);
            var identity = await(tx.runAsync("MERGE (s:SelfAnalystSync {namespace: $namespace}) SET s.lock = $generation "
                    + "RETURN s.schemaVersion AS version", parameters), deadline);
            requireTime(deadline);
            var existing = await(identity.singleAsync(), deadline).get("version");
            requireTime(deadline);
            await(identity.consumeAsync(), deadline);
            if (!existing.isNull() && existing.asInt() != 1) throw new IllegalStateException("neo4j.schemaConflict");
            for (int at = 0; at < payload.entities().size(); at += BATCH_SIZE) {
                parameters.put("rows", payload.entities().subList(at, Math.min(at + BATCH_SIZE, payload.entities().size())));
                run(tx, deadline, """
                        UNWIND $rows AS row
                        MERGE (n:SelfAnalystEntity {namespace: $namespace, id: row.id})
                        SET n += row, n.generation = $generation, n.tombstone = false
                        """, parameters);
            }
            for (int at = 0; at < payload.assertions().size(); at += BATCH_SIZE) {
                parameters.put("rows", payload.assertions().subList(at, Math.min(at + BATCH_SIZE, payload.assertions().size())));
                run(tx, deadline, """
                        UNWIND $rows AS row
                        MATCH (a:SelfAnalystEntity {namespace: $namespace, id: row.subject})
                        MATCH (b:SelfAnalystEntity {namespace: $namespace, id: row.object})
                        MERGE (a)-[r:SELF_ANALYST_ASSERTION {namespace: $namespace, id: row.id}]->(b)
                        SET r += row, r.generation = $generation
                        """, parameters);
            }
            run(tx, deadline, """
                    MATCH (:SelfAnalystEntity {namespace: $namespace})-[r:SELF_ANALYST_ASSERTION {namespace: $namespace}]->(:SelfAnalystEntity {namespace: $namespace})
                    WHERE r.generation IS NULL OR r.generation <> $generation DELETE r
                    """, parameters);
            // Never DETACH DELETE: externally owned edges must survive even if they reference our node.
            run(tx, deadline, """
                    MATCH (n:SelfAnalystEntity {namespace: $namespace})
                    WHERE n.generation IS NULL OR n.generation <> $generation
                    REMOVE n.type, n.name, n.description, n.aliases, n.mergedIds, n.source, n.sourceRef,
                           n.start, n.end, n.evidenceJson, n.attributesJson, n.generation
                    SET n.tombstone = true
                    """, parameters);
            run(tx, deadline, """
                    MATCH (n:SelfAnalystEntity {namespace: $namespace})
                    WHERE n.tombstone = true AND NOT EXISTS { MATCH (n)--() }
                    DELETE n
                    """, parameters);
            parameters.put("coverage", payload.coverageJson()); parameters.put("entities", payload.entities().size());
            parameters.put("assertions", payload.assertions().size()); parameters.put("bytes", payload.bytes());
            run(tx, deadline, """
                    MATCH (s:SelfAnalystSync {namespace: $namespace})
                    SET s.generation = $generation, s.coverageJson = $coverage, s.entities = $entities,
                        s.assertions = $assertions, s.bytes = $bytes, s.schemaVersion = 1,
                        s.periodMeaning = 'summary-period-not-task-duration', s.syncedAt = datetime()
                    REMOVE s.lock
                    """, parameters);
            requireTime(deadline);
            // A timeout waiting for this acknowledgement is commit-uncertain, not proof of rollback.
            await(tx.commitAsync(), deadline);
            completed = true;
        } finally {
            // On failure this can queue rollback/reset, never COMMIT. The owning driver is closed
            // below even if a stalled cursor prevents graceful session cleanup from completing.
            try { await(session.closeAsync(), deadline); }
            catch (RuntimeException failure) { if (completed) throw failure; }
        }
    }

    private static void run(AsyncQueryRunner runner, long deadline, String query, Map<String, Object> parameters) {
        requireTime(deadline);
        consume(runner.runAsync(query, parameters), deadline);
    }
    private static void consume(CompletionStage<ResultCursor> result, long deadline) {
        ResultCursor cursor = await(result, deadline);
        requireTime(deadline);
        await(cursor.consumeAsync(), deadline);
    }
    private static long requireTime(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw timeout();
        return remaining;
    }
    private static <T> T await(CompletionStage<T> work, long deadline) {
        try { return work.toCompletableFuture().get(requireTime(deadline), TimeUnit.NANOSECONDS); }
        catch (TimeoutException failure) { throw timeout(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw timeout(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException error) throw error;
            throw new IllegalStateException("neo4j.syncFailed");
        }
    }
    private static IllegalStateException timeout() { return new IllegalStateException("neo4j.timeout"); }
}
