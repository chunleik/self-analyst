package com.selfanalyst.events.raw;

import java.time.Instant;

/** catalog 中一条月度原始分区记录。 */
public record RawPartitionMetadata(
        String partitionMonth,
        String relativePath,
        Instant receivedStart,
        Instant receivedEnd,
        RawPartitionStatus status,
        long eventCount,
        String firstEventId,
        String lastEventId,
        long sizeBytes,
        String fileSha256,
        Instant verifiedAt,
        int schemaVersion) {
}
