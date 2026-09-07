package com.selfanalyst.events.raw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/** 从健康 sealed manifest 旁路重建可恢复 catalog，不修改原始分区。 */
public final class RawCatalogRebuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RawCatalogRebuilder() {}

    public static Path rebuild(Path configuredRawRoot) {
        try {
            Path rawRoot = RawPartitionCatalog.validateRawRootForRead(configuredRawRoot);
            String id = UUID.randomUUID().toString();
            Path stagingRoot = rawRoot.resolve(".catalog-rebuilding-" + id);
            Files.createDirectory(stagingRoot);
            Path stagedCatalog;
            try (RawPartitionCatalog staging = new RawPartitionCatalog(stagingRoot);
                 var manifests = Files.walk(rawRoot, 2)) {
                for (Path manifest : manifests
                        .filter(path -> path.getFileName().toString().endsWith(".manifest.json"))
                        .sorted(Comparator.naturalOrder()).toList()) {
                    JsonNode json = MAPPER.readTree(Files.readString(manifest));
                    String month = requiredText(json, "partitionMonth");
                    String databaseFile = requiredText(json, "databaseFile");
                    Path database = manifest.getParent().resolve(databaseFile).normalize();
                    if (!database.startsWith(rawRoot) || !Files.isRegularFile(database)) {
                        throw new IllegalStateException("manifest 数据库路径越界或不存在: " + manifest);
                    }
                    String expectedHash = requiredText(json, "fileSha256");
                    if (!expectedHash.equals(RawEventStore.fileSha256(database))) {
                        throw new IllegalStateException("manifest 文件摘要不匹配: " + manifest);
                    }
                    String relative = rawRoot.relativize(database).toString().replace('\\', '/');
                    staging.insert(new RawPartitionMetadata(month, relative,
                            optionalInstant(json, "receivedStart"),
                            optionalInstant(json, "receivedEnd"), RawPartitionStatus.SEALED,
                            json.path("eventCount").asLong(), optionalText(json, "firstEventId"),
                            optionalText(json, "lastEventId"), json.path("fileSizeBytes").asLong(),
                            expectedHash, optionalInstant(json, "verifiedAt"),
                            json.path("schemaVersion").asInt()));
                }
                stagedCatalog = staging.catalogPath();
            }

            Path catalog = rawRoot.resolve("catalog.db");
            Path backup = rawRoot.resolve("catalog.db.backup-" + id);
            boolean hadCatalog = Files.exists(catalog);
            if (hadCatalog) Files.move(catalog, backup);
            try {
                moveReplacing(stagedCatalog, catalog);
            } catch (Exception replacementFailure) {
                if (hadCatalog && Files.exists(backup) && !Files.exists(catalog)) {
                    Files.move(backup, catalog);
                }
                throw replacementFailure;
            }
            Files.deleteIfExists(stagingRoot);
            return hadCatalog ? backup : null;
        } catch (Exception failure) {
            throw new IllegalStateException("无法从 manifest 重建原始分区 catalog", failure);
        }
    }

    private static void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requiredText(JsonNode json, String field) {
        String value = optionalText(json, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("manifest 缺少字段: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant optionalInstant(JsonNode json, String field) {
        String value = optionalText(json, field);
        return value == null ? null : Instant.parse(value);
    }
}
