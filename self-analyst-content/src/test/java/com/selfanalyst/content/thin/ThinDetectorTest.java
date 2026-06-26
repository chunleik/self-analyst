package com.selfanalyst.content.thin;

import com.selfanalyst.content.uia.UiaNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-TST-100: ThinDetector test cases.
 *
 * @see ThinDetector
 */
class ThinDetectorTest {

    private final ThinDetector detector = new ThinDetector();

    /**
     * SPEC-TST-100: Fewer than 100 total chars → thin.
     */
    @Test
    void shouldDetectThinWhenFewChars() {
        UiaNode node1 = new UiaNode("Hello, ", "", 50020, "", null, false, null);
        UiaNode node2 = new UiaNode("world!!!world!!!world!", "", 50020, "", null, false, null);
        // 7 + 23 = 30 total chars < 100 → thin
        assertTrue(detector.isThin(List.of(node1, node2), "someapp", "Some Title"));
    }

    /**
     * SPEC-TST-100: Canvas app title match → thin regardless of char count.
     */
    @Test
    void shouldDetectCanvasApp() {
        // 500 chars — enough to pass the <100 check, but title contains "Google Docs"
        UiaNode node = new UiaNode("A".repeat(500), "", 50020, "", null, false, null);
        assertTrue(detector.isThin(List.of(node), "chrome.exe", "Google Docs - Project Plan"));
    }

    /**
     * SPEC-TST-100: Content density below 30% → thin.
     * <p>
     * 500 total chars, 100 content chars → ratio = 0.2 < 0.3
     */
    @Test
    void shouldDetectThinByDensity() {
        // Button (50000) is a chrome role → 400 chars, 0 content
        UiaNode chrome = new UiaNode("A".repeat(400), "", 50000, "", null, false, null);
        // Text (50020) is a content role → 100 chars, 100 content
        UiaNode content = new UiaNode("B".repeat(100), "", 50020, "", null, false, null);
        // Total = 500, Content = 100, Ratio = 0.2 < 0.3 → thin
        assertTrue(detector.isThin(List.of(chrome, content), "someapp", "Some Window"));
    }

    /**
     * SPEC-TST-100: Normal app with sufficient content density → not thin.
     * <p>
     * 500 total chars, 400 content chars → ratio = 0.8 >= 0.3
     */
    @Test
    void shouldDetectNormalApp() {
        // Text (50020) is a content role → 400 chars, 400 content
        UiaNode content = new UiaNode("A".repeat(400), "", 50020, "", null, false, null);
        // Button (50000) is a chrome role → 100 chars, 0 content
        UiaNode chrome = new UiaNode("B".repeat(100), "", 50000, "", null, false, null);
        // Total = 500, Content = 400, Ratio = 0.8 >= 0.3 → not thin
        assertFalse(detector.isThin(List.of(content, chrome), "idea64.exe", "IntelliJ IDEA"));
    }

    /**
     * SPEC-TST-100: Empty / null tree → thin (total chars = 0 < 100).
     */
    @Test
    void shouldDetectEmptyTree() {
        assertTrue(detector.isThin(List.of(), "someapp", "Some Title"));
    }
}
