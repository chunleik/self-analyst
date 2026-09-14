package com.selfanalyst.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {
    @TempDir Path dir;
    @Test void correctsThenForgetsAndReportsActualSave() throws Exception {
        var service = new LongTermMemoryService(MemoryStore.load(dir));
        var item = service.createManual("project", "旧项目", "用户说明", null,"ui_manual","active");
        var tools = new MemoryTools(service);
        assertTrue(tools.findMemory("旧项目").contains(item.id()));
        assertEquals("saved=true", tools.correctMemory(item.id(), "旧项目", "新项目", false));
        assertFalse(service.profile().buildContextSummary().contains("旧项目"));
        assertTrue(tools.correctMemory(item.id(), "旧项目", "错误覆盖", false).startsWith("saved=false"));
        assertEquals("saved=true", tools.correctMemory(item.id(), "新项目", "", true));
        assertFalse(service.profile().buildContextSummary().contains("新项目"));
    }
    @Test void failureDoesNotClaimSuccessOrChangeContext() throws Exception {
        var service = new LongTermMemoryService(MemoryStore.load(dir));
        var item = service.createManual("project", "旧项目", "用户说明", null,"ui_manual","active");
        Files.delete(dir.resolve("memory.json")); Files.createDirectory(dir.resolve("memory.json"));
        Files.writeString(dir.resolve("memory.json/block"), "block");
        assertTrue(new MemoryTools(service).correctMemory(item.id(),"旧项目","新项目",false).startsWith("saved=false"));
        assertEquals("旧项目", service.list(null,null,null,null).getFirst().content());
    }
}
