package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.ontology.OntologyService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.*;
import java.util.function.Supplier;

public final class DesktopOntologyController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OntologyService service;
    public DesktopOntologyController(OntologyService service) { this.service = service; }

    public void register(Javalin app) {
        String base = "/desktop/ontology";
        app.get(base + "/status", ctx -> respond(ctx, () -> service.status()));
        app.get(base + "/entities", ctx -> respond(ctx, () -> service.search(ctx.queryParam("q"), ctx.queryParam("type"),
                ctx.queryParam("relatedTo"), bool(ctx, "unclassified"), ctx.queryParam("start"), ctx.queryParam("end"),
                number(ctx, "offset", 0), number(ctx, "limit", 30))));
        app.get(base + "/entities/{id}", ctx -> respond(ctx, () -> service.detail(ctx.pathParam("id"),
                ctx.queryParam("start"), ctx.queryParam("end"), number(ctx, "offset", 0), number(ctx, "limit", 30))));
        app.post(base + "/entities", ctx -> respond(ctx, () -> save(ctx, null)));
        app.put(base + "/entities/{id}", ctx -> respond(ctx, () -> save(ctx, ctx.pathParam("id"))));
        app.delete(base + "/entities/{id}", ctx -> respond(ctx, () -> { service.deleteEntity(ctx.pathParam("id")); return Map.of("ok", true); }));
        app.post(base + "/merge", ctx -> respond(ctx, () -> {
            JsonNode body = body(ctx); return service.merge(text(body, "from"), text(body, "to"));
        }));
        app.post(base + "/relations", ctx -> respond(ctx, () -> {
            JsonNode body = body(ctx);
            service.decide(text(body, "subject"), text(body, "predicate"), text(body, "object"), text(body, "action"));
            return Map.of("ok", true);
        }));
        app.post(base + "/rebuild", ctx -> respond(ctx, () -> service.rebuild()));
    }
    private Object save(Context ctx, String id) {
        JsonNode body = body(ctx);
        List<String> aliases = new ArrayList<>();
        JsonNode values = body.get("aliases");
        if (values != null) {
            if (!values.isArray() || values.size() > 30) throw new IllegalArgumentException("Invalid aliases");
            for (JsonNode value : values) {
                if (!value.isTextual()) throw new IllegalArgumentException("Invalid alias");
                aliases.add(value.asText());
            }
        }
        return service.saveEntity(id, text(body, "type"), text(body, "name"), text(body, "description"), aliases);
    }
    private void respond(Context ctx, Supplier<Object> action) {
        ctx.header("Cache-Control", "no-store");
        if (service == null) { ctx.status(503).json(Map.of("errorCode", "ontology.error.unavailable", "error", "Ontology unavailable")); return; }
        try { ctx.json(action.get()); }
        catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("errorCode", "ontology.error.invalid", "error", "Invalid ontology request"));
        } catch (RuntimeException e) {
            ctx.status(503).json(Map.of("errorCode", "ontology.error.unavailable", "error", "Ontology source or storage unavailable"));
        }
    }
    private static JsonNode body(Context ctx) {
        if (ctx.body().length() > 16000) throw new IllegalArgumentException("Request too large");
        try {
            JsonNode node = JSON.readTree(ctx.body());
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected object");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid JSON"); }
    }
    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new IllegalArgumentException("Expected text");
        return value.asText();
    }
    private static int number(Context ctx, String name, int fallback) {
        String value = ctx.queryParam(name); return value == null ? fallback : Integer.parseInt(value);
    }
    private static boolean bool(Context ctx, String name) {
        String value = ctx.queryParam(name);
        if (value != null && !value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Expected boolean");
        return "true".equals(value);
    }
}
