package com.selfanalyst.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** 永久原始事件配置的共享语义校验。 */
public final class RawConfigValidator {

    public static final int MAX_QUERY_RANGE_DAYS = 366;
    public static final int MAX_QUERY_PAGE_SIZE = 10_000;
    public static final long MAX_LOW_DISK_THRESHOLD_BYTES = 1L << 50;
    public static final int MAX_PROJECTOR_BATCH_SIZE = 10_000;

    private static final List<String> UNSUPPORTED_RETENTION_KEYS = List.of(
            "aw.raw.enabled",
            "aw.raw.retentionDays",
            "aw.raw.maxPartitions",
            "aw.raw.autoDelete");

    private RawConfigValidator() {}

    /** 校验用户提供的 raw 配置；缺失项使用受支持键中声明的默认值。 */
    public static void validate(Properties userProperties) {
        Properties effective = new Properties();
        SupportedKeys.defaults().forEach(effective::setProperty);
        if (userProperties != null) {
            rejectUnsupportedRetentionKeys(userProperties);
            effective.putAll(userProperties);
        }

        Path dir;
        try {
            String value = effective.getProperty("aw.raw.dir", "").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("aw.raw.dir 不能为空");
            }
            dir = Path.of(value);
        } catch (InvalidPathException invalidPath) {
            throw new IllegalArgumentException("aw.raw.dir 不是可解析的本地路径", invalidPath);
        }

        int maxRangeDays = positiveInt(effective, "aw.raw.query.maxRangeDays",
                MAX_QUERY_RANGE_DAYS);
        int maxPageSize = positiveInt(effective, "aw.raw.query.maxPageSize",
                MAX_QUERY_PAGE_SIZE);
        long warnBytes = positiveLong(effective, "aw.raw.lowDisk.warnBytes",
                MAX_LOW_DISK_THRESHOLD_BYTES);
        long blockBytes = positiveLong(effective, "aw.raw.lowDisk.blockBytes",
                MAX_LOW_DISK_THRESHOLD_BYTES);
        RawIntegrityPolicy integrityPolicy = RawIntegrityPolicy.parse(
                effective.getProperty("aw.raw.integrity.verifyOnStartup"));
        int projectorBatchSize = positiveInt(effective, "aw.raw.projector.batchSize",
                MAX_PROJECTOR_BATCH_SIZE);
        validate(dir, maxRangeDays, maxPageSize, warnBytes, blockBytes,
                integrityPolicy, projectorBatchSize);
    }

    /** 校验已经解析并应用环境变量覆盖后的运行时配置。 */
    public static void validate(Path dir, int maxRangeDays, int maxPageSize,
                                long warnBytes, long blockBytes,
                                RawIntegrityPolicy integrityPolicy,
                                int projectorBatchSize) {
        if (dir == null) {
            throw new IllegalArgumentException("aw.raw.dir 不能为空");
        }
        requireRange("aw.raw.query.maxRangeDays", maxRangeDays, MAX_QUERY_RANGE_DAYS);
        requireRange("aw.raw.query.maxPageSize", maxPageSize, MAX_QUERY_PAGE_SIZE);
        requireRange("aw.raw.lowDisk.warnBytes", warnBytes,
                MAX_LOW_DISK_THRESHOLD_BYTES);
        requireRange("aw.raw.lowDisk.blockBytes", blockBytes,
                MAX_LOW_DISK_THRESHOLD_BYTES);
        if (blockBytes >= warnBytes) {
            throw new IllegalArgumentException(
                    "aw.raw.lowDisk.blockBytes 必须小于 aw.raw.lowDisk.warnBytes");
        }
        if (integrityPolicy == null) {
            throw new IllegalArgumentException(
                    "aw.raw.integrity.verifyOnStartup 必须是 latest 或 all");
        }
        requireRange("aw.raw.projector.batchSize", projectorBatchSize,
                MAX_PROJECTOR_BATCH_SIZE);
    }

    public static void rejectUnsupportedRetentionKeys(Properties properties) {
        for (String key : UNSUPPORTED_RETENTION_KEYS) {
            if (properties.containsKey(key)) {
                throw new IllegalArgumentException(
                        key + " 不受支持；嵌入式模式固定启用永久原始事件且不自动删除分区");
            }
        }
    }

    private static int positiveInt(Properties properties, String key, int max) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, "").trim());
            requireRange(key, value, max);
            return value;
        } catch (NumberFormatException invalidNumber) {
            throw new IllegalArgumentException(key + " 必须是正整数且不大于 " + max,
                    invalidNumber);
        }
    }

    private static long positiveLong(Properties properties, String key, long max) {
        try {
            long value = Long.parseLong(properties.getProperty(key, "").trim());
            requireRange(key, value, max);
            return value;
        } catch (NumberFormatException invalidNumber) {
            throw new IllegalArgumentException(key + " 必须是正整数且不大于 " + max,
                    invalidNumber);
        }
    }

    private static void requireRange(String key, long value, long max) {
        if (value <= 0 || value > max) {
            throw new IllegalArgumentException(key + " 必须在 1 到 " + max + " 之间");
        }
    }
}
