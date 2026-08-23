package com.selfanalyst.desktop.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteChatSessionIndexTest {

    @Test
    void pagedStorePathDoesNotMaterializeTheWholeProjection(@TempDir Path memoryDir) {
        ChatSessionStore setup = new ChatSessionStore(memoryDir);
        ChatSessionStore.Session first = setup.create(new ChatSessionStore.CreateRequest());
        setup.create(new ChatSessionStore.CreateRequest());
        CountingIndex counting = new CountingIndex(
                new SqliteChatSessionIndex(memoryDir.resolve("chat-sessions/index.db")));
        ChatSessionStore paged = new ChatSessionStore(
                memoryDir, ChatSessionStoreIo.nio(), counting);

        assertEquals(1, paged.listIndexPage(1, null, null).sessions().size());
        assertEquals(1, counting.validateCalls);
        assertEquals(0, counting.loadCalls);
        assertEquals(1, counting.pageCalls);
        paged.updateMeta(first.id, "row-only", null, null);
        assertEquals(0, counting.loadCalls);
        assertEquals(1, counting.upsertCalls);
    }

    @Test
    void rowMutationsGenerationAndSqlKeysetSearchAreTransactional(@TempDir Path tempDir) {
        SqliteChatSessionIndex index = new SqliteChatSessionIndex(tempDir.resolve("index.db"));
        ChatSessionStore.SessionMeta a = meta("a".repeat(32), "alpha 100%", "2026-01-03T00:00:00Z");
        ChatSessionStore.SessionMeta b = meta("b".repeat(32), "beta_under", "2026-01-02T00:00:00Z");
        ChatSessionStore.SessionMeta c = meta("c".repeat(32), "gamma", "2026-01-01T00:00:00Z");
        c.summary = "literal % and _ markers";
        ChatSessionStore.Index initial = new ChatSessionStore.Index();
        initial.activeSessionId = a.id;
        initial.generation = 7;
        initial.sessions = new ArrayList<>(List.of(a, b, c));
        index.replaceAll(initial);

        assertEquals(7, index.generation());
        assertEquals(a.id, index.activeSessionId());
        assertEquals(List.of(a.id, b.id), index.page(2, "", null, null).stream()
                .map(meta -> meta.id).toList());
        assertEquals(List.of(c.id), index.page(2, "", b.updatedAt.toString(), b.id).stream()
                .map(meta -> meta.id).toList());
        assertEquals(List.of(a.id), index.page(5, "100%", null, null).stream()
                .map(meta -> meta.id).toList());
        assertEquals(List.of(c.id), index.page(5, "_ markers", null, null).stream()
                .map(meta -> meta.id).toList());

        b.title = "beta updated";
        b.updatedAt = Instant.parse("2026-01-04T00:00:00Z");
        index.upsert(b, b.id);
        assertEquals(8, index.generation());
        assertEquals(b.id, index.activeSessionId());
        assertEquals("beta updated", index.load().sessions.getFirst().title);

        index.setActive(a.id);
        assertEquals(8, index.generation(), "active-only updates must not stale cursors");

        ChatSessionStore.SessionMeta invalid = meta("d".repeat(32), "invalid", "2026-01-05T00:00:00Z");
        invalid.id = null;
        assertThrows(IllegalStateException.class, () -> index.upsert(invalid, c.id));
        assertEquals(8, index.generation(), "failed row writes must roll back generation");
        assertEquals(a.id, index.activeSessionId(), "failed row writes must roll back active");

        index.delete(a.id, b.id);
        assertEquals(9, index.generation());
        assertEquals(b.id, index.activeSessionId());
        assertTrue(!index.contains(a.id));
        assertEquals(c.id, index.newestSessionIdExcluding(b.id));
    }

    private static ChatSessionStore.SessionMeta meta(String id, String title, String updatedAt) {
        ChatSessionStore.SessionMeta meta = new ChatSessionStore.SessionMeta();
        meta.id = id;
        meta.title = title;
        meta.createdAt = Instant.parse(updatedAt);
        meta.updatedAt = Instant.parse(updatedAt);
        meta.source = "manual";
        meta.memoryPolicy = "smart";
        return meta;
    }

    private static final class CountingIndex implements ChatSessionIndex {
        private final ChatSessionIndex delegate;
        private int validateCalls;
        private int loadCalls;
        private int pageCalls;
        private int upsertCalls;

        private CountingIndex(ChatSessionIndex delegate) {
            this.delegate = delegate;
        }

        @Override public boolean exists() { return delegate.exists(); }
        @Override public void validate() { validateCalls++; delegate.validate(); }
        @Override public ChatSessionStore.Index load() { loadCalls++; return delegate.load(); }
        @Override public String activeSessionId() { return delegate.activeSessionId(); }
        @Override public long generation() { return delegate.generation(); }
        @Override public boolean contains(String id) { return delegate.contains(id); }
        @Override public String newestSessionIdExcluding(String id) {
            return delegate.newestSessionIdExcluding(id);
        }
        @Override public List<ChatSessionStore.SessionMeta> page(
                int limit, String query, String updatedAt, String id) {
            pageCalls++;
            return delegate.page(limit, query, updatedAt, id);
        }
        @Override public void replaceAll(ChatSessionStore.Index index) { delegate.replaceAll(index); }
        @Override public void upsert(ChatSessionStore.SessionMeta meta, String active) {
            upsertCalls++;
            delegate.upsert(meta, active);
        }
        @Override public void delete(String id, String active) { delegate.delete(id, active); }
        @Override public void setActive(String active) { delegate.setActive(active); }
        @Override public void resetCorrupt() { delegate.resetCorrupt(); }
    }
}
