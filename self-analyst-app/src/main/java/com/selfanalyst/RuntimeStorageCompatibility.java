package com.selfanalyst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.selfanalyst.config.Config;
import com.selfanalyst.config.ConfigResolver;
import com.selfanalyst.config.TomlSupport;
import com.selfanalyst.memory.GrowthProfile;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** 只检查既有结构，不调用任何具有迁移或恢复副作用的存储构造器。 */
final class RuntimeStorageCompatibility {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Path root;
    private final Map<Path, String> directories = new HashMap<>();
    private int embeddingDimensions;

    private RuntimeStorageCompatibility(Path root) { this.root = root; }

    static void verify(Path root) throws IOException {
        new RuntimeStorageCompatibility(root).verify();
    }

    private void verify() throws IOException {
        try {
            Properties user = new Properties();
            Path configFile = root.resolve("config/config.toml");
            if (present(configFile)) {
                regular(configFile);
                TomlSupport.parseAndFlatten(Files.readString(configFile)).forEach(user::setProperty);
            }
            Config config = ConfigResolver.resolve(user).config();
            embeddingDimensions = config.embeddingDimensions();
            directories.put(root, "root");
            directories.put(root.resolve("config"), "config");
            register(root.resolve("memory"), "memory");
            Path memory = resolve(config.memoryDir());
            register(memory, "memory");
            register(resolve(config.eventsDataDir()), "events");
            register(resolve(config.eventsRawDir()), "raw");
            register(resolve(config.wikiSemanticIndexDir()), "index");
            // 先登记全部有效路径，再遍历，支持配置将存储放在 data 内其他目录。
            for (Path path : Set.copyOf(directories.keySet())) {
                if (present(path)) inspect(path, directories.get(path));
            }
        } catch (RuntimeStorageGuard.StorageException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("现有数据无法只读确认兼容");
        }
    }

    private Path resolve(Path path) {
        return (path.isAbsolute() ? path : root.getParent().resolve(path)).toAbsolutePath().normalize();
    }

    private void register(Path path, String kind) throws IOException {
        path = path.toAbsolutePath().normalize();
        String previous = directories.putIfAbsent(path, kind);
        if (previous != null && !previous.equals(kind)) throw invalid("存储路径重叠");
    }

