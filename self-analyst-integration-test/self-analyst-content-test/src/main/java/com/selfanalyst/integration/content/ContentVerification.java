package com.selfanalyst.integration.content;

import com.selfanalyst.content.ContentEvent;
import com.selfanalyst.content.capture.ContentCapture;
import com.selfanalyst.content.capture.ContentResult;
import com.selfanalyst.content.capture.HybridMerger;
import com.selfanalyst.content.platform.WindowsCapture;
import com.selfanalyst.content.thin.ThinDetector;
import com.selfanalyst.content.uia.UiaNode;
import com.selfanalyst.content.uia.UiaTreeWalker;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ContentVerification {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        step("ThinDetector canvas title pattern", () -> {
            ThinDetector d = new ThinDetector();
            check(d.isThin(List.of(), "figma.exe", "My Design - Figma"), "should detect Figma");
            // Google Docs in title → thin
            check(d.isThin(List.of(), "chrome.exe", "Report - Google Docs"), "should detect Google Docs");
        });

        step("ThinDetector low character count", () -> {
            ThinDetector d = new ThinDetector();
            UiaNode root = UiaNode.create("Win", null, 50032, "Window", null, false);
            root.addChild(UiaNode.create("OK", null, 50000, "Button", null, false));
            check(d.isThin(List.of(root), "app.exe", "Small"), "low char count should be thin");
        });

        step("ThinDetector content-rich window", () -> {
            ThinDetector d = new ThinDetector();
            UiaNode root = UiaNode.create("Editor", null, 50032, "Window", null, false);
            root.addChild(UiaNode.create(repeat("X", 150), null, 50020, "Text", null, false));
            check(!d.isThin(List.of(root), "editor.exe", "Rich Content"), "content-rich should not be thin");
        });

        step("ThinDetector chrome-heavy window with little extractable text", () -> {
            ThinDetector d = new ThinDetector();
            // Simulate WXWork: lots of UI chrome (buttons, tabs) but extractText yields only 4 chars
            UiaNode root = UiaNode.create("企业微信", null, 50032, "Window", null, false);
            root.addChild(UiaNode.create("发送", null, 50000, "Button", null, false));
            root.addChild(UiaNode.create("表情", null, 50000, "Button", null, false));
            UiaNode toolbar = UiaNode.create("消息|通讯录|工作台", null, 50021, "ToolBar", null, false);
            root.addChild(toolbar);
            // Total tree chars ≈ 4 + 2 + 2 + 8 = 16 (> 0 but extractText yields only "企业微信" = 4)
            // 4-param version: extractedChars=4 < 100 → thin
            check(d.isThin(List.of(root), "WXWork.exe", "企业微信", 4),
                    "chrome-heavy window with 4 extractable chars should be thin");
            // 3-param version: totalChars=16 < 100 → also thin (backward compat)
            check(d.isThin(List.of(root), "WXWork.exe", "企业微信"),
                    "3-param overload should delegate correctly");
        });

        step("ContentCapture UIA-only path", () -> {
            ContentCapture capture = new ContentCapture(null, null);
            UiaNode root = UiaNode.create("Editor", null, 50032, "Window", null, false);
            root.addChild(UiaNode.create(repeat("B", 150), null, 50020, "Text", null, false));
            ContentResult result = capture.capture(0L, "editor.exe", "Editor", root, "extracted text from editor");
            check("uia".equals(result.source()), "source should be uia");
            check(result.uiaChars() > 0, "uiaChars should be > 0");
            check(result.ocrChars() == 0, "ocrChars should be 0");
        });

        step("HybridMerger merge both present", () -> {
            HybridMerger m = new HybridMerger();
            String r = m.merge("UIA text", "OCR text");
            check(r.contains("UIA text"), "should contain UIA text");
            check(r.contains("OCR text"), "should contain OCR text");
            check(r.contains("--- OCR ---"), "should have OCR separator");
        });

        step("HybridMerger UIA-only when OCR empty", () -> {
            HybridMerger m = new HybridMerger();
            String r = m.merge("UIA text", "");
            check("UIA text".equals(r), "should return UIA text only");
        });

        step("UiaTreeWalker control type names", () -> {
            check("Button".equals(UiaTreeWalker.controlTypeName(50000)), "50000 should be Button");
            check("Edit".equals(UiaTreeWalker.controlTypeName(50004)), "50004 should be Edit");
            check("Text".equals(UiaTreeWalker.controlTypeName(50020)), "50020 should be Text");
            check("Window".equals(UiaTreeWalker.controlTypeName(50032)), "50032 should be Window");
            check("ScrollBar".equals(UiaTreeWalker.controlTypeName(50014)), "50014 should be ScrollBar");
            check("ComboBox".equals(UiaTreeWalker.controlTypeName(50003)), "50003 should be ComboBox");
            check(UiaTreeWalker.controlTypeName(99999).startsWith("Unknown"), "unknown should start with Unknown");
        });

        step("ContentEvent.toHeartbeatData null safety", () -> {
            ContentEvent event = new ContentEvent(
                    Instant.now(), 5.0, null, null, null, null,
                    null, null, 0, 0);
            Map<String, Object> data = event.toHeartbeatData();
            check("".equals(data.get("app")), "null app should default to empty string");
            check("".equals(data.get("title")), "null title should default to empty string");
            check(!data.containsKey("text_content"), "persisted event must not contain text_content");
            check("window".equals(data.get("title_source")), "null title source should default to window");
        });

        step("real foreground window capture (ThinDetector + OCR)", () -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("win")) {
                System.out.println("  skipped: not on Windows");
                return;
            }
            WindowsCapture wc = new WindowsCapture();
            var foreground = wc.getForegroundWindowInfo();
            long handle = foreground.handle();
            if (handle == 0) {
                System.out.println("  skipped: no foreground window");
                return;
            }
            String app = foreground.app();
            String title = foreground.title();
            System.out.println("  app=" + app + " title=" + title);

            UiaTreeWalker.UiaWalkResult walk;
            try { walk = wc.walkTree(handle); }
            catch (Exception e) { walk = new UiaTreeWalker.UiaWalkResult("", null); }

            long t0 = System.currentTimeMillis();
            ContentResult result = wc.createCapture().capture(
                    handle, app, title, walk.root(), walk.text());
            long t1 = System.currentTimeMillis();

            System.out.println("  source=" + result.source() + " uia_chars=" + result.uiaChars()
                    + " ocr_chars=" + result.ocrChars() + " time=" + (t1-t0) + "ms");
            System.out.println("  transient_text_chars=" + result.textContent().length()
                    + " context_title_detected=" + (result.contextTitle() != null));
            check(result.source() != null && !result.source().isEmpty(), "source should be non-empty");
            if (result.ocrChars() > 0) {
                System.out.println("  >>> OCR PRODUCED " + result.ocrChars() + " CHARACTERS <<<");
            }
        });

        System.out.println("\n=== Content Verification: " + passed + " passed, " + failed + " failed ===");
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

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    @FunctionalInterface
    interface Step { void run() throws Exception; }
}
