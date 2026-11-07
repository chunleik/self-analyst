package com.selfanalyst.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyRuntimeTest {
    @Test void listsNormalizeAndModelUsesUnion() {
        var runtime = new PrivacyRuntime(" PotPlayer.exe ; vlc.exe, ,;", " CHAT.exe ;potplayer.EXE", " EXAMPLE.com ; private.test, ");
        var capture = runtime.captureExcluded();
        var model = runtime.modelPolicy().get();
        assertTrue(capture.test("POTPLAYER.EXE"));
        assertTrue(capture.test("vlc.exe"));
        assertFalse(capture.test("chat.exe"));
        assertFalse(capture.test("PotPlayerMini.exe"));
        assertFalse(capture.test(null));
        assertFalse(capture.test(""));
        assertTrue(model.excludedApp("potplayer.exe"));
        assertTrue(model.excludedApp("VLC.EXE"));
        assertTrue(model.excludedApp("chat.exe"));
        assertTrue(model.excludedHost("EXAMPLE.COM"));
        assertTrue(model.excludedTitle("PRIVATE.TEST page"));
        assertFalse(model.excludedApp("other.exe"));
    }

    @Test void retainedFunctionsReadReplacementsAndOldPolicyStaysImmutable() {
        var runtime = new PrivacyRuntime("old.exe", "hidden.exe", "old.test");
        var capture = runtime.captureExcluded();
        var models = runtime.modelPolicy();
        var old = models.get();
        runtime.apply("new.exe", "", "new.test");
        assertFalse(capture.test("old.exe"));
        assertTrue(capture.test("NEW.EXE"));
        assertFalse(models.get().excludedApp("hidden.exe"));
        assertTrue(models.get().excludedApp("new.exe"));
        assertFalse(models.get().excludedHost("old.test"));
        assertTrue(models.get().excludedHost("new.test"));
        assertTrue(old.excludedApp("old.exe"));
        assertTrue(old.excludedHost("old.test"));
        runtime.apply(null, null, null);
        assertFalse(capture.test("new.exe"));
        assertFalse(models.get().excludedApp("new.exe"));
    }

    @Test void configLoadsCollectionExclusions(@TempDir Path dir) throws Exception {
        assertEquals("", Config.load(dir, Map.of()).collectionExcludeApps());
        Files.writeString(dir.resolve("config.toml"), "[events.collection]\nexcludeApps='private.exe'\n");
        Config config = Config.load(dir, Map.of());
        assertEquals("private.exe", config.collectionExcludeApps());
        assertTrue(new PrivacyRuntime(config).captureExcluded().test("private.exe"));
        assertEquals("private.exe", ConfigResolver.runtimeProperties(config).getProperty("events.collection.excludeApps"));
    }
}
