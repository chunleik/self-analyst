package com.selfanalyst.integration.content;

import com.selfanalyst.content.ContentEvent;
import com.selfanalyst.content.capture.ContextCapturePolicy;
import com.selfanalyst.content.capture.TitleCapture;
import com.selfanalyst.content.capture.TitleCaptureResult;
import com.selfanalyst.content.platform.WindowsCapture;
import com.selfanalyst.content.uia.UiaNode;
import com.selfanalyst.content.uia.UiaTreeWalker;

import java.time.Instant;
import java.util.Map;

public class ContentVerification {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        step("context privacy exclusions", () -> {
            ContextCapturePolicy policy = new ContextCapturePolicy();
            check(policy.isExcluded("keepass.exe", "KeePass"), "password manager excluded");
            check(policy.isExcluded("app.exe", "输入密码"), "credential title excluded");
            check(!policy.isExcluded("Weixin.exe", "微信"), "Weixin remains eligible");
        });

        step("Weixin conversation title projection", () -> {
            String text = "微信\n项目讨论群\n聊天记录\n聊天信息\n"
                    + "SELF_ANALYST_FORBIDDEN_BODY_7F3A";
            TitleCaptureResult result = new TitleCapture().capture("Weixin.exe", null, text);
            check("项目讨论群".equals(result.contextTitle()), "conversation title extracted");
            check(!result.toString().contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"),
                    "body must not cross projection boundary");
        });

        step("UIA Document title projection", () -> {
            UiaNode root = UiaNode.create("微信", null, 50032, "Window", null, false);
            root.addChild(UiaNode.create("文章标题", null, 50030, "Document", null, false));
            TitleCaptureResult result = new TitleCapture().capture(
                    "Weixin.exe", root, "正文".repeat(100));
            check("文章标题".equals(result.contextTitle()), "article title extracted");
            check("article".equals(result.contextKind()), "article kind recorded");
        });

        step("UiaTreeWalker control type names", () -> {
            check("Button".equals(UiaTreeWalker.controlTypeName(50000)), "50000 should be Button");
            check("Edit".equals(UiaTreeWalker.controlTypeName(50004)), "50004 should be Edit");
            check("Text".equals(UiaTreeWalker.controlTypeName(50020)), "50020 should be Text");
            check("Window".equals(UiaTreeWalker.controlTypeName(50032)), "50032 should be Window");
        });

        step("ContentEvent persists title fields only", () -> {
            ContentEvent event = new ContentEvent(
                    Instant.now(), 5.0, "Weixin.exe", "微信", "项目讨论群",
                    "chat", "uia_context", "high", 500);
            Map<String, Object> data = event.toHeartbeatData();
            check("项目讨论群".equals(data.get("context_title")), "context title present");
            check(!data.containsKey("text_content"), "body field absent");
            check(!data.containsKey("ocr_chars"), "OCR diagnostics absent");
        });

        step("real foreground UIA title capture", () -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("win")) {
                System.out.println("  skipped: not on Windows");
                return;
            }
            WindowsCapture windows = new WindowsCapture();
            var foreground = windows.getForegroundWindowInfo();
            if (foreground.handle() == 0) {
                System.out.println("  skipped: no foreground window");
                return;
            }
            UiaTreeWalker.UiaWalkResult walk = new UiaTreeWalker().walk(foreground.handle());
            TitleCaptureResult result = new TitleCapture().capture(
                    foreground.app(), walk.root(), walk.text());
            System.out.println("  app=" + foreground.app()
                    + " title=" + foreground.title()
                    + " uia_chars=" + result.uiaChars()
                    + " context_title_detected=" + (result.contextTitle() != null));
        });

        System.out.println("\n=== Content Verification: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void step(String description, Step action) {
        try {
            action.run();
            System.out.println("[PASS] " + description);
            passed++;
        } catch (Throwable error) {
            System.out.println("[FAIL] " + description + " — " + error.getMessage());
            failed++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    interface Step { void run() throws Exception; }
}
