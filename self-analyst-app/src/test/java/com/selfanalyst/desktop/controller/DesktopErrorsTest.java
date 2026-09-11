package com.selfanalyst.desktop.controller;

import com.selfanalyst.i18n.Lang;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DesktopErrorsTest {
    @Test void englishAndChineseErrorsRetainPathAndStableCode() {
        String path = "D:/用户资料/{path}";
        var english = DesktopErrors.payload(Lang.english(), "error.file.pathMissing", Map.of("path", path));
        var chinese = DesktopErrors.payload(Lang.chinese(), "error.file.pathMissing", Map.of("path", path));
        assertEquals("error.file.pathMissing", english.get("errorCode"));
        assertEquals(Map.of("path", path), english.get("errorParams"));
        assertEquals("The watched folder does not exist or is not a directory: " + path, english.get("error"));
        assertEquals("监控目录不存在或不是目录：" + path, chinese.get("error"));
    }
    @Test void configurationFailureDoesNotExposeInternalException() {
        var payload = DesktopErrors.payload(Lang.english(), "error.configSave", Map.of());
        assertEquals("Failed to save or apply configuration", payload.get("error"));
        assertEquals(Map.of(), payload.get("errorParams"));
    }
}
