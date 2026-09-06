package com.selfanalyst.desktop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Atomically persists the last successful {@link SummarySnapshot}.
 * Corrupt or missing files are treated as no snapshot.
 */
public class SummarySnapshotStore {

    static final String FILE_NAME = "desktop-summary-snapshot.json";

    private static final Logger log = LoggerFactory.getLogger(SummarySnapshotStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public SummarySnapshotStore(Path memoryDir) {
        this.file = memoryDir.resolve(FILE_NAME);
    }

    public Optional<SummarySnapshot> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SummarySnapshot snapshot = MAPPER.readValue(file.toFile(), SummarySnapshot.class);
            if (snapshot == null || snapshot.current() == null || snapshot.timeline() == null) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.warn("摘要快照无法读取，按无快照处理: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void save(SummarySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("摘要快照写入失败: {}", e.getMessage());
        }
    }

    Path file() {
        return file;
    }
}
