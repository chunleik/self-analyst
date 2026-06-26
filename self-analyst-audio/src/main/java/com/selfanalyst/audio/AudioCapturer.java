package com.selfanalyst.audio;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Captures microphone audio using Java Sound API.
 * Records chunks of N seconds, with Voice Activity Detection (VAD).
 */
public class AudioCapturer {

    private final AudioFormat format;
    private final int chunkSeconds;
    private final TargetDataLine line;
    private final float vadThreshold; // RMS threshold for voice detection

    public AudioCapturer(int chunkSeconds, float vadThreshold) {
        this.chunkSeconds = chunkSeconds;
        this.vadThreshold = vadThreshold;
        this.format = new AudioFormat(16000, 16, 1, true, false);

        TargetDataLine l;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            l = (TargetDataLine) AudioSystem.getLine(info);
            l.open(format);
            l.start();
        } catch (Exception e) {
            l = null;
        }
        this.line = l;
    }

    public boolean isAvailable() { return line != null && line.isOpen(); }

    /** Record one chunk. Returns WAV bytes, or null if no voice detected. */
    public byte[] capture() {
        if (!isAvailable()) return null;
        try {
            int bufferSize = (int) format.getFrameSize() * (int) format.getFrameRate() * chunkSeconds;
            byte[] buffer = new byte[bufferSize];
            int total = 0;
            while (total < bufferSize) {
                int n = line.read(buffer, total, bufferSize - total);
                if (n <= 0) break;
                total += n;
            }
            // Trim to actual bytes
            byte[] data = new byte[total];
            System.arraycopy(buffer, 0, data, 0, total);

            // VAD: skip silent chunks
            if (rms(data) < vadThreshold) return null;

            return toWav(data);
        } catch (Exception e) {
            return null;
        }
    }

    /** Compute RMS (root mean square) of audio samples for VAD. */
    private static float rms(byte[] data) {
        long sum = 0;
        for (int i = 0; i < data.length - 1; i += 2) {
            int sample = (data[i + 1] << 8) | (data[i] & 0xFF);
            sum += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sum / (data.length / 2));
        return (float) (rms / 32768.0);
    }

    /** Wrap raw PCM 16kHz 16bit mono into a minimal WAV container. */
    private static byte[] toWav(byte[] pcm) {
        int dataLen = pcm.length;
        int totalLen = 44 + dataLen;
        ByteArrayOutputStream out = new ByteArrayOutputStream(totalLen);
        try {
            // RIFF header
            out.write("RIFF".getBytes());
            out.write(intToBytes(totalLen - 8));
            out.write("WAVE".getBytes());
            // fmt chunk
            out.write("fmt ".getBytes());
            out.write(intToBytes(16));    // chunk size
            out.write(shortToBytes((short) 1));  // PCM
            out.write(shortToBytes((short) 1));  // mono
            out.write(intToBytes(16000));        // sample rate
            out.write(intToBytes(32000));        // byte rate
            out.write(shortToBytes((short) 2));  // block align
            out.write(shortToBytes((short) 16)); // bits per sample
            // data chunk
            out.write("data".getBytes());
            out.write(intToBytes(dataLen));
            out.write(pcm);
        } catch (IOException ignored) {}
        return out.toByteArray();
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] shortToBytes(short v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    public void close() {
        if (line != null) {
            line.stop();
            line.close();
        }
    }
}
