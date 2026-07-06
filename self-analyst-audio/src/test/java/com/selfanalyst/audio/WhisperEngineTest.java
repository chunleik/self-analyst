package com.selfanalyst.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperEngineTest {

    @Test
    void nonZeroWhisperExitDoesNotBecomeTranscription(@TempDir Path tempDir) throws Exception {
        Path javaExe = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java");
        Path fakeModel = tempDir.resolve("ggml-small.bin");
        Files.writeString(fakeModel, "not a real model");

        WhisperEngine engine = new WhisperEngine(javaExe, fakeModel, "auto");

        assertTrue(engine.isAvailable());
        assertEquals("", engine.transcribe(minimalWav()));
        assertFalse(engine.isAvailable());
    }

    @Test
    void diagnosticOutputDoesNotBecomeTranscriptText() {
        String output = """
                read_audio_data: reading audio data from 'C:\\Users\\10478\\AppData\\Local\\Temp\\whisper_1.wav' ...read_audio_data: trying to decode with miniaudioAny.
                read_audio_data: reading audio data from 'C:\\Users\\10478\\AppData\\Local\\Temp\\whisper_2.wav' ...read_audio_data: trying to decode with miniaudio
                """;

        assertEquals("Any.", WhisperEngine.extractTranscript(output));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static byte[] minimalWav() {
        byte[] wav = new byte[46];
        byte[] riff = "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] wave = "WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] fmt = "fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] data = "data".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(riff, 0, wav, 0, riff.length);
        putInt(wav, 4, wav.length - 8);
        System.arraycopy(wave, 0, wav, 8, wave.length);
        System.arraycopy(fmt, 0, wav, 12, fmt.length);
        putInt(wav, 16, 16);
        putShort(wav, 20, 1);
        putShort(wav, 22, 1);
        putInt(wav, 24, 16000);
        putInt(wav, 28, 32000);
        putShort(wav, 32, 2);
        putShort(wav, 34, 16);
        System.arraycopy(data, 0, wav, 36, data.length);
        putInt(wav, 40, wav.length - 44);
        return wav;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
        bytes[offset + 2] = (byte) (value >> 16);
        bytes[offset + 3] = (byte) (value >> 24);
    }

    private static void putShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
    }
}
