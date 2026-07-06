package com.selfanalyst.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Captures microphone or system-loopback audio in 16 kHz mono WAV chunks.
 */
public class AudioCapturer {
    private final AudioInput input;

    public record CaptureResult(
            byte[] wavData,
            boolean voiceDetected,
            float rms,
            String source,
            String deviceName) {}

    public AudioCapturer(int chunkSeconds, float vadThreshold) {
        this(chunkSeconds, vadThreshold, "mic");
    }

    public AudioCapturer(int chunkSeconds, float vadThreshold, String source) {
        this.input = openInput(source, chunkSeconds, vadThreshold, defaultProviders(source));
    }

    public boolean isAvailable() {
        return input.isAvailable();
    }

    public String source() {
        return input.source();
    }

    public String deviceName() {
        return input.deviceName();
    }

    /** Record one chunk. Returns WAV bytes, or null if no voice detected. */
    public byte[] capture() {
        CaptureResult result = captureResult();
        if (result == null) return null;
        return result.voiceDetected() ? result.wavData() : null;
    }

    /** Record one chunk with diagnostics for UI/status reporting. */
    public CaptureResult captureResult() {
        return input.captureResult();
    }

    public static List<String> availableInputDevices() {
        List<String> devices = new ArrayList<>();
        devices.addAll(WasapiLoopbackAudioInput.availableInputDevices());
        devices.addAll(JavaSoundAudioInput.availableInputDevices());
        return devices;
    }

    static boolean systemMixerLooksLikeLoopback(String name, String description) {
        return JavaSoundAudioInput.systemMixerLooksLikeLoopback(name, description);
    }

    static AudioInput openInput(String source, int chunkSeconds, float vadThreshold,
                                List<AudioInputProvider> providers) {
        String normalized = normalizeSource(source);
        for (AudioInputProvider provider : providers) {
            AudioInput input = provider.open(chunkSeconds, vadThreshold, normalized);
            if (input != null && input.isAvailable()) {
                return input;
            }
            if (input != null) {
                input.close();
            }
        }
        return AudioInput.unavailable(normalized);
    }

    private static List<AudioInputProvider> defaultProviders(String source) {
        String normalized = normalizeSource(source);
        if ("system".equals(normalized)) {
            return List.of(
                    WasapiLoopbackAudioInput::open,
                    JavaSoundAudioInput::open);
        }
        return List.of(JavaSoundAudioInput::open);
    }

    private static String normalizeSource(String source) {
        if (source == null) return "mic";
        String s = source.trim().toLowerCase(Locale.ROOT);
        return "system".equals(s) ? "system" : "mic";
    }

    public void close() {
        input.close();
    }
}
