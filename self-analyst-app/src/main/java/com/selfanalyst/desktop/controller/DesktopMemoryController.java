package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.memory.LongTermMemoryService;
import io.javalin.http.Context;

import java.util.Map;

public class DesktopMemoryController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LongTermMemoryService memoryService;

    public DesktopMemoryController(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void list(Context ctx) {
        ctx.json(Map.of(
                "memories", memoryService.list(
                        ctx.queryParam("status"), ctx.queryParam("type"),
                        ctx.queryParam("sourceSessionId"), ctx.queryParam("q")),
                "legacy", Map.of(
                        "goals", memoryService.profile().getGoals(),
                        "patterns", memoryService.profile().getPatterns(),
                        "logs", memoryService.profile().getLogs())));
    }

    public void create(Context ctx) {
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            ctx.status(201).json(memoryService.createManual(
                    text(body, "type"), text(body, "content"), text(body, "evidence"),
                    text(body, "sourceSessionId"), "ui_manual", textOr(body, "status", "active")));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to create memory: " + e.getMessage()));
        }
    }

    public void update(Context ctx) {
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            var updated = memoryService.update(ctx.pathParam("id"), text(body, "type"),
                    text(body, "content"), text(body, "evidence"),
                    body.has("confidence") ? body.get("confidence").asInt() : null,
                    text(body, "status"),
                    body.has("sensitive") ? body.get("sensitive").asBoolean() : null);
            if (updated == null) {
                ctx.status(404).json(Map.of("error", "Memory not found: " + ctx.pathParam("id")));
                return;
            }
            ctx.json(updated);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to update memory: " + e.getMessage()));
        }
    }

    public void delete(Context ctx) {
        try {
            String id = ctx.pathParam("id");
            if (!memoryService.delete(id)) {
                ctx.status(404).json(Map.of("error", "Memory not found: " + id));
                return;
            }
            ctx.json(Map.of("deleted", true, "id", id));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to delete memory: " + e.getMessage()));
        }
    }

    public void createFromSession(Context ctx) {
        try {
            JsonNode body = MAPPER.readTree(ctx.body());
            ctx.status(201).json(memoryService.createManual(
                    text(body, "type"), text(body, "content"), text(body, "evidence"),
                    ctx.pathParam("id"), "chat_manual", textOr(body, "status", "active")));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to create session memory: " + e.getMessage()));
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body != null ? body.get(field) : null;
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String textOr(JsonNode body, String field, String fallback) {
        String value = text(body, field);
        return value == null || value.isBlank() ? fallback : value;
    }
}
