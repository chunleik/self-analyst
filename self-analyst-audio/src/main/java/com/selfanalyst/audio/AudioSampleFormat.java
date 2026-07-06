package com.selfanalyst.audio;

public record AudioSampleFormat(
        int sampleRate,
        int channels,
        int bitsPerSample,
        int frameSize,
        Encoding encoding) {

    public enum Encoding {
        PCM_SIGNED,
        IEEE_FLOAT
    }
}
