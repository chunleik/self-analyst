package com.selfanalyst.aw.webui;

import io.javalin.http.Context;
import io.javalin.http.Header;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;

public class WebUiHandler {

    private static final String WEBUI_ROOT = "/webui/";

    public void handleStatic(Context ctx) {
        String path = ctx.pathParam("path");
        if (path == null || path.isEmpty()) {
            path = "index.html";
        }

        byte[] content = loadResource(path);
        if (content != null) {
            ctx.status(200).contentType(guessMimeType(path));
            ctx.header(Header.CACHE_CONTROL, cacheControl(path));
            ctx.result(content);
            return;
        }

        // SPA fallback: return index.html for non-file paths
        byte[] indexHtml = loadResource("index.html");
        if (indexHtml != null) {
            ctx.status(200).contentType("text/html");
            ctx.header(Header.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
            ctx.result(indexHtml);
        } else {
            ctx.status(404).result("Not Found");
        }
    }

    private byte[] loadResource(String fileName) {
        String resourcePath = WEBUI_ROOT + fileName;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int n;
            while ((n = in.read(data)) != -1) {
                buf.write(data, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private String cacheControl(String fileName) {
        if (fileName.equals("index.html")
                || fileName.equals("logo.png")
                || fileName.equals("manifest.json")
                || fileName.equals("service-worker.js")
                || fileName.startsWith("img/icons/")) {
            return "no-cache, no-store, must-revalidate";
        }
        return "public, max-age=3600";
    }

    private String guessMimeType(String fileName) {
        if (fileName.endsWith(".html")) return "text/html; charset=utf-8";
        if (fileName.endsWith(".css")) return "text/css; charset=utf-8";
        if (fileName.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (fileName.endsWith(".json")) return "application/json";
        if (fileName.endsWith(".svg")) return "image/svg+xml";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".ico")) return "image/x-icon";
        if (fileName.endsWith(".woff2")) return "font/woff2";
        if (fileName.endsWith(".woff")) return "font/woff";
        if (fileName.endsWith(".ttf")) return "font/ttf";
        if (fileName.endsWith(".map")) return "application/json";
        String mime = URLConnection.guessContentTypeFromName(fileName);
        return mime != null ? mime : "application/octet-stream";
    }
}
