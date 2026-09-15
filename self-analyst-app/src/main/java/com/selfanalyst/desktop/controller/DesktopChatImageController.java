package com.selfanalyst.desktop.controller;

import com.selfanalyst.desktop.store.ChatImageStore;
import io.javalin.http.Context;
import java.io.IOException;
import java.util.Map;

public final class DesktopChatImageController {
    private final ChatImageStore images;
    public DesktopChatImageController(ChatImageStore images) { this.images = images; }
    public void upload(Context ctx) {
        try {
            ctx.status(201).json(images.upload(ctx.pathParam("id"), ctx.req().getInputStream(), ctx.contentType()));
        } catch (IllegalArgumentException | IOException failure) { error(ctx, failure, 400); }
    }
    public void content(Context ctx) {
        try {
            var image = images.detail(ctx.pathParam("id"), ctx.pathParam("image"));
            if (image == null) { ctx.status(404); return; }
            ctx.contentType(image.mimeType()).header("X-Content-Type-Options", "nosniff")
                    .header("Cache-Control", "no-store")
                    .result(images.read(ctx.pathParam("id"), image.id()));
        } catch (IllegalArgumentException | IOException failure) { error(ctx, failure, 404); }
    }
    public void delete(Context ctx) {
        try {
            images.removeDraft(ctx.pathParam("id"), ctx.pathParam("image"));
            ctx.status(204);
        } catch (IllegalArgumentException failure) { error(ctx, failure, 400); }
    }
    private static void error(Context ctx, Exception failure, int status) {
        ctx.status(status).json(Map.of("error", "Image unavailable, invalid, or exceeds the PNG/JPEG limits",
                "errorCode", "error.chat.imageInvalid"));
    }
}
