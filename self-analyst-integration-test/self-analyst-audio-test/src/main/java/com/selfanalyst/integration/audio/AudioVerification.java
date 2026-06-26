package com.selfanalyst.integration.audio;

import com.selfanalyst.audio.AudioCapturer;

import java.lang.reflect.Method;

public class AudioVerification {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        step("AudioCapturer constructor + isAvailable + close", () -> {
            AudioCapturer capturer = new AudioCapturer(5, 0.01f);
            // isAvailable depends on mic hardware — just verify no exception
            boolean available = capturer.isAvailable();
            System.out.println("  mic available: " + available);
            capturer.close();
        });

        step("AudioCapturer capture() without throwing", () -> {
            AudioCapturer capturer = new AudioCapturer(5, 0.01f);
            byte[] result = capturer.capture();
            // May return null (no mic / VAD silence) or WAV data
            if (result != null) {
                check(result.length > 44, "WAV should have header + data");
                check(result[0] == 'R' && result[1] == 'I' && result[2] == 'F' && result[3] == 'F',
                        "should start with RIFF header");
            }
            capturer.close();
        });

        step("RMS computation: silence vs full-scale", () -> {
            Method rms = AudioCapturer.class.getDeclaredMethod("rms", byte[].class);
            rms.setAccessible(true);

            // Silent audio
            byte[] silent = new byte[200];
            float rmsSilent = (float) rms.invoke(null, (Object) silent);
            check(rmsSilent < 0.01f, "silent RMS should be < 0.01, got " + rmsSilent);

            // Full-scale audio (32767)
            byte[] full = new byte[200];
            for (int i = 0; i < 200; i += 2) {
                full[i] = (byte) 0xFF;
                full[i + 1] = 0x7F;
            }
            float rmsFull = (float) rms.invoke(null, (Object) full);
            check(rmsFull > 0.99f, "full-scale RMS should be ~1.0, got " + rmsFull);
        });

        step("WAV container header structure", () -> {
            Method toWav = AudioCapturer.class.getDeclaredMethod("toWav", byte[].class);
            toWav.setAccessible(true);

            byte[] pcm = new byte[100];
            byte[] wav = (byte[]) toWav.invoke(null, (Object) pcm);
            check(wav.length == 144, "WAV length = 44 header + 100 data = 144");

            // RIFF
            check(wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F', "RIFF magic");
            // WAVE
            check(wav[8] == 'W' && wav[9] == 'A' && wav[10] == 'V' && wav[11] == 'E', "WAVE magic");
            // fmt chunk
            check(wav[12] == 'f' && wav[13] == 'm' && wav[14] == 't' && wav[15] == ' ', "fmt chunk");
            // PCM format = 1
            check((wav[21] << 8 | wav[20] & 0xFF) == 1, "PCM format = 1");
            // mono
            check((wav[23] << 8 | wav[22] & 0xFF) == 1, "channels = 1");
            // sample rate = 16000
            int sr = (wav[27] & 0xFF) << 24 | (wav[26] & 0xFF) << 16 | (wav[25] & 0xFF) << 8 | (wav[24] & 0xFF);
            check(sr == 16000, "sample rate = 16000");
            // bits per sample = 16
            check((wav[35] << 8 | wav[34] & 0xFF) == 16, "bits per sample = 16");
            // data chunk
            check(wav[36] == 'd' && wav[37] == 'a' && wav[38] == 't' && wav[39] == 'a', "data chunk");
            // data size = 100
            int ds = (wav[43] & 0xFF) << 24 | (wav[42] & 0xFF) << 16 | (wav[41] & 0xFF) << 8 | (wav[40] & 0xFF);
            check(ds == 100, "data chunk size = 100, got " + ds);
        });

        step("little-endian byte encoding", () -> {
            Method intToBytes = AudioCapturer.class.getDeclaredMethod("intToBytes", int.class);
            intToBytes.setAccessible(true);
            Method shortToBytes = AudioCapturer.class.getDeclaredMethod("shortToBytes", short.class);
            shortToBytes.setAccessible(true);

            byte[] ib = (byte[]) intToBytes.invoke(null, 0x01020304);
            check((ib[0] & 0xFF) == 0x04 && (ib[1] & 0xFF) == 0x03 && (ib[2] & 0xFF) == 0x02 && (ib[3] & 0xFF) == 0x01,
                    "intToBytes LE encoding");

            byte[] zero = (byte[]) intToBytes.invoke(null, 0);
            check(zero[0] == 0 && zero[1] == 0 && zero[2] == 0 && zero[3] == 0, "intToBytes zero");

            byte[] sb = (byte[]) shortToBytes.invoke(null, (short) 0x0102);
            check((sb[0] & 0xFF) == 0x02 && (sb[1] & 0xFF) == 0x01, "shortToBytes LE encoding");

            byte[] neg = (byte[]) shortToBytes.invoke(null, (short) -1);
            check((neg[0] & 0xFF) == 0xFF && (neg[1] & 0xFF) == 0xFF, "shortToBytes -1");
        });

        System.out.println("\n=== Audio Verification: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void step(String desc, Step r) {
        try {
            r.run();
            System.out.println("[PASS] " + desc);
            passed++;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + desc + " — " + t.getMessage());
            failed++;
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    @FunctionalInterface
    interface Step { void run() throws Exception; }
}
