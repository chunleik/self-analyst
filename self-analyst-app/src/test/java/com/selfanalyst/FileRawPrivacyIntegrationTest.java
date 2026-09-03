package com.selfanalyst;

import com.selfanalyst.aw.AwServer;
import com.selfanalyst.aw.raw.RawEvent;
import com.selfanalyst.aw.raw.RawEventIdGenerator;
import com.selfanalyst.aw.raw.RawEventSource;
import com.selfanalyst.aw.raw.RawEventStore;
import com.selfanalyst.aw.raw.RawIngestKind;
import com.selfanalyst.file.FileFilterConfig;
import com.selfanalyst.file.FileWatchStore;
import com.selfanalyst.file.FileWatcher;
import com.selfanalyst.file.PathFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRawPrivacyIntegrationTest {
    private static final String SECRET = "SELF_ANALYST_FILE_BODY_SECRET_6B22";

    @Test
    void fileBodyNeverReachesMetadataStoresRawProjectionManifestOrLogs(@TempDir Path dir)
            throws Exception {
        Path rawDir = dir.resolve("raw");
        seedManifest(rawDir);
        AwServer server = new AwServer(dir.resolve("aw"), rawDir, 0,
                31, 1000, 1, 1, 1000);
        server.start(0);
        Path watched = Files.createDirectory(dir.resolve("watched"));
        Path fileDb = dir.resolve("file-watch.db");
        FileWatcher watcher = null;
        try (FileWatchStore fileStore = new FileWatchStore(fileDb)) {
            PathFilter filter = new PathFilter(FileFilterConfig.parse(
                    0, List.of(), List.of(), List.of("txt"), false));
            watcher = new FileWatcher(fileStore, filter, List.of(watched),
                    "http://127.0.0.1:" + server.port(), 0, 0);
            watcher.start();
            long registrationDeadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < registrationDeadline
                    && !watcher.isRegistrationComplete()) Thread.sleep(50);
            assertTrue(watcher.isRegistrationComplete());
            Path secretFile = watched.resolve("notes.txt");
            Files.writeString(secretFile, SECRET, StandardCharsets.UTF_8);

            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline
                    && server.rawEventStore().count(YearMonth.now(ZoneOffset.UTC)) == 0) {
                Thread.sleep(100);
            }
            assertTrue(server.rawEventStore().count(YearMonth.now(ZoneOffset.UTC)) > 0);
        } finally {
            if (watcher != null) watcher.shutdown();
            server.stop();
        }

        for (Path root : List.of(rawDir, dir.resolve("aw"), fileDb)) {
            if (!Files.exists(root)) continue;
            if (Files.isRegularFile(root)) {
                assertNoSecret(root);
            } else try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) assertNoSecret(file);
            }
        }
        assertFalse(server.serverLog().getEntries().toString().contains(SECRET));
    }

    private static void seedManifest(Path rawDir) throws Exception {
        RawEventIdGenerator ids = new RawEventIdGenerator();
        try (RawEventStore store = new RawEventStore(rawDir)) {
            for (String time : List.of("2026-07-31T23:59:59Z", "2026-08-01T00:00:00Z")) {
                Instant instant = Instant.parse(time);
                store.append(RawEvent.create(ids, time, "safe", RawEventSource.FILE, 1,
                        RawIngestKind.HEARTBEAT, instant, instant, 1,
                        Map.of("path", "safe.txt"), null, null));
            }
        }
    }

    private static void assertNoSecret(Path file) throws Exception {
        String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(bytes.contains(SECRET), file.toString());
    }
}
