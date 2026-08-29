package com.selfanalyst.content.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PaddleOCR-json powered OCR engine — persistent process mode.
 *
 * Keeps PaddleOCR-json.exe alive as a long-lived background process and
 * communicates via stdin/stdout pipe, eliminating the 1–3 s model-loading
 * overhead that one-shot mode paid on every capture cycle.
 *
 * Protocol (PaddleOCR-json v1.4.1 pipe mode — start without -image_path):
 *   stdin  ← {"image_path":"<absolute-path>"}\n
 *   stdout → model-loading diagnostics (first call only), then:
 *           {"code":100,"data":[{"text":"...","score":0.9,"box":[...]}]}\n
 *
 * Results with score < MIN_SCORE are dropped to suppress UI-chrome noise.
 */
public class PaddleOcrEngine implements OcrEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double MIN_SCORE = 0.60;
    private static final int OCR_TIMEOUT_MS = 30_000;

    private final Path exePath;
    private volatile boolean available;

    // Guarded by synchronized(this)
    private Process process;
    private BufferedWriter procStdin;
    private BufferedReader procStdout;

    public PaddleOcrEngine(Path exePath) {
        this.exePath = exePath;
        available = Files.isExecutable(exePath) || exePath.toString().endsWith(".exe");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopProcess, "paddle-ocr-shutdown"));
        if (available) {
            try {
                startProcess();
            } catch (Exception e) {
                available = false;
            }
        }
    }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public synchronized String recognize(BufferedImage image) {
        if (!available || image == null || image.getWidth() <= 0) return "";
        Path pngPath = null;
        try {
            pngPath = Files.createTempFile("paddle_", ".png");
            ImageIO.write(image, "png", pngPath.toFile());
            return sendRequest(pngPath);
        } catch (Exception e) {
            // Process may have crashed — attempt one restart
            try {
                startProcess();
                if (pngPath != null) return sendRequest(pngPath);
            } catch (Exception restart) {
                available = false;
            }
            return "";
        } finally {
            if (pngPath != null) {
                try { Files.deleteIfExists(pngPath); } catch (IOException ignored) {}
            }
        }
    }

    // ── Process lifecycle ───────────────────────────────────────────

    /** Kill SelfAnalyst-owned instances left over from a previous force-killed JVM. */
    private void killOrphanedInstances() {
        ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(candidate -> isOrphanedSelfAnalystOcrProcess(
                        exePath,
                        candidate.info().command(),
                        candidate.parent().filter(ProcessHandle::isAlive).isPresent()))
                .forEach(candidate -> {
                    try { candidate.destroyForcibly(); } catch (Exception ignored) {}
                });
    }

    /**
     * The bundled executable path establishes ownership, while the absence of a live parent
     * distinguishes an orphan from an OCR process used by another active SelfAnalyst instance.
     */
    static boolean isOrphanedSelfAnalystOcrProcess(Path selfAnalystExecutable,
                                                   Optional<String> processCommand,
                                                   boolean hasLiveParent) {
        if (processCommand.isEmpty() || hasLiveParent) return false;

        Path expected = selfAnalystExecutable.toAbsolutePath().normalize();
        Path actual;
        try {
            actual = Path.of(processCommand.get()).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            return false;
        }

        String expectedPath = expected.toString();
        String actualPath = actual.toString();
        return isWindows()
                ? expectedPath.equalsIgnoreCase(actualPath)
                : expectedPath.equals(actualPath);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private void startProcess() throws IOException {
        stopProcess();
        killOrphanedInstances();
        process = new ProcessBuilder(exePath.toAbsolutePath().toString())
                .directory(exePath.getParent().toFile())
                .redirectErrorStream(true)  // merge stderr → stdout
                .start();
        procStdin  = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        procStdout = new BufferedReader(new InputStreamReader(process.getInputStream(),  StandardCharsets.UTF_8));
    }

    private void stopProcess() {
        try { if (procStdin != null) procStdin.close(); } catch (IOException ignored) {}
        if (process != null) process.destroyForcibly();
        process    = null;
        procStdin  = null;
        procStdout = null;
    }

    // ── Request / response ──────────────────────────────────────────

    /**
     * Write one image path to stdin and read lines from stdout until we
     * find the JSON response line (contains {@code "code":}).
     *
     * On the first call the model-loading diagnostics appear before the
     * response; the loop simply skips those lines.  Subsequent calls get
     * the response immediately.
     */
    private String sendRequest(Path pngPath) throws IOException {
        // Escape Windows backslashes for JSON
        String absPath = pngPath.toAbsolutePath().toString().replace("\\", "\\\\");
        procStdin.write("{\"image_path\":\"" + absPath + "\"}\n");
        procStdin.flush();

        long deadline = System.currentTimeMillis() + OCR_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String line = procStdout.readLine();
            if (line == null) throw new IOException("PaddleOCR-json process exited unexpectedly");
            if (line.contains("\"code\"")) return parseResponse(line);
            // other lines are model-loading diagnostics — skip
        }
        throw new IOException("PaddleOCR-json timed out after " + OCR_TIMEOUT_MS + " ms");
    }

    @SuppressWarnings("unchecked")
    private String parseResponse(String json) {
        try {
            Map<String, Object> result = MAPPER.readValue(json, Map.class);
            int code = result.get("code") instanceof Number n ? n.intValue() : -1;
            if (code != 100) return "";

            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null) return "";

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> item : data) {
                Object scoreObj = item.get("score");
                if (scoreObj instanceof Number s && s.doubleValue() < MIN_SCORE) continue;
                Object text = item.get("text");
                if (text != null) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(text);
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
