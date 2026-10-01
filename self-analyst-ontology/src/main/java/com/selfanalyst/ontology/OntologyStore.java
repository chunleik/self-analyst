package com.selfanalyst.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import static com.selfanalyst.ontology.Ontology.*;

/** User decisions and replaceable source projections have separate transactional records. */
public final class OntologyStore implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Connection connection;

    public OntologyStore(Path path) {
        Connection opened = null;
        try {
            boolean existed = Files.exists(path);
            Files.createDirectories(path.toAbsolutePath().getParent());
            opened = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            connection = opened;
            try (Statement sql = connection.createStatement()) {
                sql.execute("PRAGMA busy_timeout=5000");
                int version;
                try (ResultSet row = sql.executeQuery("PRAGMA user_version")) { version = row.getInt(1); }
                if ((existed && version != 1) || (!existed && version != 0)) throw new SQLException("Unsupported ontology format");
                try (ResultSet row = sql.executeQuery("PRAGMA quick_check")) {
                    if (!row.next() || !"ok".equals(row.getString(1))) throw new SQLException("Invalid ontology database");
                }
                if (!existed) {
                    connection.setAutoCommit(false);
                    sql.execute("CREATE TABLE user_state (id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL)");
                    sql.execute("CREATE TABLE projection (id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL)");
                    write("user_state", UserState.empty());
                    write("projection", Snapshot.empty());
                    sql.execute("PRAGMA user_version=1");
                    connection.commit();
                    connection.setAutoCommit(true);
                }
                read("user_state", UserState.class);
                read("projection", Snapshot.class);
            }
        } catch (Exception e) {
            if (opened != null) try { opened.close(); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
            throw new IllegalStateException("Ontology storage is unavailable; existing data was preserved", e);
        }
    }

    public synchronized UserState users() { return read("user_state", UserState.class); }
    public synchronized Snapshot projection() { return read("projection", Snapshot.class); }
    public synchronized void saveUsers(UserState state) { transaction("user_state", state); }
    public synchronized void replaceProjection(Snapshot snapshot) { transaction("projection", snapshot); }

    private void transaction(String table, Object value) {
        try {
            connection.setAutoCommit(false);
            write(table, value);
            connection.commit();
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
            throw new IllegalStateException("Ontology update failed", e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { throw new IllegalStateException("Ontology connection failed", e); }
        }
    }
    private void write(String table, Object value) throws Exception {
        try (PreparedStatement sql = connection.prepareStatement("INSERT INTO " + table + "(id,body) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body")) {
            sql.setString(1, JSON.writeValueAsString(value)); sql.executeUpdate();
        }
    }
    private <T> T read(String table, Class<T> type) {
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery("SELECT body FROM " + table + " WHERE id=1")) {
            if (!row.next()) throw new SQLException("Missing ontology record");
            return JSON.readValue(row.getString(1), type);
        } catch (Exception e) { throw new IllegalStateException("Ontology data cannot be read", e); }
    }
    @Override public synchronized void close() {
        try { connection.close(); } catch (SQLException e) { throw new IllegalStateException("Ontology close failed", e); }
    }
}
