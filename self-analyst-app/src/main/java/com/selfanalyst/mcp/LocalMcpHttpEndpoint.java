package com.selfanalyst.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.selfanalyst.mail.MailException;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Stateless, JSON-only Streamable HTTP transport for authorized native loopback clients. */
public final class LocalMcpHttpEndpoint {
    public static final String PATH = "/mcp/{serverId}";

    private static final String LATEST_VERSION = "2025-11-25";
    private static final Set<String> VERSIONS = Set.of(LATEST_VERSION, "2025-06-18", "2025-03-26");
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public interface Access {
        boolean authorize(String serverId, String bearerToken) throws Exception;

        boolean exposed(String serverId) throws Exception;

        List<JsonNode> listTools(String serverId, String bearerToken) throws Exception;

        /** Returns a complete MCP CallToolResult, including any sanitized tool-level errors. */
        JsonNode callTool(String serverId, String bearerToken, String toolName, ObjectNode arguments) throws Exception;
    }

    private final Access access;

    public LocalMcpHttpEndpoint(Access access) {
        this.access = Objects.requireNonNull(access);
    }

    public void handle(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        var headers = ctx.req();
        int port = headers.getLocalPort();
        if (!localAuthority(ctx.header("Host"), port) || ctx.header("Origin") != null) {
            ctx.status(403);
            return;
        }
        String serverId = ctx.pathParam("serverId");
        var credentials = headers.getHeaders("Authorization");
        String token = credentials.hasMoreElements() ? bearer(credentials.nextElement()) : null;
        try {
            if (credentials.hasMoreElements() || token == null || !access.authorize(serverId, token)) {
                ctx.header("WWW-Authenticate", "Bearer").status(401);
                return;
            }
            if (!access.exposed(serverId)) {
                ctx.status(404);
                return;
            }
        } catch (Exception ignored) {
            ctx.status(503);
            return;
        }
        if (ctx.method() != HandlerType.POST) {
            ctx.header("Allow", "POST").status(405);
            return;
        }
        String versionHeader = ctx.header("MCP-Protocol-Version");
        if (versionHeader != null && !VERSIONS.contains(versionHeader)) {
            ctx.status(400);
            return;
        }
        if (!jsonContentType(ctx.header("Content-Type"))) {
            ctx.status(415);
            return;
        }
        if (!acceptsJson(ctx.header("Accept"))) {
            ctx.status(406);
            return;
        }
        final JsonNode request;
        try {
            if (headers.getContentLengthLong() > MAX_REQUEST_BYTES) {
                ctx.status(413);
                return;
            }
            byte[] input = headers.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (input.length > MAX_REQUEST_BYTES) {
                ctx.status(413);
                return;
            }
            request = JSON.readTree(input);
        } catch (Exception ignored) {
            sendError(ctx, 400, NullNode.instance, -32700, "Parse error");
            return;
        }
        dispatch(ctx, request, serverId, token);
    }

