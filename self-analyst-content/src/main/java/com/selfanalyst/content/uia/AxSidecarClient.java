package com.selfanalyst.content.uia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client for the long-lived accessibility-tree sidecar (SPEC-AXS-*).
 *
 * <p>Replaces the one-shot PowerShell UIA walk (SPEC-UIA-001) with a persistent
 * Rust child process speaking an OS-neutral NDJSON protocol over stdio
 * (SPEC-AXS-010..016). Owns the process lifecycle: lazy start, single in-flight
 * request, per-query timeout with kill+restart, crash recovery, and a shutdown
 * hook (SPEC-AXS-020..023).
 *
 * <p>Any failure (missing binary, crash, timeout, parse error, non-ok status)
 * degrades to {@code null}, so callers fall back exactly as they do today when
 * UIA is unavailable (SPEC-AXS-023, preserving SPEC-WCH-003 / SPEC-NFR-102).
 *
 * <p>Phase 1: produces {@link UiaNode} (mapping the neutral {@code role} back to
 * the legacy control-type id) so the existing text-extraction path can consume
 * it unchanged for an apples-to-apples equivalence check. Phase 2 renames the
 * model to a neutral {@code AccessibilityNode} (SPEC-AXS-030).
 */
public final class AxSidecarClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 1500L;

    private final String binaryPath;
    private final long timeoutMs;
    private final Object lock = new Object();

    private Process proc;
    private BufferedWriter writer;
    private BlockingQueue<String> responses;
    private long reqId;

    /** Process-wide shared instance (SPEC-AXS-020: single sidecar for all callers). */
    private static volatile AxSidecarClient shared;

    /** Returns the process-wide shared client, creating it on first use. */
    public static AxSidecarClient shared() {
        AxSidecarClient s = shared;
        if (s == null) {
            synchronized (AxSidecarClient.class) {
                s = shared;
                if (s == null) {
                    s = new AxSidecarClient();
                    shared = s;
                }
            }
        }
        return s;
    }

    public AxSidecarClient() {
        this(resolveBinary(), longProp("content.axsidecar.timeout-ms", DEFAULT_TIMEOUT_MS));
    }

    public AxSidecarClient(String binaryPath, long timeoutMs) {
        this.binaryPath = binaryPath;
        this.timeoutMs = timeoutMs;
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "axsidecar-shutdown"));
    }

    /** Returns whether a sidecar binary is configured/extractable. */
    public boolean isAvailable() {
        return binaryPath != null;
    }

    /**
     * Query the accessibility tree for a native window handle.
     *
     * @param handle neutral window handle (Windows: HWND numeric value)
     * @return the root node, or {@code null} on any failure
     */
    public UiaNode query(long handle) {
        if (binaryPath == null) return null;
        synchronized (lock) {
            try {
                ensureStarted();
                long id = ++reqId;
                String req = "{\"id\":" + id + ",\"handle\":\"0x"
                        + Long.toHexString(handle) + "\"}\n";
                writer.write(req);
                writer.flush();

                String line = responses.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (line == null) {            // timeout: kill + restart, degrade
                    restart();
                    return null;
                }
                JsonNode resp = MAPPER.readTree(line);
                if (resp.path("id").asLong(-1) != id) return null;   // desync
                if (!"ok".equals(resp.path("status").asText())) return null;
                JsonNode root = resp.get("root");
                if (root == null || root.isNull()) return null;
                return toUiaNode(root);
            } catch (Exception e) {
                restart();
                return null;
            }
        }
    }

    // ── Process lifecycle ──────────────────────────────────────────

    private void ensureStarted() throws Exception {
        if (proc != null && proc.isAlive()) return;
        cleanup();

        ProcessBuilder pb = new ProcessBuilder(binaryPath);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);   // keep stdout protocol clean
        Process p = pb.start();

        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> pump(p.getInputStream(), q), "axsidecar-reader");
        reader.setDaemon(true);
        reader.start();

        this.proc = p;
        this.writer = new BufferedWriter(
                new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        this.responses = q;
    }

    private static void pump(InputStream in, BlockingQueue<String> q) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                q.offer(line);
            }
        } catch (Exception ignored) {
            // EOF / process exit ends the reader thread
        }
    }

    private void restart() {
        cleanup();
    }

    private void cleanup() {
        if (proc != null) {
            proc.destroyForcibly();
            proc = null;
        }
        writer = null;
        responses = null;
    }

    @Override
    public void close() {
        synchronized (lock) {
            cleanup();
        }
    }

    // ── JSON -> UiaNode (Phase 1 bridge) ───────────────────────────

    private UiaNode toUiaNode(JsonNode n) {
        String role = n.path("role").asText("Unknown");
        int controlType = roleToControlType(role);
        String name = n.path("name").asText("");
        String value = n.path("value").asText("");
        boolean secure = n.path("secure").asBoolean(false);

        double[] b = {0, 0, 0, 0};
        JsonNode bn = n.get("bounds");
        if (bn != null && bn.isArray() && bn.size() == 4) {
            for (int i = 0; i < 4; i++) b[i] = bn.get(i).asDouble();
        }

        UiaNode node = UiaNode.create(name, value, controlType, "", b, secure);
        JsonNode kids = n.get("children");
        if (kids != null && kids.isArray()) {
            for (JsonNode k : kids) node.addChild(toUiaNode(k));
        }
        return node;
    }

    // ── Binary resolution ──────────────────────────────────────────

    private static String resolveBinary() {
        // 1. explicit path (SPEC-AXS-061: content.axsidecar.path)
        String prop = System.getProperty("content.axsidecar.path",
                System.getenv("CONTENT_AXSIDECAR_PATH"));
        if (prop != null && !prop.isBlank() && Files.exists(Path.of(prop))) {
            return prop;
        }
        // 2. extract bundled binary from classpath (wired into the build in Phase 2)
        String res = "/axsidecar/" + binaryName();
        try (InputStream in = AxSidecarClient.class.getResourceAsStream(res)) {
            if (in != null) {
                Path tmp = Files.createTempFile("axsidecar_", isWindows() ? ".exe" : "");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().setExecutable(true);
                tmp.toFile().deleteOnExit();
                return tmp.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        // 3. dev fallback: built binary in the cargo target dir, relative to cwd
        Path dev = Path.of("self-analyst-axsidecar", "target", "release", binaryName());
        if (Files.exists(dev)) {
            return dev.toAbsolutePath().toString();
        }
        return null;
    }

    private static String binaryName() {
        return isWindows() ? "axsidecar.exe" : "axsidecar";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static long longProp(String key, long dflt) {
        try {
            String v = System.getProperty(key);
            return v != null ? Long.parseLong(v.trim()) : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ── Neutral role -> legacy control-type id (inverse of UIA mapping) ──

    private static final Map<String, Integer> ROLE_TO_CT = new HashMap<>();
    static {
        String[] roles = {
            "Button", "Calendar", "CheckBox", "ComboBox", "Edit", "Hyperlink",
            "Image", "ListItem", "List", "Menu", "MenuBar", "MenuItem",
            "ProgressBar", "RadioButton", "ScrollBar", "Slider", "Spinner",
            "StatusBar", "Tab", "TabItem", "Text", "ToolBar", "ToolTip", "Tree",
            "TreeItem", "Custom", "Group", "Thumb", "DataGrid", "DataItem",
            "Document", "SplitButton", "Window", "Pane", "Header", "HeaderItem",
            "Table", "TitleBar", "Separator", "SemanticZoom", "AppBar"
        };
        for (int i = 0; i < roles.length; i++) {
            ROLE_TO_CT.put(roles[i], 50000 + i);
        }
    }

    static int roleToControlType(String role) {
        return ROLE_TO_CT.getOrDefault(role, 0);
    }
}
