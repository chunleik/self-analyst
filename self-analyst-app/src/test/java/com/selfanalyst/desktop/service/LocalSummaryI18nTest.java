package com.selfanalyst.desktop.service;

import com.selfanalyst.i18n.Lang;
import com.selfanalyst.events.store.Database;
import com.selfanalyst.events.store.EventStore;
import com.selfanalyst.events.store.PulseTimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalSummaryI18nTest {
    @Test void englishEmptyScreenHasNoChineseGeneratedCopy(@TempDir Path dir) throws Exception {
        try (var database = new Database(dir)) {
            var facts = new SummaryService(new EventStore(database, PulseTimeConfig.DEFAULT), null, Lang.english());
            var assembler = new DesktopSummaryAssembler(facts, new BehaviorAdviceService(Lang.english()),
                    new SummaryPromptService(), null, null, Clock.systemUTC());
            var payload = assembler.assemble(new DesktopSummaryAssembler.Request(false, 0, Lang.english(), null));
            assertFalse(payload.toString().matches("(?s).*[\\p{IsHan}].*"), payload.toString());
            assertTrue(payload.toString().contains("No activity data"));
            assertEquals("1h 1m", SummaryService.formatDuration(3660, Lang.english()));
            assertEquals("1小时1分钟", SummaryService.formatDuration(3660, Lang.chinese()));
            assertEquals(61, SummaryFactFingerprint.parseMinutes("1h 1m"));
            assertEquals(61, SummaryFactFingerprint.parseMinutes("1小时1分钟"));
        }
    }

    @Test void everyLocalAdviceRuleUsesEnglishResources() {
        var service = new BehaviorAdviceService(Lang.english());
        for (var data : List.of(
                new SummaryService.BehaviorData(7, 20, 60, 20, 20, 80, 100, List.of()),
                new SummaryService.BehaviorData(7, 70, 60, 20, 20, 200, 100, List.of()),
                new SummaryService.BehaviorData(7, 70, 60, 100, 20, 100, 100, List.of()),
                new SummaryService.BehaviorData(7, 150, 100, 20, 20, 100, 100, List.of()))) {
            assertFalse(service.generate(data).toString().matches("(?s).*[\\p{IsHan}].*"));
        }
    }
}
