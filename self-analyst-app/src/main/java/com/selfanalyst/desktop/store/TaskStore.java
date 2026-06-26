package com.selfanalyst.desktop.store;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent CRUD store for desktop tasks backed by {@code tasks.json}.
 */
public class TaskStore {

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path filePath;

    public TaskStore(Path memoryDir) {
        this.filePath = memoryDir.resolve("tasks.json");
    }

    // ── Core persistence ─────────────────────────────────────────

    public List<Task> load() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            TaskList wrapper = MAPPER.readValue(filePath.toFile(), TaskList.class);
            return wrapper != null && wrapper.tasks != null ? wrapper.tasks : new ArrayList<>();
        } catch (IOException e) {
            log.warn("Failed to load tasks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public void save(List<Task> tasks) {
        try {
            Files.createDirectories(filePath.getParent());
            Path tmp = filePath.getParent().resolve(filePath.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), new TaskList(tasks));
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save tasks", e);
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────

    /** Returns tasks sorted per spec: open first, then by dueDate asc,
     *  same dueDate higher priority first, same priority updatedAt desc. */
    public List<Task> list() {
        List<Task> tasks = load();
        tasks.sort(TASK_COMPARATOR);
        return tasks;
    }

    public Task create(Task task) {
        List<Task> tasks = load();
        task.id = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        task.createdAt = now;
        task.updatedAt = now;
        if (task.status == null) {
            task.status = "open";
        }
        if (task.priority == null) {
            task.priority = "medium";
        }
        tasks.add(task);
        save(tasks);
        return task;
    }

    public Task update(String id, Task updated) {
        List<Task> tasks = load();
        for (int i = 0; i < tasks.size(); i++) {
            if (id.equals(tasks.get(i).id)) {
                Task existing = tasks.get(i);
                if (updated.title != null) existing.title = updated.title;
                if (updated.notes != null) existing.notes = updated.notes;
                if (updated.status != null) existing.status = updated.status;
                if (updated.priority != null) existing.priority = updated.priority;
                if (updated.dueAt != null) existing.dueAt = updated.dueAt;
                if (updated.source != null) existing.source = updated.source;
                existing.updatedAt = Instant.now();
                save(tasks);
                return existing;
            }
        }
        return null;
    }

    public boolean delete(String id) {
        List<Task> tasks = load();
        boolean removed = tasks.removeIf(t -> id.equals(t.id));
        if (removed) {
            save(tasks);
        }
        return removed;
    }

    public Task complete(String id) {
        List<Task> tasks = load();
        for (Task t : tasks) {
            if (id.equals(t.id)) {
                t.status = "completed";
                t.completedAt = Instant.now();
                t.updatedAt = Instant.now();
                save(tasks);
                return t;
            }
        }
        return null;
    }

    public Task archive(String id) {
        List<Task> tasks = load();
        for (Task t : tasks) {
            if (id.equals(t.id)) {
                t.status = "archived";
                t.updatedAt = Instant.now();
                save(tasks);
                return t;
            }
        }
        return null;
    }

    // ── Sorting ──────────────────────────────────────────────────

    private static final Comparator<Task> TASK_COMPARATOR = Comparator
            .<Task, Boolean>comparing(t -> !"open".equals(t.status))  // open first
            .thenComparing(t -> t.dueAt != null ? t.dueAt : Instant.MAX,
                    Comparator.nullsLast(Comparator.naturalOrder()))  // earlier dueDate first
            .thenComparing(t -> priorityOrder(t.priority))            // higher priority first
            .thenComparing(t -> t.updatedAt != null ? t.updatedAt : Instant.EPOCH,
                    Comparator.reverseOrder());                       // updatedAt desc

    private static int priorityOrder(String p) {
        return switch (p != null ? p.toLowerCase() : "medium") {
            case "high" -> 0;
            case "medium" -> 1;
            case "low" -> 2;
            default -> 1;
        };
    }

    // ── Model ────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Task {
        public String id;
        public String title;
        public String notes;
        public String status;     // open | completed | archived
        public String priority;   // low | medium | high
        public String source;     // user | agent_suggestion

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant dueAt;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant createdAt;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant updatedAt;

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        public Instant completedAt;

        public Task() {}

        public Task(String title) {
            this.title = title;
            this.status = "open";
            this.priority = "medium";
            this.source = "user";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskList(List<Task> tasks) {
    }
}
