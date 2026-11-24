package com.selfanalyst.mail;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MailFolderScopeServiceTest {
    private static MailAccount account(String scope, Set<String> folders, boolean body, long revision) {
        return new MailAccount("mail-1", MailAccount.Provider.QQ, "synthetic@qq.com", "Synthetic", true,
                scope, folders, body, revision);
    }
    private static MailAccount all(boolean body) { return account("all", Set.of("INBOX"), body, 1); }
    private static ReadOnlyMailService.SearchRequest query(String folder, String cursor) {
        return new ReadOnlyMailService.SearchRequest("mail-1", folder, null, null, null, null, null, 1, cursor);
    }
    private static ReadOnlyMailService service(AtomicReference<List<MailAccount>> accounts,
                                               ReadOnlyMailServiceTest.FakeTransport transport, AtomicInteger marked) {
        return new ReadOnlyMailService(accounts::get, a -> "synthetic-secret".toCharArray(), new ReadOnlyMailService.AuthorizationBoundary() {
            public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) { }
            public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) { marked.incrementAndGet(); }
        }, transport, MailLimits.defaults(), Clock.systemUTC());
    }
    @Test void allScopeListsOver32FullNamesAndNewFoldersWithoutWhitelistRefreshOrBodyReads() {
        var transport = new ReadOnlyMailServiceTest.FakeTransport();
        transport.folders = IntStream.range(0, 40).mapToObj(i -> "Projects/项目/" + i).toList();
        var accounts = new AtomicReference<>(List.of(all(false)));
        try (var service = service(accounts, transport, new AtomicInteger())) {
            assertEquals(transport.folders, service.listFolders("mail-1", ReadOnlyMailServiceTest.context()));
            var newFolders = new ArrayList<>(transport.folders); newFolders.add("归档.2026.新增");
            transport.folders = List.copyOf(newFolders);
            assertEquals(newFolders, service.listFolders("mail-1", ReadOnlyMailServiceTest.context()));
            assertEquals(2, transport.folderLists.get());
            var result = service.searchMessages(query("归档.2026.新增", null), ReadOnlyMailServiceTest.context());
            assertEquals(List.of("归档.2026.新增"), transport.openedFolders);
            assertEquals(2, transport.folderLists.get(), "Search must not enumerate a stale whitelist or search all folders");
            assertEquals("unknown", result.coverage().providerVisibleRange());
            assertEquals(0, transport.bodyReads.get());
            assertEquals(MailException.Code.BODY_ACCESS_DENIED, assertThrows(MailException.class,
                    () -> service.getMessage(result.messages().getFirst().reference(), ReadOnlyMailServiceTest.context())).code());
        }
    }
    @Test void selectedScopeUsesExactNamesAndStarNeverGrantsOtherFolders() {
        var selected = account("selected", Set.of("INBOX", "Projects/项目", "*"), false, 1);
        var transport = new ReadOnlyMailServiceTest.FakeTransport();
        transport.folders = List.of("INBOX", "inbox", "Projects", "Projects/项目", "Projects/项目/子目录", "*", "Other");
        try (var service = service(new AtomicReference<>(List.of(selected)), transport, new AtomicInteger())) {
            assertEquals(List.of("INBOX", "Projects/项目", "*"), service.listFolders("mail-1", ReadOnlyMailServiceTest.context()));
            for (String folder : List.of("inbox", "Projects", "Projects/项目/子目录", "Other")) {
                assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                        () -> service.searchMessages(query(folder, null), ReadOnlyMailServiceTest.context())).code());
            }
            assertEquals(1, transport.connections.get());
            service.searchMessages(query("*", null), ReadOnlyMailServiceTest.context());
            assertEquals(List.of("*"), transport.openedFolders);
        }
        assertEquals("selected", ReadOnlyMailServiceTest.account().folderScope());
    }
    @Test void allScopeDoesNotTurnInvalidNamesIntoValidRequests() {
        var unrestricted = account("all", Set.of(), false, 1);
        assertTrue(unrestricted.allowsFolder("Projects/项目")); assertTrue(unrestricted.allowsFolder("x".repeat(256)));
        for (String invalid : Arrays.asList(null, "", " ", "Bad\r\nName", "Bad\u0000Name", "x".repeat(257)))
            assertFalse(unrestricted.allowsFolder(invalid));
        for (String invalid : List.of("", " ", "Bad\nName", "Bad\u0000Name", "x".repeat(257)))
            assertEquals(MailException.Code.SEARCH_UNSUPPORTED, assertThrows(MailException.class, () -> query(invalid, null)).code());
        assertThrows(MailException.class, () -> account("selected", Set.of(), false, 1));
        assertThrows(MailException.class, () -> account("*", Set.of("INBOX"), false, 1));
        assertThrows(MailException.class, () -> account(null, Set.of("INBOX"), false, 1));
    }
    @Test void directoryAndNameBudgetsRejectInsteadOfTruncatingEvenForSelectedOrConnectionTest() {
        for (MailAccount account : List.of(all(false), ReadOnlyMailServiceTest.account())) {
            var transport = new ReadOnlyMailServiceTest.FakeTransport();
            var marked = new AtomicInteger();
            try (var service = service(new AtomicReference<>(List.of(account)), transport, marked)) {
                transport.folders = IntStream.range(0, 256).mapToObj(i -> i == 0 ? "INBOX" : "Folder/" + i).toList();
                assertEquals(account.folderScope().equals("all") ? 256 : 1,
                        service.listFolders("mail-1", ReadOnlyMailServiceTest.context()).size());
                marked.set(0);
                for (List<String> folders : List.of(IntStream.range(0, 257).mapToObj(i -> "Folder/" + i).toList(), List.of("x".repeat(257)))) {
                    transport.folders = folders;
                    assertEquals(MailException.Code.LIMIT_EXCEEDED, assertThrows(MailException.class,
                            () -> service.listFolders("mail-1", ReadOnlyMailServiceTest.context())).code());
                    assertEquals(MailException.Code.LIMIT_EXCEEDED, assertThrows(MailException.class,
                            () -> service.testConnection(account, "synthetic-secret".toCharArray(), null)).code());
                }
                for (List<String> folders : List.of(List.of(""), List.of(" "), List.of("Bad\nName"), Arrays.asList((String) null))) {
                    transport.folders = folders;
                    assertEquals(MailException.Code.PROTOCOL_UNAVAILABLE, assertThrows(MailException.class,
                            () -> service.listFolders("mail-1", ReadOnlyMailServiceTest.context())).code());
                }
                assertEquals(0, marked.get(), "Invalid server metadata must never reach the result boundary");
            }
        }
        var transport = new ReadOnlyMailServiceTest.FakeTransport(); transport.folders = List.of("x".repeat(256));
        try (var service = service(new AtomicReference<>(List.of(all(false))), transport, new AtomicInteger())) {
            assertEquals(transport.folders, service.listFolders("mail-1", ReadOnlyMailServiceTest.context()));
        }
    }
    @Test void narrowingScopeInvalidatesReferencesAndCursorsEvenForStillAllowedFolder() {
        var accounts = new AtomicReference<>(List.of(all(true)));
        var transport = new ReadOnlyMailServiceTest.FakeTransport();
        try (var service = service(accounts, transport, new AtomicInteger())) {
            var result = service.searchMessages(query("INBOX", null), ReadOnlyMailServiceTest.context());
            assertNotNull(result.nextCursor());
            accounts.set(List.of(account("selected", Set.of("INBOX"), true, 2)));
            assertEquals(MailException.Code.STALE_REFERENCE, assertThrows(MailException.class,
                    () -> service.getMessage(result.messages().getFirst().reference(), ReadOnlyMailServiceTest.context())).code());
            assertEquals(MailException.Code.INVALID_CURSOR, assertThrows(MailException.class,
                    () -> service.searchMessages(query("INBOX", result.nextCursor()), ReadOnlyMailServiceTest.context())).code());
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                    () -> service.searchMessages(query("Projects/项目", null), ReadOnlyMailServiceTest.context())).code());
            assertEquals(1, transport.connections.get());
        }
    }
    @Test void narrowingDuringFolderListSearchAndBodyReadDiscardsResults() throws Exception {
        var accounts = new AtomicReference<>(List.of(all(true)));
        var transport = new ReadOnlyMailServiceTest.FakeTransport(); transport.block = new CountDownLatch(1);
        var marked = new AtomicInteger();
        try (var service = service(accounts, transport, marked)) {
            var pending = CompletableFuture.runAsync(() -> assertEquals(MailException.Code.ACCESS_DENIED,
                    assertThrows(MailException.class, () -> service.listFolders("mail-1", ReadOnlyMailServiceTest.context())).code()));
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS));
            accounts.set(List.of(account("selected", Set.of("INBOX"), true, 2)));
            transport.block.countDown(); pending.get(2, TimeUnit.SECONDS); assertEquals(0, marked.get());
            accounts.set(List.of(all(true)));
            transport.afterSearch = () -> accounts.set(List.of(account("selected", Set.of("INBOX"), true, 2)));
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                    () -> service.searchMessages(query("Projects/项目", null), ReadOnlyMailServiceTest.context())).code());
            assertEquals(0, marked.get());
            accounts.set(List.of(all(true))); transport.afterSearch = () -> { };
            var ref = service.searchMessages(query("Projects/项目", null), ReadOnlyMailServiceTest.context()).messages().getFirst().reference();
            marked.set(0);
            transport.afterRead = () -> accounts.set(List.of(account("selected", Set.of("INBOX"), true, 2)));
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                    () -> service.getMessage(ref, ReadOnlyMailServiceTest.context())).code());
            assertEquals(0, marked.get());
        }
    }
    @Test void resultBoundaryCannotExposeOldAccountScopeAfterRevocation() {
        var accounts = new AtomicReference<>(List.of(all(false)));
        var transport = new ReadOnlyMailServiceTest.FakeTransport();
        var boundary = new ReadOnlyMailService.AuthorizationBoundary() {
            public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) { }
            public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) {
                accounts.set(List.of(account("selected", Set.of("INBOX"), false, 2)));
            }
        };
        try (var service = new ReadOnlyMailService(accounts::get, a -> "synthetic-secret".toCharArray(), boundary,
                transport, MailLimits.defaults(), Clock.systemUTC())) {
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                    () -> service.listAccounts(ReadOnlyMailServiceTest.context())).code());
            accounts.set(List.of(all(false)));
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                    () -> service.listFolders("mail-1", ReadOnlyMailServiceTest.context())).code());
        }
    }
    @Test void narrowingWhileAuthorizationWaitsIsRecheckedEvenWithoutRevisionChange() {
        // The second callback is the final pre-network check; the fourth is the final result check.
        for (int revokeAt : List.of(2, 4)) {
            var accounts = new AtomicReference<>(List.of(all(false)));
            var transport = new ReadOnlyMailServiceTest.FakeTransport();
            var authorizations = new AtomicInteger();
            var boundary = new ReadOnlyMailService.AuthorizationBoundary() {
                public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) {
                    if (authorizations.incrementAndGet() == revokeAt)
                        accounts.set(List.of(account("selected", Set.of("INBOX"), false, 1)));
                }
                public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) {
                    if (revokeAt == 2) fail("No result may reach the provenance boundary");
                }
            };
            try (var service = new ReadOnlyMailService(accounts::get, a -> {
                if (revokeAt == 2) fail("Revocation during consent must stop before credential access");
                return "synthetic-secret".toCharArray();
            }, boundary, transport, MailLimits.defaults(), Clock.systemUTC())) {
                assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                        () -> service.searchMessages(query("Projects/项目", null), ReadOnlyMailServiceTest.context())).code());
                assertEquals(revokeAt, authorizations.get());
                assertEquals(revokeAt == 2 ? 0 : 1, transport.connections.get());
            }
        }
    }
    @Test void allScopeStillRequiresIndependentModelAuthorizationBeforeNetwork() {
        var transport = new ReadOnlyMailServiceTest.FakeTransport();
        var boundary = new ReadOnlyMailService.AuthorizationBoundary() {
            public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) { throw new MailException(MailException.Code.ACCESS_DENIED); }
            public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) { fail(); }
        };
        try (var service = new ReadOnlyMailService(() -> List.of(all(true)), a -> { fail(); return null; }, boundary,
                transport, MailLimits.defaults(), Clock.systemUTC())) {
            assertTrue(service.listAccounts(ReadOnlyMailServiceTest.context()).isEmpty());
            assertEquals(MailException.Code.ACCESS_DENIED, assertThrows(MailException.class,
                    () -> service.searchMessages(query("Projects/项目", null), ReadOnlyMailServiceTest.context())).code());
            assertEquals(0, transport.connections.get());
        }
    }
}
