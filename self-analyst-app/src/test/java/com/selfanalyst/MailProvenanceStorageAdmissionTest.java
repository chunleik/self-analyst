package com.selfanalyst;

import com.selfanalyst.desktop.store.ChatSessionStore;
import com.selfanalyst.integrations.policy.MailSessionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class MailProvenanceStorageAdmissionTest {
    @TempDir Path root;
    @Test void legacyChatDatabaseWithoutMailTablesIsAdmittedWithoutMutation() throws Exception {
        Path database=database();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
            s.execute("DROP TABLE mail_session_origins");s.execute("DROP TABLE mail_model_targets");
            s.execute("DELETE FROM metadata WHERE key='mail_provenance_version'");
        }
        byte[] before=Files.readAllBytes(database);
        RuntimeStorageCompatibility.verify(root);
        assertArrayEquals(before,Files.readAllBytes(database));
    }
    @Test void currentMailMetadataIsAdmittedButPartialOrUnknownVersionIsRejected() throws Exception {
        Path database=database(); RuntimeStorageCompatibility.verify(root);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) { s.execute("UPDATE metadata SET value='99' WHERE key='mail_provenance_version'"); }
        assertThrows(RuntimeStorageGuard.StorageException.class,()->RuntimeStorageCompatibility.verify(root));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) { s.execute("UPDATE metadata SET value='1' WHERE key='mail_provenance_version'");s.execute("DROP TABLE mail_model_targets"); }
        assertThrows(RuntimeStorageGuard.StorageException.class,()->RuntimeStorageCompatibility.verify(root));
    }
    private Path database() {
        try(var store=new ChatSessionStore(root.resolve("memory"))) {
            String id=store.create(new ChatSessionStore.CreateRequest()).id;
            var policy=new MailSessionPolicy(store);policy.consentTarget(id,"https://model.example/v1");policy.beforeMailResult(id,"https://model.example/v1");
        }
        return root.resolve("memory/chat-sessions/chat.db");
    }
}
