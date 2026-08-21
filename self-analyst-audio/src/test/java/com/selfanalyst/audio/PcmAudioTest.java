package com.selfanalyst.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmAudioTest {

    @Test
    void convertsStereoFloat48kToMono16kPcm16() {
        ByteBuffer source = ByteBuffer.allocate(480 * 2 * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 480; i++) {
            source.putFloat(0.5f);
            source.putFloat(0.25f);
        }

        byte[] pcm = PcmAudio.toPcm16Mono(
                source.array(),
                new AudioSampleFormat(48000, 2, 32, 8, AudioSampleFormat.Encoding.IEEE_FLOAT));

        assertEquals(160 * 2, pcm.length);
        assertEquals((short) 12287, firstSample(pcm));
    }

    @Test
    void wrapsPcm16MonoAs16kWav() {
        byte[] wav = PcmAudio.toWav(new byte[] {1, 0, 2, 0});

        assertEquals("RIFF", ascii(wav, 0, 4));
        assertEquals("WAVE", ascii(wav, 8, 4));
        assertEquals("fmt ", ascii(wav, 12, 4));
        assertEquals(16000, littleEndianInt(wav, 24));
        assertEquals(1, littleEndianShort(wav, 22));
        assertEquals(16, littleEndianShort(wav, 34));
        assertEquals("data", ascii(wav, 36, 4));
        assertEquals(4, littleEndianInt(wav, 40));
    }

    @Test
    void computesNormalizedRmsForSilenceAndFullScalePcm() {
        assertEquals(0f, PcmAudio.rms(new byte[200]));
        byte[] fullScale = new byte[200];
        for (int i = 0; i < fullScale.length; i += 2) {
            fullScale[i] = (byte) 0xFF;
            fullScale[i + 1] = 0x7F;
        }
        assertTrue(PcmAudio.rms(fullScale) > 0.99f);
    }

    private static short firstSample(byte[] bytes) {
        return ByteBuffer.wrap(bytes, 0, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort());
    }
}
