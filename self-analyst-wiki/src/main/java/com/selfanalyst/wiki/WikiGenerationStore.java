package com.selfanalyst.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 专用连接和短事务保护跨进程的周期准入。检查点只接受调用方已验证的派生 JSON；
 * 事实引用与语义仍由生成管线在保存前和恢复后验证。账本独立于检查点生命周期。
 */
public final class WikiGenerationStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;
    public static final long OUTPUT_RESERVE_TOKENS = 4096;
    public static final int MAX_PAYLOAD_BYTES = 512 * 1024;
    public static final Duration COMPLETED_RETENTION = Duration.ofDays(7);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> STAGES = Set.of("DIRECT", "LEAF", "MERGE", "FINAL");
    // 载荷版本独立于SQLite表结构：v1任务片段，v2主题卡片；语义兼容由管线的generation key隔离。
    private static final Set<Integer> CHECKPOINT_PAYLOAD_VERSIONS = Set.of(1, 2);
    private static final Set<String> PRIVATE_FIELDS = Set.of("prompt", "systemprompt", "rawresponse",
            "rawmodelresponse", "apikey", "authorization", "credentials", "secret");
    private static final Map<String, String> SCHEMA = schema();
    private final Connection connection;
    private final Clock clock;

    public record BudgetLimits(int maxCalls, long maxTokens, long outputTokenReserve) {
        public BudgetLimits {
            if (maxCalls < 1 || maxTokens < 1 || outputTokenReserve < 1) {
                throw new IllegalArgumentException("Invalid period budget limits");
            }
        }
        public BudgetLimits(int maxCalls, long maxTokens) { this(maxCalls, maxTokens, OUTPUT_RESERVE_TOKENS); }
        public static BudgetLimits defaults() { return new BudgetLimits(12, 256000); }
    }

    public record Reservation(String callId, String periodKey, long reservedTokens) {}

    public record BudgetSnapshot(int calls, long tokens, int unsettledCalls, long requiredTokens,
                                 long reservedTokens, int estimatedCalls) {
        public BudgetSnapshot(int calls, long tokens, int unsettledCalls, long requiredTokens) {
            this(calls, tokens, unsettledCalls, requiredTokens, 0, 0);
        }
        public BudgetSnapshot(int calls, long tokens, int unsettledCalls) { this(calls, tokens, unsettledCalls, 0); }
        public boolean canReserve(long estimatedInputTokens, BudgetLimits limits) {
            return permits(calls, tokens, reservationTokens(estimatedInputTokens, limits.outputTokenReserve()), limits);
        }
        public boolean canResume(BudgetLimits limits) {
            return permits(calls, tokens, Math.max(requiredTokens, limits.outputTokenReserve()), limits);
        }
    }

    public record Checkpoint(String stage, String payloadJson) {}

    public WikiGenerationStore(Path dbPath) { this(dbPath, Clock.systemUTC()); }

    // 可控时钟用于重启、跨日和清理的确定性测试。
    WikiGenerationStore(Path dbPath, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        Connection opened = null;
        try {
            boolean existing = dbPath != null && Files.exists(dbPath, LinkOption.NOFOLLOW_LINKS);
            if (existing && !Files.isRegularFile(dbPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid Wiki generation database path");
            }
            if (dbPath != null) Files.createDirectories(dbPath.toAbsolutePath().getParent());
            Class.forName("org.sqlite.JDBC");
            opened = DriverManager.getConnection(dbPath == null ? "jdbc:sqlite::memory:"
                    : "jdbc:sqlite:" + dbPath.toAbsolutePath());
            connection = opened;
            execute("PRAGMA busy_timeout=10000");
            execute("PRAGMA foreign_keys=ON");
            transaction(() -> {
                int version = schemaVersion(connection);
                if (version == 0 && !existing && userTables(connection).isEmpty()) {
                    for (String ddl : SCHEMA.values()) execute(ddl);
                    execute("PRAGMA user_version=" + SCHEMA_VERSION);
                } else {
                    verifyConnection(connection);
                }
                return null;
            });
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
        } catch (Exception error) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            }
            throw new IllegalStateException("Cannot initialize Wiki generation storage", error);
        }
    }

    public static WikiGenerationStore inMemory() { return new WikiGenerationStore(null); }

    /** 仅逻辑周期；不包含执行日期、Wiki entry ID、输入或模型配置。 */
    public static String periodKey(WikiPeriod period) {
        Objects.requireNonNull(period);
        Objects.requireNonNull(period.level());
        Objects.requireNonNull(period.start());
        Objects.requireNonNull(period.end());
        if (!period.start().isBefore(period.end())) throw new IllegalArgumentException("Invalid Wiki period");
        String timezone = ZoneId.of(period.timezone()).getId();
        return hash(period.level().name() + "\n" + period.start() + "\n" + period.end() + "\n" + timezone);
    }

    public synchronized Reservation reserve(String periodKey, String generationKey, String nodeKey,
                                            long estimatedInputTokens, BudgetLimits limits) {
        requireHash(periodKey);
        Objects.requireNonNull(limits);
        String generation = keyHash(generationKey);
        String node = keyHash(nodeKey);
        long reserved = reservationTokens(estimatedInputTokens, limits.outputTokenReserve());
        // 准入拒绝也提交 required_tokens，供重启时判断调额是否足以恢复。
        Object outcome = transaction(() -> {
            update("INSERT OR IGNORE INTO generation_periods VALUES (?,0,0,0,0)", periodKey);
            BudgetSnapshot before = readSnapshot(periodKey);
            if (!permits(before.calls(), before.tokens(), reserved, limits)) {
                update("UPDATE generation_periods SET required_tokens=? WHERE period_key=?", reserved, periodKey);
                return new WikiPeriodBudgetException(readSnapshot(periodKey), limits);
            }
            ensureGeneration(generation, periodKey);
            String callId = UUID.randomUUID().toString();
            update("""
                    INSERT INTO generation_calls
                    (call_id,period_key,generation_key,node_key,reserved_tokens,accounted_tokens,state,created_at)
                    VALUES (?,?,?,?,?,?,'RESERVED',?)
                    """, callId, periodKey, generation, node, reserved, reserved, clock.millis());
            update("""
                    UPDATE generation_periods SET calls=calls+1,tokens=tokens+?,
                    unsettled_calls=unsettled_calls+1,required_tokens=0 WHERE period_key=?
                    """, reserved, periodKey);
            return new Reservation(callId, periodKey, reserved);
        });
        if (outcome instanceof WikiPeriodBudgetException rejected) throw rejected;
        return (Reservation) outcome;
    }

    /** 调用方必须已确认请求从未发送；超时、取消或未知结果均不得使用此方法。 */
    public synchronized void cancelBeforeSend(String callId) {
        transaction(() -> {
            Call call = readCall(callId);
            if (call.state().equals("CANCELLED")) return null;
            if (call.state().equals("ACTUAL")) throw new IllegalStateException("Call already has actual usage");
            update("UPDATE generation_calls SET state='CANCELLED',accounted_tokens=0,settled_at=? WHERE call_id=?",
                    clock.millis(), callId);
            update("""
                    UPDATE generation_periods SET calls=calls-1,tokens=tokens-?,unsettled_calls=unsettled_calls-?
                    WHERE period_key=?
                    """, call.accountedTokens(), call.state().equals("RESERVED") ? 1 : 0, call.periodKey());
            return null;
        });
    }

    /** null/null 表示无真实 usage，保守保留预留；稍后取得真实 usage 时允许一次升级。 */
    public synchronized void settle(String callId, Long inputTokens, Long outputTokens) {
        validateUsage(inputTokens, outputTokens);
        transaction(() -> { settleCall(readCall(callId), inputTokens, outputTokens); return null; });
    }

    /** 成功检查点与该请求结算原子提交，任何一步失败均回滚。 */
    public synchronized void completeCheckpoint(String callId, String generationKey, String nodeKey,
                                               String stage, String payloadJson,
                                               Long inputTokens, Long outputTokens) {
        String generation = keyHash(generationKey);
        String node = keyHash(nodeKey);
        validatePayload(stage, payloadJson);
        validateUsage(inputTokens, outputTokens);
        transaction(() -> {
            if (callId != null) {
                Call call = readCall(callId);
                if (!call.generationKey().equals(generation) || !call.nodeKey().equals(node)
                        || call.state().equals("CANCELLED")) {
                    throw new IllegalArgumentException("Checkpoint does not match its call");
                }
                settleCall(call, inputTokens, outputTokens);
            } else if (inputTokens != null || outputTokens != null) {
                throw new IllegalArgumentException("Usage requires a call");
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT stage,payload_json FROM generation_checkpoints WHERE generation_key=? AND node_key=?")) {
                query.setString(1, generation); query.setString(2, node);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        if (!rows.getString(1).equals(stage) || !rows.getString(2).equals(payloadJson)) {
                            throw new IllegalStateException("Checkpoint already exists with different content");
                        }
                        return null;
                    }
                }
            }
            update("INSERT INTO generation_checkpoints VALUES (?,?,?,?,?,?)",
                    generation, node, stage, payloadJson, hash(payloadJson), clock.millis());
            return null;
        });
    }

    public synchronized Optional<Checkpoint> loadCheckpoint(String generationKey, String nodeKey) {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT stage,payload_json,payload_hash FROM generation_checkpoints
                WHERE generation_key=? AND node_key=?
                """)) {
            query.setString(1, keyHash(generationKey)); query.setString(2, keyHash(nodeKey));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String stage = rows.getString(1), payload = rows.getString(2);
                validatePayload(stage, payload);
                if (!hash(payload).equals(rows.getString(3))) throw new IllegalStateException("Corrupt Wiki checkpoint");
                return Optional.of(new Checkpoint(stage, payload));
            }
        } catch (SQLException error) { throw storageError(error); }
    }

    public synchronized BudgetSnapshot snapshot(String periodKey) {
        requireHash(periodKey);
        try { return readSnapshot(periodKey); } catch (SQLException error) { throw storageError(error); }
    }

    public synchronized boolean canResume(String periodKey, BudgetLimits limits) {
        return snapshot(periodKey).canResume(limits);
    }

    /** 仅正式 Wiki 发布完成后调用；未发布 FINAL 和叶检查点不会按时间清理。 */
    public synchronized void markPublished(String generationKey) {
        String generation = keyHash(generationKey);
        transaction(() -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM generation_checkpoints WHERE generation_key=? AND stage IN ('DIRECT','FINAL')")) {
                query.setString(1, generation);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Cannot publish without a complete checkpoint");
                }
            }
            update("UPDATE generation_runs SET published_at=COALESCE(published_at,?) WHERE generation_key=?",
                    clock.millis(), generation);
            return null;
        });
    }

    public int cleanupPublished() { return cleanupPublished(COMPLETED_RETENTION); }

    public synchronized int cleanupPublished(Duration retention) {
        Objects.requireNonNull(retention);
        if (retention.isNegative()) throw new IllegalArgumentException("Invalid checkpoint retention");
        long cutoff = clock.instant().minus(retention).toEpochMilli();
        return transaction(() -> update("""
                DELETE FROM generation_checkpoints WHERE generation_key IN
                (SELECT generation_key FROM generation_runs WHERE published_at IS NOT NULL AND published_at<=?)
                """, cutoff));
    }

    /** 格式准入用纯只读连接；不建库、不迁移，不恢复日志或覆盖损坏文件。 */
    public static void verifyReadOnly(Path dbPath) throws IOException {
        if (!Files.exists(dbPath, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(dbPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid generation database");
        for (String suffix : new String[]{"-wal", "-journal"}) {
            Path journal = dbPath.resolveSibling(dbPath.getFileName() + suffix);
            if (Files.exists(journal) && Files.size(journal) > 0) throw new IOException("Generation database has pending journal");
        }
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection readOnly = DriverManager.getConnection("jdbc:sqlite:"
                    + dbPath.toAbsolutePath().toUri().toASCIIString() + "?mode=ro&immutable=1")) {
                verifyConnection(readOnly);
            }
        } catch (Exception error) { throw new IOException("Incompatible Wiki generation database", error); }
    }

    @Override public synchronized void close() {
        try { connection.close(); } catch (SQLException error) { throw storageError(error); }
    }

    private static Map<String, String> schema() {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("generation_periods", """
                CREATE TABLE generation_periods (
                  period_key TEXT PRIMARY KEY NOT NULL CHECK(length(period_key)=64),
                  calls INTEGER NOT NULL CHECK(calls>=0), tokens INTEGER NOT NULL CHECK(tokens>=0),
                  unsettled_calls INTEGER NOT NULL CHECK(unsettled_calls>=0 AND unsettled_calls<=calls),
                  required_tokens INTEGER NOT NULL CHECK(required_tokens>=0)
                )
                """);
        tables.put("generation_runs", """
                CREATE TABLE generation_runs (
                  generation_key TEXT PRIMARY KEY NOT NULL CHECK(length(generation_key)=64),
                  period_key TEXT NOT NULL REFERENCES generation_periods(period_key),
                  created_at INTEGER NOT NULL, published_at INTEGER
                )
                """);
        tables.put("generation_calls", """
                CREATE TABLE generation_calls (
                  call_id TEXT PRIMARY KEY NOT NULL CHECK(length(call_id)=36),
                  period_key TEXT NOT NULL REFERENCES generation_periods(period_key),
                  generation_key TEXT NOT NULL REFERENCES generation_runs(generation_key),
                  node_key TEXT NOT NULL CHECK(length(node_key)=64),
                  reserved_tokens INTEGER NOT NULL CHECK(reserved_tokens>=1),
                  accounted_tokens INTEGER NOT NULL CHECK(accounted_tokens>=0),
                  state TEXT NOT NULL CHECK(state IN ('RESERVED','ESTIMATED','ACTUAL','CANCELLED')),
                  input_tokens INTEGER CHECK(input_tokens>=0), output_tokens INTEGER CHECK(output_tokens>=0),
                  created_at INTEGER NOT NULL, settled_at INTEGER
                )
                """);
        tables.put("generation_checkpoints", """
                CREATE TABLE generation_checkpoints (
                  generation_key TEXT NOT NULL REFERENCES generation_runs(generation_key),
                  node_key TEXT NOT NULL CHECK(length(node_key)=64),
                  stage TEXT NOT NULL CHECK(stage IN ('DIRECT','LEAF','MERGE','FINAL')),
                  payload_json TEXT NOT NULL CHECK(length(CAST(payload_json AS BLOB))<=524288),
                  payload_hash TEXT NOT NULL CHECK(length(payload_hash)=64), created_at INTEGER NOT NULL,
                  PRIMARY KEY(generation_key,node_key)
                )
                """);
        return tables;
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private static Map<String, String> userTables(Connection connection) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND type='table'")) {
            while (rows.next()) result.put(rows.getString(1), rows.getString(2));
        }
        return result;
    }

    private static void verifyConnection(Connection connection) throws SQLException {
        if (schemaVersion(connection) != SCHEMA_VERSION) throw new SQLException("Unsupported generation schema version");
        Map<String, String> actual = userTables(connection);
        if (!actual.keySet().equals(SCHEMA.keySet())) throw new SQLException("Unrecognized generation schema");
        for (String table : SCHEMA.keySet()) {
            if (!normalizeSql(SCHEMA.get(table)).equals(normalizeSql(actual.get(table)))) {
                throw new SQLException("Unrecognized generation table structure");
            }
        }
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type IN ('view','trigger') OR (type='index' AND sql IS NOT NULL) LIMIT 1")) {
                if (rows.next()) throw new SQLException("Unexpected generation schema objects");
            }
            try (ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) throw new SQLException("Corrupt generation database");
            }
            try (ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw new SQLException("Corrupt generation references");
            }
            try (ResultSet rows = statement.executeQuery("""
                    SELECT 1 FROM generation_periods p LEFT JOIN (
                      SELECT period_key,SUM(CASE WHEN state='CANCELLED' THEN 0 ELSE 1 END) calls,
                      SUM(accounted_tokens) tokens,SUM(CASE WHEN state='RESERVED' THEN 1 ELSE 0 END) unsettled
                      FROM generation_calls GROUP BY period_key
                    ) c ON c.period_key=p.period_key
                    WHERE p.calls!=COALESCE(c.calls,0) OR p.tokens!=COALESCE(c.tokens,0)
                      OR p.unsettled_calls!=COALESCE(c.unsettled,0) LIMIT 1
                    """)) {
                if (rows.next()) throw new SQLException("Inconsistent generation ledger");
            }
            try (ResultSet rows = statement.executeQuery("""
                    SELECT 1 FROM generation_calls c JOIN generation_runs r ON r.generation_key=c.generation_key
                    WHERE c.period_key!=r.period_key
                      OR (c.state IN ('RESERVED','ESTIMATED') AND c.accounted_tokens!=c.reserved_tokens)
                      OR (c.state='CANCELLED' AND c.accounted_tokens!=0)
                      OR (c.state!='ACTUAL' AND (c.input_tokens IS NOT NULL OR c.output_tokens IS NOT NULL))
                      OR (c.state='RESERVED' AND c.settled_at IS NOT NULL)
                      OR (c.state!='RESERVED' AND c.settled_at IS NULL)
                      OR (c.state='ACTUAL' AND (c.input_tokens IS NULL OR c.output_tokens IS NULL
                          OR c.accounted_tokens!=c.input_tokens+c.output_tokens)) LIMIT 1
                    """)) {
                if (rows.next()) throw new SQLException("Inconsistent generation call");
            }
            try (ResultSet rows = statement.executeQuery("SELECT stage,payload_json,payload_hash FROM generation_checkpoints")) {
                while (rows.next()) {
                    try {
                        validatePayload(rows.getString(1), rows.getString(2));
                        if (!hash(rows.getString(2)).equals(rows.getString(3))) throw new IllegalArgumentException();
                    } catch (RuntimeException invalid) { throw new SQLException("Corrupt generation checkpoint"); }
                }
            }
        }
    }

    private static String normalizeSql(String sql) { return sql == null ? "" : sql.replaceAll("\\s+", " ").trim(); }

    private void ensureGeneration(String generation, String periodKey) throws SQLException {
        update("INSERT OR IGNORE INTO generation_runs VALUES (?,?,?,NULL)", generation, periodKey, clock.millis());
        try (PreparedStatement query = connection.prepareStatement("SELECT period_key FROM generation_runs WHERE generation_key=?")) {
            query.setString(1, generation);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !periodKey.equals(rows.getString(1))) {
                    throw new IllegalArgumentException("Generation key reused for a different period");
                }
            }
        }
    }

    private BudgetSnapshot readSnapshot(String periodKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT calls,tokens,unsettled_calls,required_tokens,
                  (SELECT COALESCE(SUM(accounted_tokens),0) FROM generation_calls c WHERE c.period_key=p.period_key
                     AND c.state IN ('RESERVED','ESTIMATED')) reserved_tokens,
                  (SELECT COUNT(*) FROM generation_calls c WHERE c.period_key=p.period_key AND c.state='ESTIMATED') estimated_calls
                FROM generation_periods p WHERE period_key=?
                """)) {
            query.setString(1, periodKey);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new BudgetSnapshot(rows.getInt(1), rows.getLong(2), rows.getInt(3), rows.getLong(4),
                        rows.getLong(5), rows.getInt(6))
                        : new BudgetSnapshot(0, 0, 0, 0);
            }
        }
    }

    private record Call(String callId, String periodKey, String generationKey, String nodeKey,
                        long accountedTokens, String state) {}

    private Call readCall(String callId) throws SQLException {
        if (callId == null || !callId.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid call ID");
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT period_key,generation_key,node_key,accounted_tokens,state FROM generation_calls WHERE call_id=?
                """)) {
            query.setString(1, callId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown generation call");
                return new Call(callId, rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4), rows.getString(5));
            }
        }
    }

    private void settleCall(Call call, Long inputTokens, Long outputTokens) throws SQLException {
        if (call.state().equals("CANCELLED")) {
            if (inputTokens == null) return;
            throw new IllegalStateException("Cannot settle actual usage for a cancelled call");
        }
        if (call.state().equals("ACTUAL") || (call.state().equals("ESTIMATED") && inputTokens == null)) return;
        long actual = inputTokens == null ? call.accountedTokens() : Math.addExact(inputTokens, outputTokens);
        BudgetSnapshot before = readSnapshot(call.periodKey());
        long total = Math.addExact(Math.subtractExact(before.tokens(), call.accountedTokens()), actual);
        update("""
                UPDATE generation_calls SET state=?,accounted_tokens=?,input_tokens=?,output_tokens=?,settled_at=? WHERE call_id=?
                """, inputTokens == null ? "ESTIMATED" : "ACTUAL", actual, inputTokens, outputTokens, clock.millis(), call.callId());
        update("UPDATE generation_periods SET tokens=?,unsettled_calls=unsettled_calls-? WHERE period_key=?",
                total, call.state().equals("RESERVED") ? 1 : 0, call.periodKey());
    }

    private static boolean permits(int calls, long tokens, long reserved, BudgetLimits limits) {
        return calls < limits.maxCalls() && tokens <= limits.maxTokens() && reserved <= limits.maxTokens() - tokens;
    }

    private static long reservationTokens(long input, long outputReserve) {
        if (input < 0 || input > Long.MAX_VALUE - outputReserve) throw new IllegalArgumentException("Invalid token estimate");
        return input + outputReserve;
    }

    private static void validateUsage(Long input, Long output) {
        if ((input == null) != (output == null) || (input != null && (input < 0 || output < 0 || input > Long.MAX_VALUE - output))) {
            throw new IllegalArgumentException("Invalid token usage");
        }
    }

    private static void validatePayload(String stage, String payload) {
        if (stage == null || !STAGES.contains(stage) || payload == null || payload.length() > MAX_PAYLOAD_BYTES
                || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid checkpoint payload bounds or stage");
        }
        try {
            JsonNode node = JSON.readTree(payload);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Checkpoint must be a derived JSON object");
            JsonNode version = node.path("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt()
                    || !CHECKPOINT_PAYLOAD_VERSIONS.contains(version.intValue())) {
                throw new IllegalArgumentException("Unsupported checkpoint schema version");
            }
            validateFields(node, 0);
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid checkpoint JSON"); }
    }

    private static void validateFields(JsonNode node, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Checkpoint JSON is too deep");
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().length() > 128 || PRIVATE_FIELDS.contains(field.getKey().replace("_", "").toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("Checkpoint contains forbidden fields");
                }
                validateFields(field.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) validateFields(child, depth + 1);
        }
    }

    private static String keyHash(String key) {
        if (key == null || key.isBlank() || key.length() > 8192) throw new IllegalArgumentException("Invalid generation key");
        return hash(key);
    }

    private static void requireHash(String key) {
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid period key");
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }

    private <T> T transaction(SqlWork<T> work) {
        boolean begun = false;
        try {
            execute("BEGIN IMMEDIATE"); begun = true;
            T result = work.run();
            execute("COMMIT");
            return result;
        } catch (SQLException | RuntimeException error) {
            if (begun) {
                try { execute("ROLLBACK"); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            }
            if (error instanceof RuntimeException runtime) throw runtime;
            throw storageError(error);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private int update(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement.executeUpdate();
        }
    }

    private static IllegalStateException storageError(Exception error) {
        return new IllegalStateException("Wiki generation storage operation failed", error);
    }
}
