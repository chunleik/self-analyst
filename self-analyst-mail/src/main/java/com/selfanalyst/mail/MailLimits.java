package com.selfanalyst.mail;

import java.time.Duration;

public record MailLimits(int maxSearchMessages, int maxBodyCodePoints, int maxDecodedBytes,
                         int maxMimeParts, int maxMimeDepth, int maxWireBytes, Duration timeout) {
    public static final int MAX_LISTED_FOLDERS = 256;
    public static final int MAX_FOLDER_NAME_LENGTH = 256;

    public static MailLimits defaults() {
        return new MailLimits(500, 20_000, 256_000, 64, 12, 2_000_000, Duration.ofSeconds(30));
    }
    public MailLimits {
        if (maxSearchMessages < 1 || maxSearchMessages > 2000 || maxBodyCodePoints < 1 || maxBodyCodePoints > 20_000
                || maxDecodedBytes < 1 || maxDecodedBytes > 1_000_000 || maxMimeParts < 1 || maxMimeParts > 128
                || maxMimeDepth < 1 || maxMimeDepth > 20 || maxWireBytes < 1024 || maxWireBytes > 8_000_000
                || timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Invalid mail limits");
        }
    }
}
