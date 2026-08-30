package com.selfanalyst.content.capture;

import com.selfanalyst.content.uia.UiaNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCapturePolicyTest {

    private final ContextCapturePolicy policy = new ContextCapturePolicy();

    @Test
    void excludesSensitiveAppsAndCredentialTitles() {
        assertTrue(policy.isExcluded("keepass.exe", "KeePass"));
        assertTrue(policy.isExcluded("app.exe", "输入密码"));
        assertFalse(policy.isExcluded("Weixin.exe", "微信"));
    }

    @Test
    void extractsDocumentNameOnlyFromRichDocumentStructure() {
        UiaNode root = UiaNode.create("root", null, 50032, "Window", null, false);
        UiaNode document = UiaNode.create("文章标题", null, 50030, "Document", null, false);
        document.addChild(UiaNode.create("正文".repeat(100), null, 50020, "Text", null, false));
        root.addChild(document);

        assertEquals("文章标题", ContextCapturePolicy.extractVerifiedDocumentTitle(root));
        assertNull(ContextCapturePolicy.extractVerifiedDocumentTitle(
                UiaNode.create("短正文", null, 50030, "Document", null, false)));
        assertNull(ContextCapturePolicy.extractVerifiedDocumentTitle(null));
    }
}
