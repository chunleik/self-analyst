package com.selfanalyst.mail;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.selfanalyst.mail.MailAccount.Provider.*;
import static org.junit.jupiter.api.Assertions.*;

class MailProviderProfilesTest {
    @Test void exactHostBindingsAreKeptForExistingAndNewProviders() {
        assertProfile(QQ, "qq", "qq.com", "imap.qq.com", "smtp.qq.com", false);
        assertProfile(NETEASE_163, "163", "163.com", "imap.163.com", "smtp.163.com", true);
        assertProfile(NETEASE_126, "126", "126.com", "imap.126.com", "smtp.126.com", true);
        assertProfile(GMAIL, "gmail", "gmail.com", "imap.gmail.com", "smtp.gmail.com", false);
        assertThrows(IllegalArgumentException.class, () -> GMAIL.bindingEmail("synthetic@mail.example.org"));
        assertThrows(IllegalArgumentException.class, () -> fromId("https://local.example.org"));
        assertThrows(IllegalArgumentException.class, () -> fromId("outlook"));
    }

    @Test void gmailRejectsUnsupportedCredentialShapesBeforeConnectAndWipesWriteInput() {
        var readAttempts = new AtomicInteger();
        var read = new AngusMailTransport((account, code, cancellation, limits, sockets) -> {
            readAttempts.incrementAndGet();
            throw new IllegalStateException("test-only");
        });
        var gmail = account(GMAIL);
        for (String invalid : List.of("short", "abcdefghijklmnopq", "abcd efg hijk lmnop", "abcd\tefgh ijkl mnop", "abcd efgh ijkl mno!")) {
            assertFalse(GMAIL.acceptsAuthorizationCode(invalid.toCharArray()));
            var failure = assertThrows(MailException.class,
                    () -> read.connect(gmail, invalid.toCharArray(), new MailCancellation(), MailLimits.defaults()));
            assertEquals(MailException.Code.AUTHENTICATION_REQUIRED, failure.code());
        }
        assertEquals(0, readAttempts.get());
        var writes = new AtomicInteger();
        var writer = new MailWriteService((message, code, gate, timeout) -> {
            writes.incrementAndGet();
            assertArrayEquals("abcdefghijklmnop".toCharArray(), code);
            assertEquals("smtps://smtp.gmail.com:465", message.target());
            gate.beginSubmission();
        }, Duration.ofSeconds(2));
        char[] wrongShape = "short".toCharArray();
        assertEquals(MailException.Code.AUTHENTICATION_REQUIRED, assertThrows(MailException.class,
                () -> writer.execute(prepare(writer, gmail), wrongShape, () -> true, new MailCancellation())).code());
        assertEquals(0, writes.get());
        assertArrayEquals(new char[wrongShape.length], wrongShape);
        char[] grouped = "abcd efgh ijkl mnop".toCharArray();
        assertTrue(GMAIL.acceptsAuthorizationCode(grouped));
        assertEquals(MailWriteService.Status.ACCEPTED_BY_SMTP, writer.execute(prepare(writer, gmail), grouped,
                () -> true, new MailCancellation()).status());
        assertEquals(1, writes.get());
        assertArrayEquals(new char[grouped.length], grouped);
    }

    @Test void gmailReadUsesCompactedAppPasswordWithoutChangingCallersArray() {
        char[] grouped = "abcd efgh ijkl mnop".toCharArray();
        final char[][] delivered = new char[1][];
        var read = new AngusMailTransport((account, code, cancellation, limits, sockets) -> {
            delivered[0] = code;
            assertArrayEquals("abcdefghijklmnop".toCharArray(), code);
            throw new jakarta.mail.AuthenticationFailedException("test-only");
        });
        assertEquals(MailException.Code.AUTHENTICATION_FAILED, assertThrows(MailException.class,
                () -> read.connect(account(GMAIL), grouped, new MailCancellation(), MailLimits.defaults())).code());
        assertArrayEquals("abcd efgh ijkl mnop".toCharArray(), grouped);
        assertArrayEquals(new char[16], delivered[0]);
    }

    private static MailWriteService.Prepared prepare(MailWriteService writer, MailAccount account) {
        return writer.prepare(account, MailWriteService.Operation.SEND, List.of("recipient@example.org"), "synthetic", "test");
    }
    private static MailAccount account(MailAccount.Provider provider) {
        return new MailAccount("profile", provider, "fixture@" + provider.domain(), "Synthetic", true, "all", Set.of(), false, 3);
    }
    private static void assertProfile(MailAccount.Provider profile, String id, String domain, String imap, String smtp, boolean imapId) {
        assertSame(profile, fromId(id));
        assertEquals(id, profile.id());
        assertEquals(domain, profile.domain());
        assertEquals(imap, profile.host());
        assertEquals(993, profile.port());
        assertEquals(smtp, profile.smtpHost());
        assertEquals(465, profile.smtpPort());
        assertEquals(imapId, profile.requiresImapId());
    }
}
