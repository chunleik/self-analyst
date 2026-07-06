package com.selfanalyst.audio;

import java.nio.file.Path;

public record AudioCaptureOptions(
        Path whisperPath,
        double vadThreshold,
        int chunkSeconds,
        String source,
        String engine,
        String cloudBaseUrl,
        String cloudApiKey,
        String cloudModel) {

    public AudioCaptureOptions {
        whisperPath = whisperPath == null ? Path.of("tools/whisper") : whisperPath;
        vadThreshold = vadThreshold <= 0 || vadThreshold > 1 ? 0.0001 : vadThreshold;
        chunkSeconds = chunkSeconds < 1 || chunkSeconds > 60 ? 10 : chunkSeconds;
        source = normalize(source, "mic");
        if (!source.equals("mic") && !source.equals("system") && !source.equals("both")) {
            source = "mic";
        }
        engine = normalize(engine, "auto");
        if (!engine.equals("local-whisper") && !engine.equals("cloud-asr") && !engine.equals("auto")) {
            engine = "auto";
        }
        cloudBaseUrl = cloudBaseUrl == null ? "" : cloudBaseUrl.trim();
        cloudApiKey = cloudApiKey == null ? "" : cloudApiKey.trim();
        cloudModel = cloudModel == null || cloudModel.isBlank()
                ? "gpt-4o-transcribe"
                : cloudModel.trim();
    }

    public static AudioCaptureOptions localDefaults(Path whisperPath, double vadThreshold) {
        return new AudioCaptureOptions(
                whisperPath, vadThreshold, 5, "mic", "local-whisper",
                "", "", "gpt-4o-transcribe");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }
}
