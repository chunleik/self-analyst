package com.selfanalyst.audio;

import java.io.*;
import java.nio.file.*;

/**
 * whisper.cpp transcription engine. Uses whisper-cli.exe via one-shot ProcessBuilder.
 * Model: ggml-small.bin (auto-detected in tools/whisper/).
 */
public class WhisperEngine implements AudioEngine {

    private final Path exePath;
    private final Path modelPath;
    private final String language;
    private volatile boolean available;

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
             .redirectErrorStream(true)
             .start();

            byte[] outBytes = proc.getInputStream().readAllBytes();
            proc.waitFor();

            String output = new String(outBytes, java.nio.charset.StandardCharsets.UTF_8);
            // Strip the leading progress lines, keep actual transcription
            String[] lines = output.split("\\r?\\n");
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("[") || line.startsWith("whisper_")
                        || line.contains("processing") || line.contains("system_info")) continue;
                text.append(line);
            }
            return text.toString().trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (wavPath != null) {
                try { Files.deleteIfExists(wavPath); } catch (IOException ignored) {}
            }
        }
    }
}
