package com.selfanalyst.desktop.controller;

import com.selfanalyst.desktop.store.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.http.Context;

import java.util.*;

/**
 * Full CRUD for desktop tasks.
 *
 * <pre>
 *   GET    /desktop/tasks             → list tasks (sorted)
 *   POST   /desktop/tasks             → create task
 *   PUT    /desktop/tasks/{id}        → update task
 *   POST   /desktop/tasks/{id}/complete → mark complete
 *   POST   /desktop/tasks/{id}/archive  → archive
 *   DELETE /desktop/tasks/{id}        → hard delete
 * </pre>
 */
public class DesktopTaskController {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final TaskStore taskStore;

    public DesktopTaskController(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    /** GET /desktop/tasks */
    public void listTasks(Context ctx) {
        try {
            List<TaskStore.Task> tasks = taskStore.list();
            ctx.json(tasks);
        } catch (Exception e) {
            ctx.status(500).json(DesktopErrors.payload(ctx, "error.tasks.list", Map.of("detail", Objects.toString(e.getMessage(), ""))));
        }
    }

    /** POST /desktop/tasks */
    public void createTask(Context ctx) {
        try {
            TaskStore.Task task = MAPPER.readValue(ctx.body(), TaskStore.Task.class);
            if (task.title == null || task.title.isBlank()) {
                ctx.status(400).json(DesktopErrors.payload(ctx, "error.task.titleRequired", Map.of()));
                return;
            }
            if (task.status == null) task.status = "open";
            if (task.priority == null) task.priority = "medium";
            if (task.source == null) task.source = "user";
            TaskStore.Task created = taskStore.create(task);
            ctx.status(201).json(created);
        } catch (Exception e) {
            ctx.status(500).json(DesktopErrors.payload(ctx, "error.task.create", Map.of("detail", Objects.toString(e.getMessage(), ""))));
        }
    }

    /** PUT /desktop/tasks/{id} */
    public void updateTask(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            TaskStore.Task updated = MAPPER.readValue(ctx.body(), TaskStore.Task.class);
            TaskStore.Task result = taskStore.update(id, updated);
            if (result == null) {
                ctx.status(404).json(DesktopErrors.payload(ctx, "error.task.notFound", Map.of("id", id)));
                return;
            }
            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).json(DesktopErrors.payload(ctx, "error.task.update", Map.of("detail", Objects.toString(e.getMessage(), ""))));
        }
    }

    /** POST /desktop/tasks/{id}/complete */
    public void completeTask(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            TaskStore.Task result = taskStore.complete(id);
            if (result == null) {
                ctx.status(404).json(DesktopErrors.payload(ctx, "error.task.notFound", Map.of("id", id)));
                return;
            }
            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).json(DesktopErrors.payload(ctx, "error.task.complete", Map.of("detail", Objects.toString(e.getMessage(), ""))));
        }
    }

    /** POST /desktop/tasks/{id}/archive */
    public void archiveTask(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            TaskStore.Task result = taskStore.archive(id);
            if (result == null) {
                ctx.status(404).json(DesktopErrors.payload(ctx, "error.task.notFound", Map.of("id", id)));
                return;
            }
            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).json(DesktopErrors.payload(ctx, "error.task.archive", Map.of("detail", Objects.toString(e.getMessage(), ""))));
        }
    }

    /** DELETE /desktop/tasks/{id} */
    public void deleteTask(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            boolean deleted = taskStore.delete(id);
            if (!deleted) {
                ctx.status(404).json(DesktopErrors.payload(ctx, "error.task.notFound", Map.of("id", id)));
                return;
            }
            ctx.json(Map.of("deleted", true, "id", id));
        } catch (Exception e) {
            ctx.status(500).json(DesktopErrors.payload(ctx, "error.task.delete", Map.of("detail", Objects.toString(e.getMessage(), ""))));
        }
    }
}
