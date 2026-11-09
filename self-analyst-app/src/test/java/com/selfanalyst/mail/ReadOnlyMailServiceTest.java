package com.selfanalyst.mail;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyMailServiceTest {
    private final Instant now = Instant.parse("2026-11-09T00:00:00Z");
    static MailAccount account() { return new MailAccount("mail-1", MailAccount.Provider.QQ, "synthetic@qq.com", "Synthetic", true, Set.of("INBOX"), true, 1); }
    static ReadOnlyMailService.CallContext context() { return new ReadOnlyMailService.CallContext("session-1", "turn-1", new MailCancellation()); }
    static ReadOnlyMailService.SearchRequest query(String cursor) { return new ReadOnlyMailService.SearchRequest("mail-1", "INBOX", null, null, null, null, null, 1, cursor); }
    private ReadOnlyMailService service(AtomicReference<List<MailAccount>> accounts, FakeTransport transport, AtomicInteger marked, Clock clock) {
        return new ReadOnlyMailService(accounts::get, a -> "synthetic-secret".toCharArray(), new ReadOnlyMailService.AuthorizationBoundary() {
            public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean body) { }
            public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) { marked.incrementAndGet(); }
        }, transport, MailLimits.defaults(), clock);
    }
    @Test void fixedProvidersRejectEnterpriseAndCustomEndpoints() {
        assertEquals("imap.qq.com", account().provider().host());
        assertEquals(993, account().provider().port());
        assertEquals("imap.163.com", new MailAccount("id", MailAccount.Provider.NETEASE_163, "synthetic@163.com", null, false, null, false, 0).provider().host());
        for (String email : List.of("synthetic@vip.163.com", "synthetic@example.com", "x\r\nLOGIN@163.com", "x@imap.163.com", "x@qq.com@163.com")) {
            assertEquals(MailException.Code.INVALID_ACCOUNT, assertThrows(MailException.class,
                    () -> new MailAccount("id", MailAccount.Provider.NETEASE_163, email, null, true, null, true, 0)).code());
        }
        assertTrue(java.util.Arrays.stream(MailAccount.class.getRecordComponents()).noneMatch(c -> c.getName().equals("host") || c.getName().equals("credential")));
    }
    @Test void revokedAccountFolderAndAmbiguousAccountsNeverConnect() {
        FakeTransport transport = new FakeTransport();
        AtomicReference<List<MailAccount>> accounts = new AtomicReference<>(List.of(account()));
        try (var service = service(accounts, transport, new AtomicInteger(), Clock.fixed(now, ZoneOffset.UTC))) {
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class, () -> service.listFolders("other", context())).code());
            assertThrows(MailException.class, () -> service.searchMessages(new ReadOnlyMailService.SearchRequest("mail-1", "Other", null, null, null, null, null, 20, null), context()));
            MailAccount second = new MailAccount("mail-2", MailAccount.Provider.QQ, "other@qq.com", "Other", true, Set.of("INBOX"), true, 1);
            accounts.set(List.of(account(), second));
            assertEquals(MailException.Code.ACCOUNT_REQUIRED, assertThrows(MailException.class, () -> service.listFolders(null, context())).code());
            assertEquals(0, transport.connections.get());
        }
    }
    @Test void referencesUseUidValidityAndPermissionsAndNeverReinterpretMissingMessages() {
        FakeTransport transport = new FakeTransport();
        AtomicReference<List<MailAccount>> accounts = new AtomicReference<>(List.of(account()));
        AtomicInteger marked = new AtomicInteger();
        try (var service = service(accounts, transport, marked, Clock.fixed(now, ZoneOffset.UTC))) {
            var result = service.searchMessages(query(null), context());
            assertEquals(20, result.messages().getFirst().uid());
            assertEquals("unknown", result.coverage().providerVisibleRange());
            assertNotNull(result.nextCursor());
            String ref = result.messages().getFirst().reference();
            assertEquals("body", service.getMessage(ref, context()).body().text());
            transport.validity = 2;
            assertEquals(MailException.Code.STALE_REFERENCE, assertThrows(MailException.class, () -> service.getMessage(ref, context())).code());
            assertEquals(MailException.Code.STALE_REFERENCE, assertThrows(MailException.class, () -> service.searchMessages(query(result.nextCursor()), context())).code());
            transport.validity = 1; transport.missing = true;
            assertEquals(MailException.Code.STALE_REFERENCE, assertThrows(MailException.class, () -> service.getMessage(ref, context())).code());
            accounts.set(List.of(new MailAccount("mail-1", MailAccount.Provider.QQ, "synthetic@qq.com", "Synthetic", true, Set.of("INBOX"), false, 2)));
            assertEquals(MailException.Code.STALE_REFERENCE, assertThrows(MailException.class, () -> service.getMessage(ref, context())).code());
            assertEquals(MailException.Code.INVALID_CURSOR, assertThrows(MailException.class, () -> service.searchMessages(query(result.nextCursor()), context())).code());
            assertEquals(2, marked.get());
        }
    }
    @Test void queryAndSessionChangesInvalidateCursorAndReference() {
        try (var service = service(new AtomicReference<>(List.of(account())), new FakeTransport(), new AtomicInteger(), Clock.fixed(now, ZoneOffset.UTC))) {
            var result = service.searchMessages(query(null), context());
            var changed = new ReadOnlyMailService.SearchRequest("mail-1", "INBOX", null, null, null, "different", null, 1, result.nextCursor());
            assertEquals(MailException.Code.INVALID_CURSOR, assertThrows(MailException.class, () -> service.searchMessages(changed, context())).code());
            var other = new ReadOnlyMailService.CallContext("session-2", "turn", null);
            assertEquals(MailException.Code.STALE_REFERENCE, assertThrows(MailException.class, () -> service.getMessage(result.messages().getFirst().reference(), other)).code());
            assertEquals(MailException.Code.INVALID_CURSOR, assertThrows(MailException.class, () -> service.searchMessages(query(result.nextCursor()), other)).code());
        }
    }
    @Test void expiredReferenceDoesNotConnect() {
        AtomicReference<Instant> time = new AtomicReference<>(now);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        FakeTransport transport = new FakeTransport();
        try (var service = service(new AtomicReference<>(List.of(account())), transport, new AtomicInteger(), clock)) {
            String ref = service.searchMessages(query(null), context()).messages().getFirst().reference();
            time.set(now.plusSeconds(901));
            assertEquals(MailException.Code.STALE_REFERENCE, assertThrows(MailException.class, () -> service.getMessage(ref, context())).code());
            assertEquals(1, transport.connections.get());
        }
    }
    @Test void provenanceFailureFailsClosedAndCredentialsAreCleared() {
        FakeTransport transport = new FakeTransport();
        char[] secret = "synthetic-secret".toCharArray();
        var boundary = new ReadOnlyMailService.AuthorizationBoundary() {
            public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) { }
            public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) { throw new MailException(MailException.Code.ACCESS_DENIED); }
        };
        try (var service = new ReadOnlyMailService(() -> List.of(account()), a -> secret, boundary, transport, MailLimits.defaults(), Clock.systemUTC())) {
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class, () -> service.searchMessages(query(null), context())).code());
            assertArrayEquals(new char[secret.length], secret);
            assertEquals(1, transport.closed.get());
        }
    }
    @Test void bodyPermissionIsRecheckedAndMetadataDoesNotRequireBodyPermission() {
        MailAccount noBody = new MailAccount("mail-1", MailAccount.Provider.QQ, "synthetic@qq.com", "Synthetic", true, Set.of("INBOX"), false, 1);
        FakeTransport transport = new FakeTransport();
        try (var service = service(new AtomicReference<>(List.of(noBody)), transport, new AtomicInteger(), Clock.systemUTC())) {
            String ref = service.searchMessages(query(null), context()).messages().getFirst().reference();
            assertEquals(MailException.Code.BODY_ACCESS_DENIED, assertThrows(MailException.class, () -> service.getMessage(ref, context())).code());
            assertEquals(1, transport.connections.get());
        }
    }
    @Test void cancellationInterruptsAndSerializesPerAccount() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.block = new CountDownLatch(1);
        try (var service = service(new AtomicReference<>(List.of(account())), transport, new AtomicInteger(), Clock.systemUTC())) {
            var first = context();
            var pending = CompletableFuture.runAsync(() -> assertEquals(MailException.Code.CANCELLED,
                    assertThrows(MailException.class, () -> service.listFolders("mail-1", first)).code()));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            var second = context();
            var pending2 = CompletableFuture.runAsync(() -> assertEquals(MailException.Code.CANCELLED,
                    assertThrows(MailException.class, () -> service.listFolders("mail-1", second)).code()));
            second.cancellation().cancel(); first.cancellation().cancel();
            pending.get(2, TimeUnit.SECONDS); pending2.get(2, TimeUnit.SECONDS);
            assertEquals(1, transport.maxActive.get());
        }
    }
    @Test void totalDeadlineEndsBlockedWork() throws Exception {
        FakeTransport transport = new FakeTransport(); transport.block = new CountDownLatch(1);
        var boundary = new ReadOnlyMailService.AuthorizationBoundary() {
            public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) { }
            public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) { fail("Timed-out result must not be exposed"); }
        };
        var limits = new MailLimits(500, 20_000, 256_000, 64, 12, 2_000_000, Duration.ofMillis(100));
        try (var service = new ReadOnlyMailService(() -> List.of(account()), a -> "synthetic".toCharArray(), boundary,
                transport, limits, Clock.systemUTC())) {
            assertEquals(MailException.Code.TIMEOUT, assertThrows(MailException.class, () -> service.listFolders("mail-1", context())).code());
        }
    }
    @Test void revocationDuringNetworkWaitPreventsResultExposure() throws Exception {
        FakeTransport transport = new FakeTransport(); transport.block = new CountDownLatch(1);
        AtomicReference<List<MailAccount>> accounts = new AtomicReference<>(List.of(account()));
        AtomicInteger marked = new AtomicInteger();
        try (var service = service(accounts, transport, marked, Clock.systemUTC())) {
            var pending = CompletableFuture.runAsync(() -> assertEquals(MailException.Code.ACCESS_DENIED,
                    assertThrows(MailException.class, () -> service.listFolders("mail-1", context())).code()));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            accounts.set(List.of()); transport.block.countDown(); pending.get(2, TimeUnit.SECONDS);
            assertEquals(0, marked.get());
        }
    }
    @Test void testsOnlyListAndOpenInboxAndDoNotUseStoredCredentials() {
        FakeTransport transport = new FakeTransport();
        var boundary = new ReadOnlyMailService.AuthorizationBoundary() {
            public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) { fail(); }
            public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) { fail(); }
        };
        try (var service = new ReadOnlyMailService(() -> List.of(account()), a -> { fail(); return null; }, boundary, transport, MailLimits.defaults(), Clock.systemUTC())) {
            char[] secret = "temporary".toCharArray();
            assertTrue(service.testConnection(account(), secret, new MailCancellation()).readOnly());
            assertEquals("temporary", new String(secret));
            assertEquals(0, transport.bodyReads.get());
        }
    }
    @Test void pageSizesAreBounded() {
        assertEquals(20, new ReadOnlyMailService.SearchRequest("id", null, null, null, null, null, null, 0, null).pageSize());
        assertThrows(MailException.class, () -> new ReadOnlyMailService.SearchRequest("id", null, null, null, null, null, null, 51, null));
    }
    static class FakeTransport implements MailTransport {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger bodyReads = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block;
        long validity = 1;
        boolean missing;
        @Override public Connection connect(MailAccount account, char[] secret, MailCancellation cancellation, MailLimits limits) {
            connections.incrementAndGet();
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            return new Connection() {
                public List<String> listFolders() {
                    entered.countDown();
                    if (block != null) try { block.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); cancellation.check(); }
                    return List.of("INBOX", "Other");
                }
                public Mailbox openReadOnly(String folder) {
                    return new Mailbox() {
                        public long uidValidity() { return validity; }
                        public SearchBatch search(ReadOnlyMailService.SearchRequest request, Long beforeUid, int maxExamined) {
                            return new SearchBatch(List.of(envelope(20), envelope(10)), 1, 2, false);
                        }
                        public Envelope envelope(long uid) { return missing ? null : new Envelope(uid, "subject", List.of("source@example.invalid"), List.of(), Instant.parse("2026-11-09T00:00:00Z"), true, "synthetic", 4); }
                        public MailBodyReader.Body readBody(long uid, MailLimits limits) { bodyReads.incrementAndGet(); return new MailBodyReader.Body("body", false, List.of(), List.of()); }
                        public void close() { }
                    };
                }
                public void close() { active.decrementAndGet(); closed.incrementAndGet(); }
            };
        }
    }
}