    private void inspect(Path directory, String kind) throws Exception {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw invalid("存储目录类型无法确认");
        if (kind.equals("index")) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) return;
            }
            try (var index = FSDirectory.open(directory); var reader = DirectoryReader.open(index)) {
                Set<String> knownFields = Set.of("doc_id", "entry_id", "doc_type", "level", "period_start",
                        "period_end", "period_start_ms", "period_end_ms", "text", "embedding", "summary", "primary_task", "matched_text");
                for (var leaf : reader.leaves()) {
                    leaf.reader().checkIntegrity();
                    Set<String> fields = new HashSet<>();
                    for (var field : leaf.reader().getFieldInfos()) {
                        fields.add(field.name);
                        if (!knownFields.contains(field.name)) throw unsupported("索引字段不兼容");
                        if (field.name.equals("embedding") && field.getVectorDimension() != embeddingDimensions)
                            throw unsupported("索引向量维度不兼容");
                    }
                    if (leaf.reader().maxDoc() > 0 && !fields.containsAll(Set.of("doc_id", "entry_id", "level")))
                        throw unsupported("索引结构不兼容");
                }
            }
            return;
        }
        try (var entries = Files.list(directory)) {
            for (Path file : entries.toList()) {
                String name = file.getFileName().toString();
                if (directories.containsKey(file) && !file.equals(directory)) continue;
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    String childKind = switch (kind + "/" + name) {
                        case "memory/chat-sessions" -> "chat";
                        case "memory/usage" -> "usage";
                        case "memory/events" -> "events";
                        case "events/raw" -> "raw";
                        case "memory/wiki-semantic-index" -> "index";
                        case "memory/agent-state" -> "agent-root";
                        case "memory/agent-workspace" -> "workspace-root";
                        case "memory/documents" -> "documents";
                        case "agent-root/self-analyst-chat" -> "agent-users";
                        case "agent-users/desktop" -> "agent-sessions";
                        case "workspace-root/self-analyst-chat" -> "empty-workspace";
                        default -> null;
                    };
                    if (childKind == null && kind.equals("raw") && name.matches("[0-9]{4}")) childKind = "partition";
                    if (childKind == null && name.matches("[a-f0-9]{32}")) {
                        childKind = switch (kind) {
                            case "agent-sessions" -> "agent-session";
                            case "documents" -> "document-session";
                            case "document-session" -> "document";
                            default -> null;
                        };
                    }
                    if (childKind == null) throw invalid("存在未识别的存储目录");
                    inspect(file, childKind);
                    continue;
                }
                regular(file);
                if (kind.equals("root") && Set.of("app.lock", "storage-format.json.tmp").contains(name)) continue;
                if (kind.equals("config") && name.equals("config.toml")) continue;
                if (kind.equals("chat") && name.equals(".writer.lock")) continue;
                if (name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal")) {
                    if (Files.size(file) != 0) throw invalid("数据库仍有日志状态，请先正常关闭原应用");
                    continue;
                }
                if (name.endsWith(".db")) { database(file, kind); continue; }
                if (kind.equals("agent-session") && name.equals("agent_state.json")) {
                    var value = json(file);
                    if (!value.path("session_id").asText().equals(directory.getFileName().toString())
                            || !value.path("context").isArray()) throw invalid("Agent 状态结构无法确认");
                    io.agentscope.core.util.JsonUtils.getJsonCodec().fromJson(
                            value.toString(), io.agentscope.core.state.AgentState.class);
                    continue;
                }
                if (kind.equals("document") && name.equals("source.json")) {
                    var value = json(file);
                    if (!value.isObject() || !value.path("schemaVersion").isIntegralNumber()
                            || value.path("schemaVersion").asInt() != 1) throw invalid("文档来源结构无法确认");
                    continue;
                }
                if (kind.equals("document") && name.equals("content")) {
                    validateDocument(file);
                    continue;
                }
                if (kind.equals("memory") && name.equals("memory.json")) {
                    JsonNode value = json(file);
                    if (!value.isObject() || !value.has("goals") || !value.has("memories")) throw invalid("记忆结构无法确认");
                    JSON.treeToValue(value, GrowthProfile.class);
                } else if (kind.equals("memory") && name.equals("tasks.json")) {
                    var tasks = json(file).path("tasks");
                    if (!tasks.isArray()) throw invalid("任务结构无法确认");
                    for (var task : tasks) {
                        if (!task.isObject()) throw invalid("任务结构无法确认");
                        JSON.treeToValue(task, com.selfanalyst.desktop.store.TaskStore.Task.class);
                    }
                } else if (kind.equals("memory") && name.equals("desktop-summary-snapshot.json")) {
                    var value = json(file);
                    if (!value.path("current").isObject() || !value.path("timeline").isArray()) throw invalid("摘要结构无法确认");
                } else if (kind.equals("usage") && name.matches("usage-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")) {
                    if (!json(file).path("categories").isObject()) throw invalid("用量结构无法确认");
                } else if (kind.equals("events") && name.equals("settings.json")) {
                    if (!json(file).isObject()) throw invalid("事件配置结构无法确认");
                } else if (kind.equals("partition") && name.matches("raw-events-[0-9]{4}-[0-9]{2}\\.manifest\\.json")) {
                    var value = json(file);
                    if (value.path("manifestVersion").asInt(-1) != 1 || value.path("schemaVersion").asInt(-1) != 1)
                        throw invalid("原始事件清单版本无法确认");
                } else throw invalid("存在未识别的存储文件");
            }
        }
    }

    private static void validateDocument(Path file) throws Exception {
        Path artifact = file.getParent();
        Path session = artifact.getParent();
        Path database = session.getParent().getParent().resolve("chat-sessions/chat.db");
        requireNoJournal(database);
        try (var connection = readOnly(database); var query = connection.prepareStatement(
                "SELECT j.format,a.size,a.sha256 FROM document_jobs j JOIN document_artifacts a ON a.id=j.id "
                        + "WHERE j.id=? AND j.session_id=? AND j.status='READY'")) {
            query.setString(1, artifact.getFileName().toString());
            query.setString(2, session.getFileName().toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw invalid("文档元数据缺失");
                com.selfanalyst.document.DocumentFormat.parse(rows.getString(1));
                if (Files.size(file) != rows.getLong(2)) throw invalid("文档大小不一致");
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
                }
                if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(rows.getString(3)))
                    throw invalid("文档完整性检查失败");
            }
        }
    }

    private static JsonNode json(Path file) throws IOException {
        JsonNode value = JSON.readTree(file.toFile());
        if (value == null) throw invalid("空数据文件");
        return value;
    }

    private static void regular(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid("数据文件类型无法确认");
    }

    private static boolean present(Path path) throws IOException {
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (java.nio.file.NoSuchFileException missing) {
            return false;
        }
    }

    private static void database(Path file, String kind) throws Exception {
        requireNoJournal(file);
        try (Connection connection = readOnly(file); var statement = connection.createStatement()) {
            verifyDatabase(connection, statement, file, kind);
        }
    }

    private static void requireNoJournal(Path file) throws IOException {
        regular(file);
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            Path side = file.resolveSibling(file.getFileName() + suffix);
            if (present(side) && Files.size(side) != 0)
                throw invalid("数据库仍有日志状态，请先正常关闭原应用");
        }
    }

    private static Connection readOnly(Path file) throws SQLException {
        // immutable 禁止 SQLite 创建共享内存或执行恢复；上面已拒绝非空日志。
        String url = "jdbc:sqlite:" + file.toUri().toASCIIString() + "?mode=ro&immutable=1";
        return DriverManager.getConnection(url);
    }

    private static void verifyDatabase(Connection connection, java.sql.Statement statement, Path file, String kind) throws Exception {
        try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) throw invalid("数据库完整性检查失败");
        }
        String name = file.getFileName().toString();
        if (kind.equals("chat") && name.equals("chat.db")) {
            columns(connection, "metadata", "key,value");
            scalar(connection, "SELECT value FROM metadata WHERE key='schema_version'", "2");
            columns(connection, "sessions", "id,title,created_at,updated_at,source,context_label,context_snapshot,memory_policy,summary,last_message_preview,message_count");
            columns(connection, "messages", "id,session_id,seq,role,content,created_at,status,error,context_snapshot,suggested_tasks");
            columns(connection, "pending_deletions", "session_id,requested_at");
        } else if (kind.equals("memory") && name.equals("llm-wiki.db")) {
            scalar(connection, "PRAGMA user_version", "3");
            columns(connection, "wiki_entries", "id,level,period_start,period_end,timezone,status,summary,primary_task,task_segments_json,metrics_json,source_entry_ids_json,model,prompt_version,retry_count,next_retry_at,last_error,created_at,updated_at,summarized_at,fact_builder_version,projector_version,source_coverage_json");
            columns(connection, "wiki_semantic_documents", "doc_id,entry_id,doc_type,level,period_start,period_end,text_hash,embedding_model,embedding_dimensions,status,retry_count,next_retry_at,last_error,created_at,updated_at,indexed_at");
        } else if (kind.equals("memory") && name.equals("file-watch.db")) {
            scalar(connection, "PRAGMA user_version", "2");
            columns(connection, "file_metadata", "id,absolute_path,relative_path,watch_root,extension,size_bytes,file_created_at,last_modified,first_seen_at,last_collected_at,status,retry_count,next_retry_at,last_error,created_at,updated_at");
        } else if (kind.equals("events") && name.equals("events.db")) {
            columns(connection, "buckets", "id,name,type,client,hostname,created,last_updated");
            columns(connection, "events", "id,bucket_id,timestamp,duration,datastr,app");
            columns(connection, "schema_migrations", "id,completed_at");
            columns(connection, "raw_projection_sources", "raw_event_id,bucket_id,projection_event_id,projector_version,projected_at");
            columns(connection, "projection_event_coverage", "projection_event_id,bucket_id,first_raw_event_id,last_raw_event_id,raw_event_count,projector_version");
            columns(connection, "raw_projection_checkpoints", "partition_month,received_at,event_id,projector_version,updated_at");
        } else if (kind.equals("raw") && name.equals("catalog.db")) {
            scalar(connection, "SELECT value FROM catalog_metadata WHERE key='schema_version'", "1");
            columns(connection, "raw_partitions", "partition_month,relative_path,received_start,received_end,status,event_count,first_event_id,last_event_id,size_bytes,file_sha256,verified_at,schema_version");
        } else if (kind.equals("partition") && name.matches("raw-events-[0-9]{4}-[0-9]{2}\\.db")) {
            columns(connection, "raw_events", "event_id,source_event_id,bucket_id,source,schema_version,ingest_kind,event_timestamp,received_at,duration,canonical_data_json,data_sha256,import_session_id,import_ordinal");
            scalar(connection, "SELECT count(*) FROM raw_events WHERE schema_version NOT IN (1,2)", "0");
        } else throw invalid("未知数据库布局");
    }

    private static void columns(Connection connection, String table, String expected) throws SQLException, IOException {
        Set<String> actual = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) actual.add(rows.getString("name"));
        }
        if (!actual.equals(Set.of(expected.split(",")))) throw unsupported("数据库表结构不兼容");
    }

    private static void scalar(Connection connection, String query, String expected) throws SQLException, IOException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            if (!rows.next() || !expected.equals(rows.getString(1)) || rows.next()) throw unsupported("数据库版本不兼容");
        }
    }

    private static RuntimeStorageGuard.StorageException invalid(String reason) {
        return new RuntimeStorageGuard.StorageException(RuntimeStorageGuard.Failure.DATA_FORMAT_INVALID, reason);
    }

    private static RuntimeStorageGuard.StorageException unsupported(String reason) {
        return new RuntimeStorageGuard.StorageException(RuntimeStorageGuard.Failure.DATA_FORMAT_UNSUPPORTED, reason);
    }
}
