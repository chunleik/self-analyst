package com.selfanalyst.desktop.store;

import com.selfanalyst.agent.SelfAnalystAgent;
import io.agentscope.core.message.*;
import io.agentscope.core.state.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatImageStoreTest {
    @TempDir Path dir;
    public static byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
    static ChatSessionStore.Message user(List<String> ids) {
        var user = new ChatSessionStore.Message();
        user.role = "user"; user.status = "sent"; user.content = ""; user.imageIds = ids;
        return user;
    }
    @Test void uploadBindRestoreAndDelete() throws Exception {
        String session, message, image;
        try (var store = new ChatSessionStore(dir)) {
            session = store.create(null).id;
            var uploaded = store.images().upload(session, new ByteArrayInputStream(png()), "image/png");
            image = uploaded.id();
            var saved = store.appendMessages(session, List.of(user(List.of(image)))).getFirst();
            message = saved.id;
            assertEquals(image, saved.images.getFirst().id());
            assertEquals(3, uploaded.width());
            assertArrayEquals(png(), store.images().read(session, image));
            assertThrows(IllegalArgumentException.class, () -> store.images().removeDraft(session, image));
            assertThrows(IllegalArgumentException.class, () -> store.appendMessages(session, List.of(user(List.of(image)))));
        }
        try (var store = new ChatSessionStore(dir)) {
            assertEquals(message, store.getSession(session).messages.getFirst().id);
            assertEquals(image, store.getSession(session).messages.getFirst().images.getFirst().id());
            store.beginDeletion(session);
            assertThrows(IllegalArgumentException.class, () -> store.images().read(session, image));
        }
        try (var store = new ChatSessionStore(dir)) {
            assertNull(store.getSession(session));
            assertFalse(Files.exists(dir.resolve("chat-images").resolve(session).resolve(image)));
        }
    }
    @Test void rejectsInvalidUploadsAndRollsBackWholeBatch() throws Exception {
        try (var store = new ChatSessionStore(dir)) {
            String a = store.create(null).id, b = store.create(null).id;
            var image = store.images().upload(a, new ByteArrayInputStream(png()), "image/png");
            assertThrows(IllegalArgumentException.class, () -> store.appendMessages(b, List.of(user(List.of(image.id())))));
            assertTrue(store.getSession(b).messages.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.appendMessages(a,
                    List.of(user(List.of(image.id())), user(List.of(image.id())))));
            assertTrue(store.getSession(a).messages.isEmpty());
            assertEquals(1, store.appendMessages(a, List.of(user(List.of(image.id())))).size());
            assertThrows(IllegalArgumentException.class, () -> store.images().upload(a, new ByteArrayInputStream(png()), "image/jpeg"));
            assertThrows(IllegalArgumentException.class, () -> store.images().upload(a, new ByteArrayInputStream(new byte[0]), "image/png"));
            assertThrows(IllegalArgumentException.class, () -> store.images().upload(a, new ByteArrayInputStream(new byte[ChatImageStore.MAX_BYTES + 1]), "image/png"));
            assertThrows(IllegalArgumentException.class, () -> store.images().read(a, "../../secret"));
            var excessive = user(List.of("1".repeat(32), "2".repeat(32), "3".repeat(32), "4".repeat(32), "5".repeat(32)));
            assertThrows(IllegalArgumentException.class, () -> store.appendMessages(a, List.of(excessive)));
            byte[] hugePixels = png();
            java.nio.ByteBuffer.wrap(hugePixels).putInt(16, 10000).putInt(20, 10000);
            var crc = new java.util.zip.CRC32(); crc.update(hugePixels, 12, 17);
            java.nio.ByteBuffer.wrap(hugePixels).putInt(29, (int) crc.getValue());
            assertThrows(IllegalArgumentException.class, () -> store.images().upload(a, new ByteArrayInputStream(hugePixels), "image/png"));
        }
    }
    @Test void cleanupRespectsBothHistoriesAndDraftExpiration() throws Exception {
        try (var store = new ChatSessionStore(dir)) {
            String session = store.create(null).id;
            var image = store.images().upload(session, new ByteArrayInputStream(png()), "image/png");
            var draft = store.images().upload(session, new ByteArrayInputStream(png()), "image/png");
            var saved = store.appendMessages(session, List.of(user(List.of(image.id())))).getFirst();
            var states = new JsonFileAgentStateStore(dir.resolve("agent-state/self-analyst-chat"));
            var state = AgentState.builder().userId("desktop").sessionId(session).build();
            state.contextMutable().add(Msg.builder().id(saved.id).role(MsgRole.USER)
                    .content(SelfAnalystAgent.imageContent("", session, saved.images)).build());
            states.save("desktop", session, "agent_state", state);
            store.documentTransaction(c -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM messages");
                    s.executeUpdate("UPDATE chat_images SET created_at=0 WHERE message_id IS NULL");
                }
                return null;
            });
            store.images().cleanup();
            assertNotNull(store.images().detail(session, image.id()));
            assertNull(store.images().detail(session, draft.id()));
            states.delete("desktop", session);
            states.close();
            store.images().cleanup();
            assertNull(store.images().detail(session, image.id()));
        }
    }
}
