package com.selfanalyst.desktop.controller;

import com.selfanalyst.document.DocumentFormat;
import com.selfanalyst.document.DocumentService;
import io.javalin.http.Context;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class DesktopDocumentController {
    private final DocumentService service;
    private final java.util.List<java.nio.file.Path> protectedRoots;
    public DesktopDocumentController(DocumentService service) { this(service, null); }
    public DesktopDocumentController(DocumentService service, com.selfanalyst.config.Config config) {
        this.service = service;
        this.protectedRoots = config == null ? java.util.List.of() : java.util.List.of(
                config.memoryDir(), config.eventsDataDir(), config.eventsRawDir(),
                com.selfanalyst.config.Config.resolveConfigDir(), java.nio.file.Path.of("."));
    }
    private boolean authenticated(Context ctx) {
        if (!Boolean.TRUE.equals(ctx.attribute("selfanalyst.managedDesktop"))) {
            ctx.status(503).json(Map.of("error", "文档功能需要受管桌面认证")); return false;
        }
        return true;
    }
    public void list(Context ctx) {
        if (!authenticated(ctx)) return;
        try {
            int offset = ctx.queryParam("offset") == null ? 0 : Integer.parseInt(ctx.queryParam("offset"));
            var documents = service.store().list(ctx.pathParam("id"), offset, 50);
            ctx.json(Map.of("documents", documents, "hasMore", documents.size() == 50, "nextOffset", offset + documents.size()));
        } catch (IllegalArgumentException failure) { ctx.status(400).json(Map.of("error", "会话或分页无效")); }
    }
    public void detail(Context ctx) {
        if (!authenticated(ctx)) return;
        try {
            var artifact = service.store().get(ctx.pathParam("id"), ctx.pathParam("artifact"));
            if (artifact == null) { ctx.status(404); return; }
            ctx.json(artifact);
        } catch (IllegalArgumentException failure) { ctx.status(404); }
    }
    public void content(Context ctx) {
        if (!authenticated(ctx)) return;
        try {
            service.readContent(ctx.pathParam("id"), ctx.pathParam("artifact"), (artifact, input) -> {
                ctx.contentType(DocumentFormat.parse(artifact.format()).mime);
                ctx.header("Content-Disposition", "attachment; filename=\"document." + DocumentFormat.parse(artifact.format()).extension
                        + "\"; filename*=UTF-8''" + URLEncoder.encode(artifact.name(), StandardCharsets.UTF_8).replace("+", "%20"));
                ctx.header("Content-Length", Long.toString(artifact.size()));
                ctx.header("Cache-Control", "no-store"); ctx.header("X-Content-Type-Options", "nosniff");
                input.transferTo(ctx.res().getOutputStream());
            });
        } catch (IllegalArgumentException failure) { ctx.status(404); }
        catch (java.io.IOException failure) { if (!ctx.res().isCommitted()) ctx.status(500).json(Map.of("error", "文档读取失败，请重试或重新生成")); }
    }

    /** 原生另存为选择之后，只校验目标，不执行任何目标目录写入。 */
    public void validateSaveTarget(Context ctx) {
        if (!authenticated(ctx)) return;
        try {
            var artifact = service.store().get(ctx.pathParam("id"), ctx.pathParam("artifact"));
            if (artifact == null || !artifact.status().equals("READY")) { ctx.status(404); return; }
            var body = com.selfanalyst.document.DocumentRequest.JSON.readTree(DesktopChatJson.readBoundedBody(ctx));
            if (body == null || !body.path("path").isTextual() || body.path("path").textValue().length() > 32768)
                throw new IllegalArgumentException("无效保存路径");
            var target = java.nio.file.Path.of(body.path("path").textValue());
            if (!target.isAbsolute() || target.getParent() == null) throw new IllegalArgumentException("请选择绝对路径");
            var resolved = target.getParent().toRealPath().resolve(target.getFileName()).normalize();
            for (var root : protectedRoots) {
                var normalized = root.toAbsolutePath().normalize();
                if (java.nio.file.Files.exists(normalized)) normalized = normalized.toRealPath();
                if (resolved.startsWith(normalized)) { ctx.status(409).json(Map.of("error", "请选择应用数据目录之外的位置")); return; }
            }
            ctx.json(Map.of("allowed", true));
        } catch (IllegalArgumentException | java.io.IOException failure) {
            ctx.status(400).json(Map.of("error", "保存目标不可用"));
        }
    }
}
