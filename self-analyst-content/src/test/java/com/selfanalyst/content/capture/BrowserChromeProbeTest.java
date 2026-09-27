package com.selfanalyst.content.capture;

import com.selfanalyst.content.uia.UiaNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserChromeProbeTest {

    @Test
    void extractsHostAndPrivateBrowsingWithoutKeepingCredentials() {
        UiaNode root = UiaNode.create("Chrome", null, 50032, "Window", null, false);
        UiaNode address = UiaNode.create("Address and search bar",
                "https://user:secret@github.com/org/repo?token=1", 50004, "Edit", null, false);
        root.addChild(address);
        root.addChild(UiaNode.create("Incognito", null, 50020, "Text", null, false));

        BrowserChromeProbe.Projection projection = BrowserChromeProbe.probe(root, "page body");
        assertNotNull(projection);
        assertEquals("github.com", projection.urlHost());
        assertTrue(projection.privateBrowsing());
        assertNull(BrowserChromeProbe.hostOf("not a host"));
        assertEquals("example.com", BrowserChromeProbe.hostOf("https://EXAMPLE.com/path"));
        assertTrue(BrowserChromeProbe.supports("chrome.exe"));
        assertFalse(BrowserChromeProbe.supports("Weixin.exe"));
    }

    @Test
    void browserCaptureDoesNotCreateContextTitle() {
        UiaNode root = UiaNode.create("Chrome", null, 50032, "Window", null, false);
        root.addChild(UiaNode.create("Address", "https://bilibili.com/video/1", 50004, "Edit", null, false));
        TitleCaptureResult result = new TitleCapture().capture("chrome.exe", root, "long page text");
        assertNull(result.contextTitle());
        assertEquals("window", result.titleSource());
        assertEquals("bilibili.com", result.urlHost());
        assertEquals(0, result.uiaChars());
    }
}