    private void dispatch(Context ctx, JsonNode request, String serverId, String token) {
        if (request == null || !request.isObject() || !request.path("jsonrpc").isTextual()
                || !"2.0".equals(request.path("jsonrpc").asText())
                || !request.path("method").isTextual() || request.path("method").asText().isBlank()) {
            sendError(ctx, 400, NullNode.instance, -32600, "Invalid Request");
            return;
        }
        JsonNode id = request.get("id");
        if (id == null) {
            // Notifications are never allowed to invoke tools.
            ctx.status(202);
            return;
        }
        if (!id.isTextual() && !id.isIntegralNumber()) {
            sendError(ctx, 400, NullNode.instance, -32600, "Invalid Request");
            return;
        }
        JsonNode params = request.has("params") ? request.get("params") : JSON.createObjectNode();
        if (!params.isObject()) {
            sendError(ctx, 200, id, -32602, "Invalid params");
            return;
        }
        String method = request.path("method").asText();
        try {
            switch (method) {
                case "initialize" -> {
                    if (!params.path("protocolVersion").isTextual()) {
                        sendError(ctx, 200, id, -32602, "Invalid params");
                        return;
                    }
                    sendResult(ctx, id, initialize(params.path("protocolVersion").asText()));
                }
                case "ping" -> sendResult(ctx, id, JSON.createObjectNode());
                case "tools/list" -> {
                    ObjectNode result = JSON.createObjectNode();
                    result.putArray("tools").addAll(access.listTools(serverId, token));
                    sendResult(ctx, id, result);
                }
                case "tools/call" -> {
                    JsonNode name = params.path("name");
                    JsonNode args = params.has("arguments") ? params.get("arguments") : JSON.createObjectNode();
                    if (!name.isTextual() || name.asText().isBlank() || !(args instanceof ObjectNode arguments)) {
                        sendError(ctx, 200, id, -32602, "Invalid params");
                        return;
                    }
                    sendResult(ctx, id, access.callTool(serverId, token, name.asText(), arguments));
                }
                default -> sendError(ctx, 200, id, -32601, "Method not found");
            }
        } catch (MailException failure) {
            if ("tools/call".equals(method) && failure.code() == MailException.Code.OUTCOME_UNKNOWN) {
                var result = JSON.createObjectNode().put("isError", true);
                result.putArray("content").addObject().put("type", "text").put("text",
                        "OUTCOME_UNKNOWN: The mail operation may have completed. Verify it before retrying.");
                sendResult(ctx, id, result);
            } else {
                sendError(ctx, 200, id, -32603, "MCP operation unavailable");
            }
        } catch (Exception ignored) {
            sendError(ctx, 200, id, -32603, "MCP operation unavailable");
        }
    }

    private static ObjectNode initialize(String clientVersion) {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", VERSIONS.contains(clientVersion) ? clientVersion : LATEST_VERSION);
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "SelfAnalyst").put("version", "1");
        return result;
    }

    private static void sendResult(Context ctx, JsonNode id, JsonNode result) {
        ObjectNode response = envelope(id);
        response.set("result", Objects.requireNonNull(result));
        ctx.status(200).contentType("application/json").result(response.toString());
    }

    private static void sendError(Context ctx, int status, JsonNode id, int code, String message) {
        ObjectNode response = envelope(id);
        response.putObject("error").put("code", code).put("message", message);
        ctx.status(status).contentType("application/json").result(response.toString());
    }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        return response;
    }

    static boolean localAuthority(String authority, int port) {
        if (authority == null || port < 1) {
            return false;
        }
        String value = authority.toLowerCase(Locale.ROOT);
        return value.equals("localhost:" + port) || value.equals("127.0.0.1:" + port)
                || (port == 80 && ("localhost".equals(value) || "127.0.0.1".equals(value)));
    }

    private static String bearer(String value) {
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7);
        return token.matches("[A-Za-z0-9._~+/-]{1,512}={0,2}") ? token : null;
    }

    private static boolean jsonContentType(String value) {
        return value != null && "application/json".equalsIgnoreCase(value.split(";", 2)[0].trim());
    }

    private static boolean acceptsJson(String value) {
        if (value == null) {
            return true;
        }
        int specificity = -1;
        double bestQuality = 0;
        for (String range : value.split(",")) {
            String[] parts = range.split(";", -1);
            int match = switch (parts[0].trim().toLowerCase(Locale.ROOT)) {
                case "application/json" -> 2;
                case "application/*" -> 1;
                case "*/*" -> 0;
                default -> -1;
            };
            if (match < 0 || match < specificity) {
                continue;
            }
            double quality = 1;
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim().toLowerCase(Locale.ROOT);
                if (part.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(part.substring(2));
                    } catch (NumberFormatException ignored) {
                        quality = 0;
                    }
                }
            }
            if (!(quality >= 0 && quality <= 1)) {
                quality = 0;
            }
            bestQuality = match == specificity ? Math.max(bestQuality, quality) : quality;
            specificity = match;
        }
        return bestQuality > 0;
    }
}
