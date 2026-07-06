package com.selfanalyst.audio;

/**
 * Speech-to-text engine interface.
 * Same pattern as OcrEngine — one-shot recognition.
 */
public interface AudioEngine {
    /** Transcribe audio byte array (WAV format) to text. */
    String transcribe(byte[] wavData);
    default boolean isAvailable() { return true; }
    default String name() { return getClass().getSimpleName(); }
}
