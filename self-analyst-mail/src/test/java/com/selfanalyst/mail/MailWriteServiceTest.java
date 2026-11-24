package com.selfanalyst.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.selfanalyst.mail.MailException.Code.*;
import static com.selfanalyst.mail.MailWriteService.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

class MailWriteServiceTest {
    static final MailAccount QQ = new MailAccount("fixture", MailAccount.Provider.QQ, "sender@qq.com", "Synthetic", true,
            "all", Set.of(), true, 99);

    @Test void exactImmutableApprovedPayloadIsUsedAndCannotReplay() {
        var calls = new AtomicInteger();
        var service = new MailWriteService((operation, secret, gate, timeout) -> {
            assertEquals("sender@qq.com", operation.account().email());
            assertEquals(List.of("Case+tag@example.org"), operation.to());
            assertEquals("中文主题", operation.subject());
            assertEquals("first\nsecond\nthrid\n", operation.body());
            assertEquals("smtps://smtp.qq.com:465", operation.target());
            assertArrayEquals("synthetic-secret".toCharArray(), secret);
            gate.beginSubmission();
            calls.incrementAndGet();
        }, Duration.ofSeconds(3));
        var recipients = new ArrayList<>(List.of("Case+tag@EXAMPLE.ORG"));
        var prepared = service.prepare(QQ, SEND, recipients, "中文主题", "first\r\nsecond\rthrid\n");
        recipients.set(0, "other@example.org");
        assertEquals(List.of("Case+tag@example.org"), prepared.approvalDetails().get("to"));
        assertEquals(99L, prepared.approvalDetails().get("accountRevision"));
        assertFalse(prepared.toString().contains("中文主题"));
        var code = "synthetic-secret".toCharArray();
        assertEquals(MailWriteService.Status.ACCEPTED_BY_SMTP, service.execute(prepared, code, () -> true, new MailCancellation()).status());
        assertArrayEquals(new char[code.length], code);
        assertEquals(ACCESS_DENIED, assertThrows(MailException.class,
                () -> service.execute(prepared, code, () -> true, new MailCancellation())).code());
        assertEquals(1, calls.get());
    }

    @Test void invalidAddressesHeadersRecipientsAndByteBudgetNeverReachTransport() throws Exception {
        var service = new MailWriteService((op, secret, gate, timeout) -> fail("validation does not connect"), Duration.ofSeconds(3));
        for (String recipient : List.of("a@example.com\r\nBcc:x@y.com", "Group: a@example.com;", "a@local", "<a@example.com>", "a@example.com,b@example.com", "a..b@example.net", "i@b_.com"))
            assertEquals(INVALID_MESSAGE, assertThrows(MailException.class,
                    () -> service.prepare(QQ, SEND, List.of(recipient), "subject", "body")).code());
        assertEquals(INVALID_MESSAGE, assertThrows(MailException.class,
                () -> service.prepare(QQ, SEND, List.of("a@example.com", "A@EXAMPLE.COM"), "subject", "body")).code());
        assertEquals(INVALID_MESSAGE, assertThrows(MailException.class,
                () -> service.prepare(QQ, SEND, List.of(), "subject", "body")).code());
        assertEquals(INVALID_MESSAGE, assertThrows(MailException.class,
                () -> service.prepare(QQ, CREATE_DRAFT, List.of(), "subject\nBcc:", "body")).code());
        assertEquals(INVALID_MESSAGE, assertThrows(MailException.class,
                () -> service.prepare(QQ, CREATE_DRAFT, List.of(), "subject", "函".repeat(MailWriteService.MAX_BODY_BYTES))).code());
        assertEquals(INVALID_MESSAGE, assertThrows(MailException.class,
                () -> service.prepare(QQ, CREATE_DRAFT, List.of(), "subject", "\uD800")).code());
        assertEquals("imaps://imap.qq.com:993", service.prepare(QQ, CREATE_DRAFT, List.of(), "subject", "").target());
        var maxRecipients = new ArrayList<String>();
        for (int i = 0; i < MailWriteService.MAX_RECIPIENTS; i++) maxRecipients.add(
                "a".repeat(63) + i + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(60));
        var details = service.prepare(QQ, SEND, maxRecipients, "😀".repeat(MailWriteService.MAX_SUBJECT_CODE_POINTS),
                "\\".repeat(MailWriteService.MAX_BODY_BYTES)).approvalDetails();
        assertTrue(new ObjectMapper().writeValueAsBytes(details).length < 32768,
                "Worst-case JSON remains fully visible in the ApprovalRegistry budget");
    }

    @Test void rejectionCancellationAndStaleServiceFailClosedAndWipeSecrets() {
        var calls = new AtomicInteger();
        MailWriteTransport transport = (op, secret, gate, timeout) -> calls.incrementAndGet();
        var service = new MailWriteService(transport, Duration.ofSeconds(3));
        var other = new MailWriteService(transport, Duration.ofSeconds(3));
        var code = "synthetic-secret".toCharArray();
        assertEquals(ACCESS_DENIED, assertThrows(MailException.class, () -> service.execute(
                service.prepare(QQ, SEND, List.of("receiver@example.org"), "subject", "body"), code,
                () -> false, new MailCancellation())).code());
        assertArrayEquals(new char[code.length], code);
        var cancelled = new MailCancellation(); cancelled.cancel();
        assertEquals(CANCELLED, assertThrows(MailException.class, () -> service.execute(
                service.prepare(QQ, CREATE_DRAFT, List.of(), "subject", "body"), code,
                () -> true, cancelled)).code());
        assertEquals(ACCESS_DENIED, assertThrows(MailException.class, () -> service.execute(
                other.prepare(QQ, CREATE_DRAFT, List.of(), "subject", "body"), code,
                () -> true, new MailCancellation())).code());
        assertEquals(0, calls.get());
    }

    @Test void revocationBetweenPreparationAndCommitPermitsNoMutation() throws Exception {
        var authorized = new AtomicBoolean(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var service = new MailWriteService((op, secret, gate, timeout) -> {
            entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            gate.beginSubmission();
            writes.incrementAndGet();
        }, Duration.ofSeconds(5));
        var operation = service.prepare(QQ, CREATE_DRAFT, List.of(), "subject", "body");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> assertThrows(MailException.class,
                    () -> service.execute(operation, "test-secret".toCharArray(), authorized::get, new MailCancellation())).code());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            authorized.set(false);
            release.countDown();
            assertEquals(ACCESS_DENIED, pending.get(3, TimeUnit.SECONDS));
        }
        assertEquals(0, writes.get());
    }

    @Test void deadlineBeforeOrAfterSubmissionIsDistinguishedWithoutRetry() throws Exception {
        for (boolean committed : List.of(false, true)) {
            var attempts = new AtomicInteger();
            var ended = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            var service = new MailWriteService((operation, secret, gate, timeout) -> {
                attempts.incrementAndGet();
                if (committed) gate.beginSubmission();
                entered.countDown();
                try { Thread.sleep(Duration.ofSeconds(20)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { ended.countDown(); }
            }, Duration.ofSeconds(1));
            var prepared = service.prepare(QQ, SEND, List.of("receiver@example.org"), "subject", "body");
            var code = "synthetic-secret".toCharArray();
            assertEquals(committed ? OUTCOME_UNKNOWN : TIMEOUT,
                    assertThrows(MailException.class, () -> service.execute(prepared, code, () -> true, new MailCancellation())).code());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(ended.await(1, TimeUnit.SECONDS));
            assertArrayEquals(new char[code.length], code);
            assertEquals(1, attempts.get());
        }
    }
}
