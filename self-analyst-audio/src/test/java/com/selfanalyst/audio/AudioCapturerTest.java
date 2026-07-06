package com.selfanalyst.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AudioCapturerTest {

    @Test
    void systemSourceUsesFirstAvailableProvider() {
        FakeInput wasapi = new FakeInput(true, "WASAPI loopback");
        FakeInput stereoMix = new FakeInput(true, "Stereo Mix");
        AtomicInteger attempts = new AtomicInteger();

        AudioInput input = AudioCapturer.openInput("system", 10, 0.0001f, List.of(
                (chunkSeconds, vadThreshold, source) -> {
                    attempts.incrementAndGet();
                    return wasapi;
                },
                (chunkSeconds, vadThreshold, source) -> {
                    attempts.incrementAndGet();
                    return stereoMix;
                }));

        assertSame(wasapi, input);
        assertEquals(1, attempts.get());
    }

    @Test
    void systemSourceFallsBackWhenFirstProviderIsUnavailable() {
        FakeInput unavailableWasapi = new FakeInput(false, "WASAPI loopback");
        FakeInput stereoMix = new FakeInput(true, "Stereo Mix");

        AudioInput input = AudioCapturer.openInput("system", 10, 0.0001f, List.of(
                (chunkSeconds, vadThreshold, source) -> unavailableWasapi,
                (chunkSeconds, vadThreshold, source) -> stereoMix));

        assertSame(stereoMix, input);
    }

    private record FakeInput(boolean available, String deviceName) implements AudioInput {
        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public AudioCapturer.CaptureResult captureResult() {
            return null;
        }

        @Override
        public String source() {
            return "system";
        }

        @Override
        public void close() {
        }
    }
}
