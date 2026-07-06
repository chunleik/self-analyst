package com.selfanalyst.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class PcmAudio {
    static final int TARGET_SAMPLE_RATE = 16000;
    static final int TARGET_CHANNELS = 1;
    static final int TARGET_BITS_PER_SAMPLE = 16;

    private PcmAudio() {}

    static byte[] toPcm16Mono(byte[] source, AudioSampleFormat format) {
        if (source == null || source.length == 0 || format.sampleRate() <= 0
                || format.channels() <= 0 || format.frameSize() <= 0) {
            return new byte[0];
        }
        int sourceFrames = source.length / format.frameSize();
        int targetFrames = (int) Math.floor(sourceFrames * (TARGET_SAMPLE_RATE / (double) format.sampleRate()));
        ByteBuffer out = ByteBuffer.allocate(Math.max(0, targetFrames) * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < targetFrames; i++) {
            int sourceFrame = Math.min(sourceFrames - 1,
                    (int) Math.floor(i * (format.sampleRate() / (double) TARGET_SAMPLE_RATE)));
            double mono = 0;
            for (int ch = 0; ch < format.channels(); ch++) {
                mono += sample(source, format, sourceFrame, ch);
            }
            mono /= format.channels();
            out.putShort(toPcm16(mono));
        }
        return out.array();
    }

    static float rms(byte[] pcm16Mono) {
        if (pcm16Mono == null || pcm16Mono.length < 2) {
            return 0f;
        }
        long sum = 0;
        int samples = 0;
        for (int i = 0; i < pcm16Mono.length - 1; i += 2) {
            int sample = (pcm16Mono[i + 1] << 8) | (pcm16Mono[i] & 0xFF);
            sum += (long) sample * sample;
            samples++;
        }
        if (samples == 0) {
            return 0f;
        }
        return (float) (Math.sqrt((double) sum / samples) / 32768.0);
    }

    static byte[] toWav(byte[] pcm16Mono) {
        byte[] pcm = pcm16Mono == null ? new byte[0] : pcm16Mono;
        int dataLen = pcm.length;
        int totalLen = 44 + dataLen;
        ByteArrayOutputStream out = new ByteArrayOutputStream(totalLen);
        try {
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            out.write(intToBytes(totalLen - 8));
            out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
            out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            out.write(intToBytes(16));
            out.write(shortToBytes((short) 1));
            out.write(shortToBytes((short) TARGET_CHANNELS));
            out.write(intToBytes(TARGET_SAMPLE_RATE));
            out.write(intToBytes(TARGET_SAMPLE_RATE * TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8));
            out.write(shortToBytes((short) (TARGET_CHANNELS * TARGET_BITS_PER_SAMPLE / 8)));
            out.write(shortToBytes((short) TARGET_BITS_PER_SAMPLE));
            out.write("data".getBytes(StandardCharsets.US_ASCII));
            out.write(intToBytes(dataLen));
            out.write(pcm);
        } catch (IOException ignored) {
        }
        return out.toByteArray();
    }

    private static double sample(byte[] source, AudioSampleFormat format, int frame, int channel) {
        int offset = frame * format.frameSize() + channel * bytesPerSample(format);
        if (offset < 0 || offset >= source.length) {
            return 0;
        }
        return switch (format.encoding()) {
            case IEEE_FLOAT -> floatSample(source, format, offset);
            case PCM_SIGNED -> signedPcmSample(source, format, offset);
        };
    }

    private static int bytesPerSample(AudioSampleFormat format) {
        return Math.max(1, format.bitsPerSample() / 8);
    }

    private static double floatSample(byte[] source, AudioSampleFormat format, int offset) {
        if (format.bitsPerSample() == 64 && offset + 8 <= source.length) {
            return ByteBuffer.wrap(source, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
        }
        if (offset + 4 <= source.length) {
            return ByteBuffer.wrap(source, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        }
        return 0;
    }

    private static double signedPcmSample(byte[] source, AudioSampleFormat format, int offset) {
        return switch (format.bitsPerSample()) {
            case 8 -> source[offset] / 128.0;
            case 24 -> signed24(source, offset) / 8388608.0;
            case 32 -> int32(source, offset) / 2147483648.0;
            default -> int16(source, offset) / 32768.0;
        };
    }

    private static int int16(byte[] source, int offset) {
        if (offset + 2 > source.length) {
            return 0;
        }
        return (short) ((source[offset + 1] << 8) | (source[offset] & 0xFF));
    }

    private static int signed24(byte[] source, int offset) {
        if (offset + 3 > source.length) {
            return 0;
        }
        int value = (source[offset] & 0xFF)
                | ((source[offset + 1] & 0xFF) << 8)
                | (source[offset + 2] << 16);
        return (value << 8) >> 8;
    }

    private static int int32(byte[] source, int offset) {
        if (offset + 4 > source.length) {
            return 0;
        }
        return ByteBuffer.wrap(source, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static short toPcm16(double sample) {
        if (sample <= -1) {
            return Short.MIN_VALUE;
        }
        if (sample >= 1) {
            return Short.MAX_VALUE;
        }
        return (short) (sample * Short.MAX_VALUE);
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] shortToBytes(short v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }
}
