package com.selfanalyst.content.capture;

import com.selfanalyst.content.uia.UiaNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TitleCaptureTest {

    private final TitleCapture capture = new TitleCapture();

    @Test
    void extractsWeixinConversationWithoutRetainingBody() {
        String text = "微信\n项目讨论群\n聊天记录\n聊天信息\n"
                + "SELF_ANALYST_FORBIDDEN_BODY_7F3A";

        TitleCaptureResult result = capture.capture("Weixin.exe", null, text);

        assertEquals("项目讨论群", result.contextTitle());
        assertEquals("chat", result.contextKind());
        assertEquals("uia_context", result.titleSource());
        assertFalse(result.toString().contains("SELF_ANALYST_FORBIDDEN_BODY_7F3A"));
    }

    @Test
    void richWeixinDocumentProducesArticleTitle() {
        UiaNode root = UiaNode.create("微信", null, 50032, "Window", null, false);
        UiaNode document = UiaNode.create(
                "富内容文章标题", null, 50030, "Document", null, false);
        document.addChild(UiaNode.create(
                "正文".repeat(100), null, 50020, "Text", null, false));
        root.addChild(document);

        TitleCaptureResult result = capture.capture(
                "Weixin.exe", root, "正文".repeat(100));

        assertEquals("富内容文章标题", result.contextTitle());
        assertEquals("article", result.contextKind());
        assertEquals("uia_document", result.titleSource());
    }

    @Test
    void noReliableCandidateFallsBackToWindowSourceWithoutBody() {
        TitleCaptureResult result = capture.capture(
                "notes.exe", null, "正文但没有可靠标题");

        assertNull(result.contextTitle());
        assertEquals("window", result.titleSource());
        assertEquals("正文但没有可靠标题".length(), result.uiaChars());
    }

    @Test
    void unverifiedApplicationDocumentNameIsNotPersisted() {
        UiaNode document = UiaNode.create(
                "一段看起来像标题的短正文", null, 50030, "Document", null, false);

        TitleCaptureResult result = capture.capture(
                "unknown.exe", document, "一段看起来像标题的短正文");

        assertNull(result.contextTitle());
        assertEquals("window", result.titleSource());
    }

    @Test
    void weixinChatSurfaceDoesNotTreatDocumentNameAsArticleTitle() {
        UiaNode document = UiaNode.create(
                "一条短消息", null, 50030, "Document", null, false);
        document.addChild(UiaNode.create(
                "正文".repeat(100), null, 50020, "Text", null, false));

        TitleCaptureResult result = capture.capture(
                "Weixin.exe", document, "一条短消息\n聊天记录\n其他控件");

        assertNull(result.contextTitle());
        assertEquals("window", result.titleSource());
    }

    @Test
    void excludedApplicationCanPersistWindowMetadataWithoutUia() {
        TitleCaptureResult result = capture.windowOnly();

        assertNull(result.contextTitle());
        assertEquals("window", result.titleSource());
        assertEquals(0, result.uiaChars());
    }
}
