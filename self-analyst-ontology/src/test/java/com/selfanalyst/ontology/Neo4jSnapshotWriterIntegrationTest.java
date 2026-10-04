package com.selfanalyst.ontology;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.selfanalyst.ontology.Ontology.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Bolt writes against a disposable authenticated, loopback-only synthetic database. */
@Execution(ExecutionMode.SAME_THREAD)
class Neo4jSnapshotWriterIntegrationTest {
    @TempDir static Path root;
    private static Neo4j neo4j;
    private static Driver driver;
    private static String password;
    private static final Instant START = Instant.parse("2026-09-01T08:00:00Z");
    private final Neo4jSnapshotWriter writer = new Neo4jSnapshotWriter();

    @BeforeAll static void startSyntheticDatabase() {
        neo4j = Neo4jBuilders.newInProcessBuilder(root)
                .withDisabledServer()
                .withConfig(GraphDatabaseSettings.auth_enabled, true)
                .withConfig(BoltConnector.listen_address, new SocketAddress("127.0.0.1", 0))
                .build();
        // Only this fresh @TempDir database is initialized. No service/user credentials are read.
        password = "synthetic-test-" + UUID.randomUUID();
        neo4j.databaseManagementService().database("system").executeTransactionally(
                "ALTER USER neo4j SET PASSWORD $password CHANGE NOT REQUIRED", Map.of("password", password));
        driver = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.basic("neo4j", password));
        driver.verifyConnectivity();
    }

    @AfterAll static void closeSyntheticDatabase() {
        try {
            if (driver != null) driver.close();
        } finally {
            if (neo4j != null) neo4j.close();
            password = null;
        }
    }

    @Test void rejectsUnauthenticatedAndIncorrectlyAuthenticatedWrites() {
        assertTrue(neo4j.config().get(GraphDatabaseSettings.auth_enabled));
        try (Driver unauthenticated = GraphDatabase.driver(neo4j.boltURI(), AuthTokens.none())) {
            assertThrows(AuthenticationException.class, unauthenticated::verifyConnectivity);
        }
        var config = config("authentication");
        var payload = Neo4jSnapshotWriter.prepare(snapshot(List.of(entity("auth-entity", "project", "Synthetic")), List.of()));
        assertThrows(AuthenticationException.class, () -> writer.write(config, "incorrect-test-password", payload));
        assertEquals(0, count("MATCH (n:SelfAnalystEntity {namespace:$namespace}) RETURN count(n)", config.namespace()));
        writer.write(config, password, payload);
        assertEquals(1, count("MATCH (n:SelfAnalystEntity {namespace:$namespace}) RETURN count(n)", config.namespace()));
    }

    @Test void repeatsEditsRejectsAndDeletesWithoutTouchingForeignGraphData() {
        var config = config("primary");
        var other = config("secondary");
        String attack = "' }) MATCH (n) DETACH DELETE n // 中文 $parameter";
        Entity activity = entity("activity:'}) RETURN 1 //", "activity", attack);
        Entity project = entity("project", "project", "Original project");
        Entity stale = entity("stale", "project", "Content that must disappear");
        Entity detached = entity("detached", "topic", "Deleted topic");
        Assertion candidate = assertion(activity.id(), project.id(), "inferred", "candidate");
        Assertion deleted = assertion(activity.id(), stale.id(), "observed", "accepted");
        var initial = new ExportSnapshot(List.of(activity, project, stale, detached), List.of(candidate, deleted),
                Map.of("merged-old-id", project.id()), Map.of("wiki", "current", "memory", "unavailable"));
        var initialPayload = Neo4jSnapshotWriter.prepare(initial);
        writer.write(config, password, initialPayload);
        writer.write(config, password, initialPayload);
        writer.write(other, password, initialPayload);

        assertEquals(4, count("MATCH (n:SelfAnalystEntity {namespace:$namespace}) RETURN count(n)", config.namespace()));
        assertEquals(2, count("MATCH ()-[r:SELF_ANALYST_ASSERTION {namespace:$namespace}]->() RETURN count(r)", config.namespace()));
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            var row = session.run("MATCH (n:SelfAnalystEntity {namespace:$namespace,id:$id}) RETURN properties(n) AS p",
                    Map.of("namespace", config.namespace(), "id", activity.id())).single().get("p").asMap();
            assertEquals(attack, row.get("name"));
            assertEquals(attack + " description", row.get("description"));
            assertEquals(List.of("alias ' \" \\ $literal"), row.get("aliases"));
            assertEquals(START.toString(), row.get("start"));
            assertEquals(START.plusSeconds(3600).toString(), row.get("end"));
            assertEquals("wiki", row.get("source"));
            assertEquals("synthetic:" + activity.id(), row.get("sourceRef"));
            assertTrue(row.get("evidenceJson").toString().contains("evidence-only-title"));
            assertTrue(row.get("attributesJson").toString().contains("summary-period"));
            assertEquals(List.of("merged-old-id"), session.run(
                    "MATCH (n:SelfAnalystEntity {namespace:$namespace,id:'project'}) RETURN n.mergedIds",
                    Map.of("namespace", config.namespace())).single().get(0).asList());
            var relation = session.run("MATCH ()-[r:SELF_ANALYST_ASSERTION {namespace:$namespace,id:$id}]->() "
                            + "RETURN r.claimType,r.status,r.start,r.end,r.evidenceJson",
                    Map.of("namespace", config.namespace(), "id", candidate.key())).single();
            assertEquals("inferred", relation.get(0).asString());
            assertEquals("candidate", relation.get(1).asString());
            assertEquals(START.toString(), relation.get(2).asString());
            assertEquals(START.plusSeconds(3600).toString(), relation.get(3).asString());
            assertTrue(relation.get(4).asString().contains("synthetic evidence"));
            session.run("""
                    MATCH (stale:SelfAnalystEntity {namespace:$namespace,id:'stale'})
                    MATCH (a:SelfAnalystEntity {namespace:$namespace,id:$activity})
                    MATCH (p:SelfAnalystEntity {namespace:$namespace,id:'project'})
                    CREATE (outside:OtherApplication {namespace:$namespace,id:'stale',name:'foreign content'})
                    CREATE (another:OtherApplication {name:'foreign endpoint'})
                    CREATE (outside)-[:OTHER_APP_RELATION {value:'keep'}]->(another)
                    CREATE (stale)-[:EXTERNAL_REFERENCE {value:'keep attached'}]->(outside)
                    CREATE (a)-[:SELF_ANALYST_ASSERTION {namespace:'foreign-owner',id:'foreign-relation',value:'keep namespace'}]->(p)
                    """, Map.of("namespace", config.namespace(), "activity", activity.id())).consume();
        }

        Entity edited = entity(project.id(), "project", "Updated project");
        Assertion confirmed = assertion(activity.id(), project.id(), "confirmed", "accepted");
        writer.write(config, password, Neo4jSnapshotWriter.prepare(new ExportSnapshot(
                List.of(activity, edited), List.of(confirmed), Map.of("merged-old-id", project.id()), Map.of("wiki", "current"))));
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            assertEquals("Updated project", session.run(
                    "MATCH (n:SelfAnalystEntity {namespace:$namespace,id:'project'}) RETURN n.name",
                    Map.of("namespace", config.namespace())).single().get(0).asString());
            var relation = session.run("MATCH ()-[r:SELF_ANALYST_ASSERTION {namespace:$namespace}]->() RETURN r.id,r.claimType,r.status",
                    Map.of("namespace", config.namespace())).single();
            assertEquals(candidate.key(), relation.get(0).asString());
            assertEquals("confirmed", relation.get(1).asString());
            assertEquals("accepted", relation.get(2).asString());
            var tombstone = session.run("MATCH (n:SelfAnalystEntity {namespace:$namespace,id:'stale'}) RETURN properties(n)",
                    Map.of("namespace", config.namespace())).single().get(0).asMap();
            assertEquals(Map.of("namespace", config.namespace(), "id", "stale", "tombstone", true), tombstone);
        }
        assertEquals(0, count("MATCH (n:SelfAnalystEntity {namespace:$namespace,id:'detached'}) RETURN count(n)", config.namespace()));
        assertEquals(1, count("MATCH (:SelfAnalystEntity {namespace:$namespace})-[r:EXTERNAL_REFERENCE]->(:OtherApplication) RETURN count(r)", config.namespace()));
        assertEquals(1, count("MATCH (:OtherApplication {namespace:$namespace})-[r:OTHER_APP_RELATION]->(:OtherApplication) RETURN count(r)", config.namespace()));
        assertEquals(1, count("MATCH (:SelfAnalystEntity {namespace:$namespace})-[r:SELF_ANALYST_ASSERTION {namespace:'foreign-owner'}]->() RETURN count(r)", config.namespace()));
        assertEquals(4, count("MATCH (n:SelfAnalystEntity {namespace:$namespace}) RETURN count(n)", other.namespace()));
        assertEquals(2, count("MATCH ()-[r:SELF_ANALYST_ASSERTION {namespace:$namespace}]->() RETURN count(r)", other.namespace()));

        // A rejected final assertion is absent from the next authoritative snapshot.
        writer.write(config, password, Neo4jSnapshotWriter.prepare(snapshot(List.of(activity, edited), List.of())));
        assertEquals(0, count("MATCH ()-[r:SELF_ANALYST_ASSERTION {namespace:$namespace}]->() RETURN count(r)", config.namespace()));
        assertEquals(1, count("MATCH (:SelfAnalystEntity {namespace:$namespace})-[r:SELF_ANALYST_ASSERTION {namespace:'foreign-owner'}]->() RETURN count(r)", config.namespace()));
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            var metadata = session.run("MATCH (s:SelfAnalystSync {namespace:$namespace}) RETURN properties(s)",
                    Map.of("namespace", config.namespace())).single().get(0).asMap();
            assertEquals(2L, metadata.get("entities"));
            assertEquals(0L, metadata.get("assertions"));
            assertEquals("summary-period-not-task-duration", metadata.get("periodMeaning"));
            assertEquals("{\"wiki\":\"current\"}", metadata.get("coverageJson"));
            assertEquals(1L, metadata.get("schemaVersion"));
            assertFalse(metadata.containsKey("lock"));
            assertNotNull(metadata.get("syncedAt"));
        }
    }

    @Test void lateBatchFailureRollsBackEarlierRowsAndPublishedGeneration() {
        var config = config("rollback");
        Entity original = entity("original", "activity", "Before failed sync");
        Entity project = entity("project", "project", "Retained project");
        var before = Neo4jSnapshotWriter.prepare(snapshot(List.of(original, project),
                List.of(assertion(original.id(), project.id(), "confirmed", "accepted"))));
        writer.write(config, password, before);
        Map<String, Object> previousMetadata;
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            previousMetadata = session.run("MATCH (s:SelfAnalystSync {namespace:$namespace}) RETURN properties(s)",
                    Map.of("namespace", config.namespace())).single().get(0).asMap();
        }

        List<Entity> changed = new ArrayList<>();
        changed.add(entity(original.id(), "activity", "Must roll back"));
        for (int i = 1; i < 500; i++) changed.add(entity("new-" + i, "topic", "Uncommitted " + i));
        var rows = new ArrayList<>(Neo4jSnapshotWriter.prepare(snapshot(changed, List.of())).entities());
        Map<String, Object> invalid = new HashMap<>(rows.getFirst());
        invalid.put("id", "invalid-second-batch");
        invalid.put("attributesJson", Map.of("not", "a property value"));
        rows.add(invalid);
        var invalidPayload = new Neo4jSnapshotWriter.Payload(rows, List.of(), "{}", 1);
        assertThrows(ClientException.class, () -> writer.write(config, password, invalidPayload));

        assertEquals(2, count("MATCH (n:SelfAnalystEntity {namespace:$namespace}) RETURN count(n)", config.namespace()));
        assertEquals(1, count("MATCH ()-[r:SELF_ANALYST_ASSERTION {namespace:$namespace}]->() RETURN count(r)", config.namespace()));
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            assertEquals("Before failed sync", session.run(
                    "MATCH (n:SelfAnalystEntity {namespace:$namespace,id:'original'}) RETURN n.name",
                    Map.of("namespace", config.namespace())).single().get(0).asString());
            assertEquals(previousMetadata, session.run("MATCH (s:SelfAnalystSync {namespace:$namespace}) RETURN properties(s)",
                    Map.of("namespace", config.namespace())).single().get(0).asMap());
        }
    }

    @Test void unsupportedPublishedSchemaCannotModifyPriorGraphOrMetadata() {
        var config = config("future-schema");
        var existing = Neo4jSnapshotWriter.prepare(snapshot(
                List.of(entity("retained", "project", "Keep original content")), List.of()));
        writer.write(config, password, existing);
        Map<String, Object> metadata;
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            metadata = session.run("MATCH (s:SelfAnalystSync {namespace:$namespace}) SET s.schemaVersion = 99 RETURN properties(s)",
                    Map.of("namespace", config.namespace())).single().get(0).asMap();
        }
        assertThrows(IllegalStateException.class, () -> writer.write(config, password,
                Neo4jSnapshotWriter.prepare(snapshot(List.of(), List.of()))));
        assertEquals(1, count("MATCH (n:SelfAnalystEntity {namespace:$namespace}) RETURN count(n)", config.namespace()));
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            assertEquals(metadata, session.run("MATCH (s:SelfAnalystSync {namespace:$namespace}) RETURN properties(s)",
                    Map.of("namespace", config.namespace())).single().get(0).asMap());
            assertEquals("Keep original content", session.run(
                    "MATCH (n:SelfAnalystEntity {namespace:$namespace,id:'retained'}) RETURN n.name",
                    Map.of("namespace", config.namespace())).single().get(0).asString());
        }
    }

    @Test void constraintNameCollisionCannotBypassIdentityUniquenessValidation() {
        var config = config("schema-collision");
        var existing = Neo4jSnapshotWriter.prepare(snapshot(
                List.of(entity("retained", "project", "Original constraint-protected content")), List.of()));
        writer.write(config, password, existing);
        Map<String, Object> metadata;
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            metadata = session.run("MATCH (s:SelfAnalystSync {namespace:$namespace}) RETURN properties(s)",
                    Map.of("namespace", config.namespace())).single().get(0).asMap();
        }
        try {
            try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
                session.run("DROP CONSTRAINT selfanalyst_entity_identity IF EXISTS").consume();
                session.run("CREATE CONSTRAINT selfanalyst_entity_identity "
                        + "FOR (n:OtherConstraintOwner) REQUIRE n.otherKey IS UNIQUE").consume();
            }
            var replacement = Neo4jSnapshotWriter.prepare(snapshot(
                    List.of(entity("replacement", "project", "Must not be published")), List.of()));
            var failure = assertThrows(IllegalStateException.class, () -> writer.write(config, password, replacement));
            assertEquals("neo4j.schemaConflict", failure.getMessage());
            assertEquals(1, count("MATCH (n:SelfAnalystEntity {namespace:$namespace}) RETURN count(n)", config.namespace()));
            try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
                assertEquals(metadata, session.run("MATCH (s:SelfAnalystSync {namespace:$namespace}) RETURN properties(s)",
                        Map.of("namespace", config.namespace())).single().get(0).asMap());
                assertEquals("Original constraint-protected content", session.run(
                        "MATCH (n:SelfAnalystEntity {namespace:$namespace,id:'retained'}) RETURN n.name",
                        Map.of("namespace", config.namespace())).single().get(0).asString());
            }
        } finally {
            try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
                session.run("DROP CONSTRAINT selfanalyst_entity_identity IF EXISTS").consume();
                session.run("CREATE CONSTRAINT selfanalyst_entity_identity "
                        + "FOR (n:SelfAnalystEntity) REQUIRE (n.namespace,n.id) IS UNIQUE").consume();
            }
        }
    }

    private static Neo4jSyncConfig config(String namespace) {
        return new Neo4jSyncConfig(true, "bolt://127.0.0.1:" + neo4j.boltURI().getPort(), "neo4j", "neo4j",
                "SYNTHETIC_TEST_ONLY_PASSWORD", namespace, 30);
    }

    private static Entity entity(String id, String type, String name) {
        return new Entity(id, type, name, name + " description", List.of("alias ' \" \\ $literal"),
                "wiki", "synthetic:" + id, START, START.plusSeconds(3600),
                List.of(new Evidence("synthetic:evidence", "evidence-only-title", true)),
                Map.of("periodMeaning", "summary-period"));
    }

    private static Assertion assertion(String activity, String project, String claimType, String status) {
        return new Assertion(activity, "relatedTo", project, claimType, "wiki", status,
                START, START.plusSeconds(3600), List.of(new Evidence("synthetic:claim", "synthetic evidence", true)));
    }

    private static ExportSnapshot snapshot(List<Entity> entities, List<Assertion> assertions) {
        return new ExportSnapshot(entities, assertions, Map.of(), Map.of("wiki", "current"));
    }

    private static long count(String query, String namespace) {
        try (var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            return session.run(query, Map.of("namespace", namespace)).single().get(0).asLong();
        }
    }
}
