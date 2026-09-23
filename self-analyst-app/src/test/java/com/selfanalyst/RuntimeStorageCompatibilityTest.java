package com.selfanalyst;

import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.memory.MemoryStore;
import com.selfanalyst.wiki.WikiStore;
import com.selfanalyst.wiki.WikiGenerationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStorageCompatibilityTest {
    @TempDir Path temporary;
    Path root;

    @org.junit.jupiter.api.BeforeEach void layout() throws Exception {
        // macOS 的系统临时目录可经 /var 链接进入；夹具使用真实路径，符合业务存储的无链接约束。
        root = Files.createDirectory(temporary.resolve("data")).toRealPath();
    }

    private Map<String, byte[]> snapshot() throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = root.relativize(file).toString();
                if (!name.equals("app.lock") && !name.equals("storage-format.json")) result.put(name, Files.readAllBytes(file));
            }
        }
        return result;
    }

    @Test void adoptsCurrentStoresWithoutChangingBusinessBytes() throws Exception {
        Path memory = root.resolve("memory");
        MemoryStore.load(memory).save();
        try (var chat = new ChatSessionStore(memory);
             var wiki = new WikiStore(memory.resolve("llm-wiki.db"));
             var generation = new WikiGenerationStore(memory.resolve("wiki-generation.db"));
             var file = new FileWatchStore(memory.resolve("file-watch.db"));
             var events = new Database(root.resolve("events"))) {
            var request = new ChatSessionStore.CreateRequest();
            request.title = "合成测试";
            var message = new ChatSessionStore.Message();
            message.role = "user";
            message.content = "生成测试文档";
            request.initialMessages = java.util.List.of(message);
            var documentSession = chat.create(request);
            var documents = new com.selfanalyst.document.DocumentService(memory, chat);
            documents.generate(documentSession.id, documentSession.messages.getFirst().id, "markdown", "测试文档",
                    "{\"schemaVersion\":1,\"blocks\":[{\"type\":\"paragraph\",\"text\":\"合成内容\"}]}", null, () -> false);
        }
        try (var raw = new com.selfanalyst.events.raw.RawEventStore(root.resolve("events/raw"))) {
            var at = java.time.Instant.parse("2026-09-03T12:00:00Z");
            raw.append(com.selfanalyst.events.raw.RawEvent.create(
                    new com.selfanalyst.events.raw.RawEventIdGenerator(), "test", "test-window",
                    com.selfanalyst.events.raw.RawEventSource.WINDOW, 1,
                    com.selfanalyst.events.raw.RawIngestKind.EVENTS, at, at, 0,
                    Map.of("app", "synthetic"), null, null));
        }
        String session = "a".repeat(32);
        var states = new io.agentscope.core.state.JsonFileAgentStateStore(memory.resolve("agent-state/self-analyst-chat"));
        states.save("desktop", session, "agent_state",
                io.agentscope.core.state.AgentState.builder().userId("desktop").sessionId(session).build());
        states.close();
        Map<String, byte[]> before = snapshot();
        try (var guard = RuntimeStorageGuard.acquire(root)) {
            assertTrue(Files.exists(root.resolve("storage-format.json")));
        }
        Map<String, byte[]> after = snapshot();
        assertEquals(before.keySet(), after.keySet());
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), name));
    }

    @Test void unknownMixedFileRefusesWithoutMarker() throws Exception {
        MemoryStore.load(root.resolve("memory")).save();
        Files.writeString(root.resolve("memory/unknown.bin"), "unknown");
        assertThrows(RuntimeStorageGuard.StorageException.class, () -> RuntimeStorageGuard.acquire(root));
        assertFalse(Files.exists(root.resolve("storage-format.json")));
        assertEquals("unknown", Files.readString(root.resolve("memory/unknown.bin")));
    }

    @Test void refusesUnknownOrCorruptGenerationStoreWithoutChangingBytes() throws Exception {
        Path database = root.resolve("memory/wiki-generation.db");
        try (var generation = new WikiGenerationStore(database)) { }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=99");
        }
        byte[] unknown = Files.readAllBytes(database);
        assertThrows(RuntimeStorageGuard.StorageException.class, () -> RuntimeStorageGuard.acquire(root));
        assertArrayEquals(unknown, Files.readAllBytes(database));
        assertFalse(Files.exists(root.resolve("storage-format.json")));
        Files.writeString(database, "corrupt generation store");
        byte[] corrupt = Files.readAllBytes(database);
        assertThrows(RuntimeStorageGuard.StorageException.class, () -> RuntimeStorageGuard.acquire(root));
        assertArrayEquals(corrupt, Files.readAllBytes(database));
        assertFalse(Files.exists(root.resolve("storage-format.json")));
    }

    @Test void acceptsEmptyKnownLayoutAndRejectsCorruptConfiguration() throws Exception {
        Files.createDirectories(root.resolve("memory"));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/config.toml"), "# empty\n");
        try (var guard = RuntimeStorageGuard.acquire(root)) { }
        Files.delete(root.resolve("storage-format.json"));
        Files.writeString(root.resolve("config/config.toml"), "[broken");
        assertThrows(RuntimeStorageGuard.StorageException.class, () -> RuntimeStorageGuard.acquire(root));
        assertFalse(Files.exists(root.resolve("storage-format.json")));
    }
}
