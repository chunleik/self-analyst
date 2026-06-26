package com.selfanalyst.content.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SPEC-TST-101: HybridMerger test cases.
 *
 * @see HybridMerger
 */
class HybridMergerTest {

    private final HybridMerger merger = new HybridMerger();

    /**
     * SPEC-TST-101: UIA and OCR both have content → merged with separator.
     */
    @Test
    void shouldMergeBoth() {
        String result = merger.merge("hello", "world");
        assertEquals("hello\n--- OCR ---\nworld", result);
    }

    /**
     * SPEC-TST-101: Only UIA has content → UIA text returned.
     */
    @Test
    void shouldUseUiaOnly() {
        String result = merger.merge("hello", "");
        assertEquals("hello", result);
    }

    /**
     * SPEC-TST-101: Only OCR has content → OCR text returned.
     */
    @Test
    void shouldUseOcrOnly() {
        String result = merger.merge("", "world");
        assertEquals("world", result);
    }

    /**
     * SPEC-TST-101: Both empty → empty string.
     */
    @Test
    void shouldReturnEmpty() {
        String result = merger.merge("", "");
        assertEquals("", result);
    }
}
