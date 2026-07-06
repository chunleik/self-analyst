package com.selfanalyst.audio;

public class FallbackAudioEngine implements AudioEngine {

    private final AudioEngine primary;
    private final AudioEngine fallback;

    public FallbackAudioEngine(AudioEngine primary, AudioEngine fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public boolean isAvailable() {
        return available(primary) || available(fallback);
    }

    @Override
    public String name() {
        if (available(primary) && available(fallback)) {
            return primary.name() + "+Fallback";
        }
        if (available(primary)) return primary.name();
        return fallback == null ? "UnavailableAudioEngine" : fallback.name();
    }

    @Override
    public String transcribe(byte[] wavData) {
        String text = available(primary) ? primary.transcribe(wavData) : "";
        if (text != null && !text.isBlank()) return text.trim();
        return available(fallback) ? fallback.transcribe(wavData) : "";
    }

    private static boolean available(AudioEngine engine) {
        return engine != null && engine.isAvailable();
    }
}
