package com.selfanalyst.document;

import com.selfanalyst.desktop.store.ChatSessionStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 元数据存于 chat.db，文件发布事务与会话归属检查共用 writer。 */
public final class DocumentStore {
    private final ChatSessionStore chats;
    public record Artifact(String id, String sessionId, String userMessageId, String status,
                           String name, String format, long size, String sha256, String createdAt,
                           String parentId, int version, String error, com.fasterxml.jackson.databind.JsonNode metadata) {}

    public DocumentStore(ChatSessionStore chats) {
        this.chats = chats;
        chats.documentTransaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS document_jobs(
                      id TEXT PRIMARY KEY, session_id TEXT NOT NULL, user_message_id TEXT NOT NULL,
                      request_hash TEXT NOT NULL, status TEXT NOT NULL,
                      name TEXT NOT NULL, format TEXT NOT NULL, created_at TEXT NOT NULL,
                      parent_id TEXT, error TEXT NOT NULL DEFAULT '',
                      UNIQUE(session_id,user_message_id,request_hash))
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS document_artifacts(
                      id TEXT PRIMARY KEY REFERENCES document_jobs(id),
                      size INTEGER NOT NULL, sha256 TEXT NOT NULL, version INTEGER NOT NULL, metadata TEXT NOT NULL DEFAULT '{}')
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_documents_session ON document_jobs(session_id,created_at,id)");
                statement.execute("UPDATE document_jobs SET status='FAILED',error='生成被应用退出中断，请重试' WHERE status='RUNNING'");
                statement.execute("DELETE FROM document_artifacts WHERE id IN (SELECT id FROM document_jobs WHERE session_id NOT IN (SELECT id FROM sessions))");
                statement.execute("DELETE FROM document_jobs WHERE session_id NOT IN (SELECT id FROM sessions)");
            }
            return null;
        });
    }

    private static void requireSession(Connection connection, String sessionId) throws SQLException {
        if (!ChatSessionStore.isGeneratedSessionId(sessionId)) throw new IllegalArgumentException("无效会话");
        try (var statement = connection.prepareStatement("SELECT 1 FROM sessions WHERE id=? AND NOT EXISTS(SELECT 1 FROM pending_deletions WHERE session_id=?)")) {
            statement.setString(1, sessionId); statement.setString(2, sessionId);
            try (var rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("会话不存在或正在删除"); }
        }
    }

    public Artifact begin(String session, String turn, String hash, String name, String format, String parent) {
        return chats.documentTransaction(connection -> {
            requireSession(connection, session);
            if (!ChatSessionStore.isGeneratedMessageId(turn)) throw new IllegalArgumentException("无效用户轮次");
            try (var query = connection.prepareStatement("SELECT 1 FROM messages WHERE id=? AND session_id=? AND role='user'")) {
                query.setString(1, turn); query.setString(2, session);
                try (var rows = query.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("轮次不属于该会话"); }
            }
            if (parent != null && !parent.isBlank()) {
                Artifact previous = read(connection, session, parent);
                if (previous == null || !previous.status.equals("READY")) throw new IllegalArgumentException("前一版本不可用");
            }
            String id = UUID.randomUUID().toString().replace("-", "");
            try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO document_jobs(id,session_id,user_message_id,request_hash,status,name,format,created_at,parent_id) VALUES(?,?,?,?,'RUNNING',?,?,?,?)")) {
                insert.setString(1, id); insert.setString(2, session); insert.setString(3, turn); insert.setString(4, hash);
                insert.setString(5, name); insert.setString(6, format); insert.setString(7, Instant.now().toString()); insert.setString(8, parent);
                insert.executeUpdate();
            }
            try (var query = connection.prepareStatement("SELECT id FROM document_jobs WHERE session_id=? AND user_message_id=? AND request_hash=?")) {
                query.setString(1, session); query.setString(2, turn); query.setString(3, hash);
                try (var row = query.executeQuery()) { row.next(); id = row.getString(1); }
            }
            Artifact result = read(connection, session, id);
            if (result.status.equals("FAILED") || result.status.equals("CANCELLED")) {
                try (var update = connection.prepareStatement("UPDATE document_jobs SET status='RUNNING',error='' WHERE id=?")) {
                    update.setString(1, id); update.executeUpdate();
                }
                result = read(connection, session, id);
            }
            return result;
        });
    }

    public Artifact publish(String session, String id, long size, String sha256) {
        return publish(session, id, size, sha256, "{}");
    }
    public Artifact publish(String session, String id, long size, String sha256, String metadata) {
        return chats.documentTransaction(connection -> {
            requireSession(connection, session);
            Artifact job = read(connection, session, id);
            if (job == null || !job.status.equals("RUNNING")) throw new IllegalStateException("文档生成状态已变化");
            int version = job.parentId == null || job.parentId.isBlank() ? 1 : Objects.requireNonNull(read(connection, session, job.parentId)).version + 1;
            try (var insert = connection.prepareStatement("INSERT INTO document_artifacts(id,size,sha256,version,metadata) VALUES(?,?,?,?,?)")) {
                insert.setString(1, id); insert.setLong(2, size); insert.setString(3, sha256); insert.setInt(4, version); insert.setString(5, metadata); insert.executeUpdate();
            }
            try (var update = connection.prepareStatement("UPDATE document_jobs SET status='READY' WHERE id=?")) {
                update.setString(1, id); update.executeUpdate();
            }
            return read(connection, session, id);
        });
    }

    public void fail(String session, String id, boolean cancelled, String message) {
        chats.documentTransaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE document_jobs SET status=?,error=? WHERE id=? AND session_id=? AND status<>'READY'")) {
                update.setString(1, cancelled ? "CANCELLED" : "FAILED"); update.setString(2, message);
                update.setString(3, id); update.setString(4, session); update.executeUpdate();
            }
            return null;
        });
    }
    public void invalidate(String session, String id) {
        chats.documentTransaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE document_jobs SET status='FAILED',error='文档文件缺失或损坏，请重新生成' WHERE id=? AND session_id=?")) {
                update.setString(1, id); update.setString(2, session); update.executeUpdate();
            }
            try (var remove = connection.prepareStatement("DELETE FROM document_artifacts WHERE id=? AND id IN(SELECT id FROM document_jobs WHERE session_id=?)")) {
                remove.setString(1, id); remove.setString(2, session); remove.executeUpdate();
            }
            return null;
        });
    }
    public Artifact get(String session, String id) {
        return chats.documentTransaction(connection -> { requireSession(connection, session); return read(connection, session, id); });
    }
    public List<Artifact> list(String session, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("无效文档分页");
        return chats.documentTransaction(connection -> {
            requireSession(connection, session);
            try (var query = connection.prepareStatement(SELECT + " WHERE j.session_id=? ORDER BY j.created_at DESC,j.id DESC LIMIT ? OFFSET ?")) {
                query.setString(1, session); query.setInt(2, limit); query.setInt(3, offset);
                try (var rows = query.executeQuery()) { var result = new ArrayList<Artifact>(); while (rows.next()) result.add(artifact(rows)); return List.copyOf(result); }
            }
        });
    }
    public Set<String> readyIds() {
        return chats.documentTransaction(connection -> {
            var result = new HashSet<String>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM document_jobs WHERE status='READY'")) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        });
    }
    public void delete(String session) {
        chats.documentTransaction(connection -> {
            for (String sql : List.of("DELETE FROM document_artifacts WHERE id IN(SELECT id FROM document_jobs WHERE session_id=?)",
                    "DELETE FROM document_jobs WHERE session_id=?")) try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, session); statement.executeUpdate();
            }
            return null;
        });
    }
    private static final String SELECT = "SELECT j.*,COALESCE(a.size,0) AS size,COALESCE(a.sha256,'') AS sha256,COALESCE(a.version,0) AS version,COALESCE(a.metadata,'{}') AS metadata FROM document_jobs j LEFT JOIN document_artifacts a ON a.id=j.id";
    private static Artifact read(Connection connection, String session, String id) throws SQLException {
        try (var query = connection.prepareStatement(SELECT + " WHERE j.session_id=? AND j.id=?")) {
            query.setString(1, session); query.setString(2, id);
            try (var rows = query.executeQuery()) { return rows.next() ? artifact(rows) : null; }
        }
    }
    private static Artifact artifact(ResultSet row) throws SQLException {
        return new Artifact(row.getString("id"), row.getString("session_id"), row.getString("user_message_id"), row.getString("status"),
                row.getString("name"), row.getString("format"), row.getLong("size"), row.getString("sha256"), row.getString("created_at"),
                row.getString("parent_id"), row.getInt("version"), row.getString("error"), metadata(row.getString("metadata")));
    }
    private static com.fasterxml.jackson.databind.JsonNode metadata(String json) throws SQLException {
        try { return DocumentRequest.JSON.readTree(json); } catch (java.io.IOException failure) { throw new SQLException("文档元数据格式无效", failure); }
    }
}
