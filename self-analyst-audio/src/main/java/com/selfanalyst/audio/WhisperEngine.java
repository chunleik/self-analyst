package com.selfanalyst.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * whisper.cpp transcription engine. Uses whisper-cli.exe via one-shot ProcessBuilder.
 * Model: ggml-small.bin (auto-detected in tools/whisper/).
 */
public class WhisperEngine implements AudioEngine {

    private static final Logger log = LoggerFactory.getLogger(WhisperEngine.class);

    private final Path exePath;
    private final Path modelPath;
    private final String language;
    private volatile boolean available;
    private static final Pattern READ_AUDIO_DIAGNOSTIC =
            Pattern.compile("read_audio_data:.*?(?:miniaudio|$)");

    public WhisperEngine(Path exePath, Path modelPath, String language) {
        this.exePath = exePath;
        this.modelPath = modelPath;
        this.language = language;
        this.available = Files.exists(exePath) && Files.exists(modelPath);
    }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public String transcribe(byte[] wavData) {
        if (!available || wavData == null || wavData.length < 44) return "";
        Path wavPath = null;
        try {
            wavPath = Files.createTempFile("whisper_", ".wav");
            Files.write(wavPath, wavData);

            Process proc = new ProcessBuilder(
                    exePath.toAbsolutePath().toString(),
                    "-m", modelPath.toAbsolutePath().toString(),
                    "-f", wavPath.toAbsolutePath().toString(),
                    "-l", language,
                    "-nt", // no timestamps
                    "--no-prints"
            ).directory(exePath.getParent().toFile())
             .redirectErrorStream(false)
             .start();

            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(proc.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(proc.getErrorStream()));
            int exitCode = proc.waitFor();

            String output = new String(stdout.join(), StandardCharsets.UTF_8);
            String errorOutput = new String(stderr.join(), StandardCharsets.UTF_8);
            if (exitCode != 0) {
                available = false;
                log.warn("Whisper command failed (exit={}): {}", exitCode, summarize(output + "\n" + errorOutput));
                return "";
            }

            return extractTranscript(output);
        } catch (Exception e) {
            available = false;
            log.warn("Whisper command failed: {}", e.getMessage());
            return "";
        } finally {
            if (wavPath != null) {
                try { Files.deleteIfExists(wavPath); } catch (IOException ignored) {}
            }
        }
    }

    public static String extractTranscript(String output) {
        if (output == null || output.isBlank()) return "";
        String[] lines = output.split("\\r?\\n");
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            line = READ_AUDIO_DIAGNOSTIC.matcher(line.trim()).replaceAll("").trim();
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("whisper_")
                    || line.contains("processing") || line.contains("system_info")) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(line);
        }
        return text.toString().trim();
    }

    private static byte[] readAll(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String summarize(String output) {
        if (output == null || output.isBlank()) return "(no output)";
        String compact = output.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }
}
