package com.selfanalyst.audio;

import java.util.List;

public record AudioCaptureDiagnostics(
        boolean microphoneAvailable,
        boolean engineAvailable,
        String source,
        String engine,
        String deviceName,
        List<String> availableInputDevices,
        double vadThreshold,
        long sampleCount,
        long silentCount,
        long voiceCount,
        long emptyTranscriptCount,
        long transcriptCount,
        String lastSampleAt,
        String lastSilentAt,
        String lastVoiceAt,
        String lastEmptyTranscriptAt,
        String lastTranscriptAt,
        String lastErrorAt,
        String lastError,
        double lastRms) {

    public AudioCaptureDiagnostics(
            boolean microphoneAvailable,
            boolean engineAvailable,
            double vadThreshold,
            long sampleCount,
            long silentCount,
            long voiceCount,
            long emptyTranscriptCount,
            long transcriptCount,
            String lastSampleAt,
            String lastSilentAt,
            String lastVoiceAt,
            String lastEmptyTranscriptAt,
            String lastTranscriptAt,
            String lastErrorAt,
            String lastError,
            double lastRms) {
        this(microphoneAvailable, engineAvailable, "mic", "", "", List.of(),
                vadThreshold, sampleCount, silentCount, voiceCount,
                emptyTranscriptCount, transcriptCount, lastSampleAt,
                lastSilentAt, lastVoiceAt, lastEmptyTranscriptAt,
                lastTranscriptAt, lastErrorAt, lastError, lastRms);
    }

    public static AudioCaptureDiagnostics empty() {
        return new AudioCaptureDiagnostics(
                false, false, "mic", "", "", List.of(), 0.0, 0, 0, 0, 0, 0,
                null, null, null, null, null, null, null, 0.0);
    }
}
