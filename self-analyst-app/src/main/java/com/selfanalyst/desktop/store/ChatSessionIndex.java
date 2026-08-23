package com.selfanalyst.desktop.store;

interface ChatSessionIndex extends AutoCloseable {

    boolean exists();

    void validate();

    ChatSessionStore.Index load();

    String activeSessionId();

    long generation();

    boolean contains(String sessionId);

    String newestSessionIdExcluding(String sessionId);

    java.util.List<ChatSessionStore.SessionMeta> page(
            int limit,
            String normalizedQuery,
            String cursorUpdatedAt,
            String cursorId);

    void replaceAll(ChatSessionStore.Index index);

    void upsert(ChatSessionStore.SessionMeta meta, String activeSessionId);

    void delete(String sessionId, String activeSessionId);

    void setActive(String activeSessionId);

    void resetCorrupt();

    @Override
    default void close() {
    }
}
