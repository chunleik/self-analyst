package com.selfanalyst.audio;

interface AudioInput extends AutoCloseable {
    boolean isAvailable();

    AudioCapturer.CaptureResult captureResult();

    String source();

    String deviceName();

    @Override
    void close();

    static AudioInput unavailable(String source) {
        return new AudioInput() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public AudioCapturer.CaptureResult captureResult() {
                return null;
            }

            @Override
            public String source() {
                return source;
            }

            @Override
            public String deviceName() {
                return "";
            }

            @Override
            public void close() {
            }
        };
    }
}
