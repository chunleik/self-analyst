package com.selfanalyst;

import com.selfanalyst.integrations.IntegrationConfig;
import com.selfanalyst.integrations.IntegrationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationStorageAdmissionTest {
    @TempDir Path temporary;
    @Test void recognizesMetadataBackupAndLockWithoutRewriting() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("data")).toRealPath();
        try (var store=new IntegrationStore(root)) {
            store.save(0,IntegrationConfig.empty());store.save(1,IntegrationConfig.empty().withRevision(1));
        }
        byte[] before=Files.readAllBytes(root.resolve("config/integrations.json"));
        RuntimeStorageCompatibility.verify(root);
        assertArrayEquals(before,Files.readAllBytes(root.resolve("config/integrations.json")));
        assertTrue(Files.exists(root.resolve("config/integrations.json.bak")));
    }
    @Test void unknownVersionFailsBeforeRuntimeAdmissionAndPreservesOriginal() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("data")).toRealPath();
        Files.createDirectories(root.resolve("config"));
        Path file=root.resolve("config/integrations.json");
        String invalid="{\"schemaVersion\":99,\"revision\":0,\"mcpServers\":[],\"mailAccounts\":[],\"toolApprovals\":[]}";
        Files.writeString(file,invalid);
        assertThrows(RuntimeStorageGuard.StorageException.class,()->RuntimeStorageGuard.acquire(root));
        assertEquals(invalid,Files.readString(file));
        assertFalse(Files.exists(root.resolve("storage-format.json")));
        assertFalse(Files.exists(root.resolve("config/.integrations.lock")));
        assertFalse(Files.exists(root.resolve("memory")));
    }
}
