package com.selfanalyst.mail;

import jakarta.mail.Session;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.BASE64MailboxEncoder;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Angus network commands against a synthetic server, never a real provider/account. */
class MailProtocolTest {
    @Test void connectionTestSendsReal163IdBeforeExamineAndDoesNotFetch() throws Exception {
        try (Fixture server = new Fixture()) {
            var account = new MailAccount("synthetic", MailAccount.Provider.NETEASE_163, "synthetic@163.com", "Synthetic", false, Set.of("INBOX"), false, 1);
            server.requireId = true;
            try (var service = service(server, account)) {
                assertTrue(service.testConnection(account, "synthetic-secret".toCharArray(), new MailCancellation()).connected());
            }
            List<String> commands = server.commands();
            assertTrue(commands.stream().anyMatch(c -> c.contains("ID (")), commands.toString());
            assertTrue(commands.stream().anyMatch(c -> c.contains("SelfAnalyst")), commands.toString());
            assertTrue(index(commands, " ID ") < index(commands, " EXAMINE "), commands.toString());
            assertTrue(commands.stream().anyMatch(c -> c.contains(" LIST ")), commands.toString());
            assertTrue(commands.stream().noneMatch(c -> c.toUpperCase(Locale.ROOT).contains("FETCH")), commands.toString());
            assertReadOnly(commands);
        }
    }
    @Test void desktopDiscoveryListsFullHierarchyBeforeEnablementWithoutOpeningOrFetching() throws Exception {
        var account = new MailAccount("mail-1", MailAccount.Provider.NETEASE_163, "synthetic@163.com", "Synthetic", false,
                "selected", Set.of("INBOX"), false, 1);
        try (Fixture server = new Fixture(); var service = service(server, account)) {
            server.requireId = true;
            server.folders = List.of("INBOX", "项目/客户A", "Archive/2026");
            server.noSelectFolders = List.of("项目", "Archive");
            var folders = service.discoverFolders(account, "synthetic-secret".toCharArray(), new MailCancellation());
            assertEquals(server.folders, folders.stream().map(MailTransport.FolderInfo::name).toList());
            assertTrue(folders.stream().allMatch(f -> f.selectable() && f.separator().equals("/")));
            var commands = server.commands();
            assertTrue(commands.stream().anyMatch(c -> c.contains(" ID ")));
            assertTrue(commands.stream().anyMatch(c -> c.contains(" LIST ")));
            assertTrue(commands.stream().noneMatch(c -> c.contains(" EXAMINE ") || c.contains(" SELECT ")
                    || c.contains("FETCH") || c.contains("SEARCH")), commands.toString());
            assertReadOnly(commands);
        }
    }
    @Test void searchAndReadUsePeekAndNeverFetchAttachmentOrChangeUnread() throws Exception {
        try (Fixture server = new Fixture(); var service = service(server, ReadOnlyMailServiceTest.account())) {
            var request = new ReadOnlyMailService.SearchRequest("mail-1", "INBOX", null, null, null, "subject", true, 20, null);
            var search = service.searchMessages(request, ReadOnlyMailServiceTest.context());
            assertEquals(2, search.messages().size());
            assertTrue(search.messages().stream().allMatch(ReadOnlyMailService.MessageSummary::unread));
            assertEquals("unknown", search.coverage().providerVisibleRange());
            assertTrue(server.commands().stream().noneMatch(c -> c.contains("BODY.PEEK[1]")), server.commands().toString());
            var message = service.getMessage(search.messages().getFirst().reference(), ReadOnlyMailServiceTest.context());
            assertEquals("Hello mail!", message.body().text());
            assertEquals(1, message.body().attachments().size());
            assertEquals("large.bin", message.body().attachments().getFirst().filename());
            assertFalse(message.body().truncated());
            assertTrue(message.message().unread());
            assertTrue(server.commands().stream().anyMatch(c -> c.contains("BODY.PEEK[1]")), server.commands().toString());
            assertTrue(server.commands().stream().noneMatch(c -> c.contains("BODY.PEEK[2]")), server.commands().toString());
            assertReadOnly(server.commands());
            assertFalse(server.seen);
        }
    }
    @Test void allScopeListsAndReadsNestedUnicodeAndNewFoldersUsingReadOnlyCommands() throws Exception {
        var account = new MailAccount("mail-1", MailAccount.Provider.QQ, "synthetic@qq.com", "Synthetic", true,
                "all", Set.of("INBOX"), true, 1);
        try (Fixture server = new Fixture(); var service = service(server, account)) {
            server.folders = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "Projects/项目/" + i).toList();
            server.noSelectFolders = List.of("Projects");
            assertEquals(server.folders, service.listFolders("mail-1", ReadOnlyMailServiceTest.context()));
            var expanded = new ArrayList<>(server.folders); expanded.add("归档/新增/收件");
            server.folders = List.copyOf(expanded);
            assertEquals(expanded, service.listFolders("mail-1", ReadOnlyMailServiceTest.context()));
            for (String folder : List.of("Projects/项目/0", "归档/新增/收件")) {
                var request = new ReadOnlyMailService.SearchRequest("mail-1", folder, null, null, null, null, null, 20, null);
                var result = service.searchMessages(request, ReadOnlyMailServiceTest.context());
                assertEquals(2, result.messages().size());
                var message = service.getMessage(result.messages().getFirst().reference(), ReadOnlyMailServiceTest.context());
                assertEquals("Hello mail!", message.body().text()); assertTrue(message.message().unread());
                assertEquals(1, message.body().attachments().size());
                assertTrue(server.commands().stream().anyMatch(c -> c.contains(" EXAMINE ")
                        && c.contains(BASE64MailboxEncoder.encode(folder))), server.commands().toString());
            }
            assertTrue(server.commands().stream().anyMatch(c -> c.contains(" LIST ") && c.contains("*")));
            assertTrue(server.commands().stream().noneMatch(c -> c.contains("BODY.PEEK[2]")));
            assertFalse(server.seen); assertReadOnly(server.commands());
        }
    }
    @Test void actualListResponseHonorsDirectoryAndNameBudgetsWithoutTruncation() throws Exception {
        var account = new MailAccount("mail-1", MailAccount.Provider.QQ, "synthetic@qq.com", "Synthetic", true,
                "all", Set.of(), false, 1);
        try (Fixture server = new Fixture(); var service = service(server, account)) {
            server.folders = java.util.stream.IntStream.range(0, 256).mapToObj(i -> "Projects/" + i).toList();
            assertEquals(256, service.listFolders("mail-1", ReadOnlyMailServiceTest.context()).size());
            // A no-select directory still consumes the protocol directory budget.
            server.noSelectFolders = List.of("Projects");
            assertEquals(MailException.Code.LIMIT_EXCEEDED, assertThrows(MailException.class,
                    () -> service.listFolders("mail-1", ReadOnlyMailServiceTest.context())).code());
            server.noSelectFolders = List.of(); server.folders = List.of("x".repeat(257));
            assertEquals(MailException.Code.LIMIT_EXCEEDED, assertThrows(MailException.class,
                    () -> service.listFolders("mail-1", ReadOnlyMailServiceTest.context())).code());
            assertReadOnly(server.commands());
        }
    }
    @Test void missingIdUnsafeLoginAndBadAuthorizationAreClassifiedAndNotRetried() throws Exception {
        try (Fixture server = new Fixture()) {
            server.hasId = false;
            var account = new MailAccount("synthetic", MailAccount.Provider.NETEASE_163, "synthetic@163.com", null, false, null, false, 1);
            try (var service = service(server, account)) {
                assertEquals(MailException.Code.PROVIDER_RESTRICTED, assertThrows(MailException.class,
                        () -> service.testConnection(account, "synthetic-secret".toCharArray(), null)).code());
            }
            assertEquals(0, server.commands().stream().filter(c -> c.contains(" EXAMINE ")).count());
        }
        try (Fixture server = new Fixture()) {
            server.unsafe = true;
            var account = ReadOnlyMailServiceTest.account();
            try (var service = service(server, account)) {
                assertEquals(MailException.Code.PROVIDER_RESTRICTED, assertThrows(MailException.class,
                        () -> service.testConnection(account, "synthetic-secret".toCharArray(), null)).code());
            }
            assertReadOnly(server.commands());
        }
        try (Fixture server = new Fixture()) {
            server.badAuth = true;
            try (var service = service(server, ReadOnlyMailServiceTest.account())) {
                var exception = assertThrows(MailException.class, () -> service.testConnection(ReadOnlyMailServiceTest.account(), "synthetic-secret".toCharArray(), null));
                assertEquals(MailException.Code.AUTHENTICATION_FAILED, exception.code());
                assertFalse(exception.toString().contains("synthetic-secret"));
            }
            assertEquals(1, server.commands().stream().filter(c -> c.contains(" LOGIN ")).count());
            assertReadOnly(server.commands());
        }
    }
    @Test void unsupportedChineseSearchDoesNotFallBackToLocalBodyScan() throws Exception {
        try (Fixture server = new Fixture(); var service = service(server, ReadOnlyMailServiceTest.account())) {
            server.badCharset = true;
            var request = new ReadOnlyMailService.SearchRequest("mail-1", "INBOX", null, null, null, "中文主题", null, 20, null);
            assertEquals(MailException.Code.SEARCH_UNSUPPORTED, assertThrows(MailException.class,
                    () -> service.searchMessages(request, ReadOnlyMailServiceTest.context())).code());
            assertTrue(server.commands().stream().noneMatch(c -> c.contains("BODY.PEEK")), server.commands().toString());
            assertReadOnly(server.commands());
        }
    }
    @Test void cancellationAndFailureCleanupNeverWrite() throws Exception {
        try (Fixture server = new Fixture(); var service = service(server, ReadOnlyMailServiceTest.account())) {
            server.stall = true;
            var context = ReadOnlyMailServiceTest.context();
            var pending = java.util.concurrent.CompletableFuture.runAsync(() -> assertEquals(MailException.Code.CANCELLED,
                    assertThrows(MailException.class, () -> service.listFolders("mail-1", context)).code()));
            assertTrue(server.stalled.await(3, TimeUnit.SECONDS));
            context.cancellation().cancel(); pending.get(3, TimeUnit.SECONDS);
            assertReadOnly(server.commands());
        }
    }
    @Test void writableResponseClaimCannotEnableWritesOrCloseCommand() throws Exception {
        try (Fixture server = new Fixture(); var service = service(server, ReadOnlyMailServiceTest.account())) {
            server.readWrite = true;
            // Angus correctly treats EXAMINE as READ_ONLY regardless of an inconsistent response label.
            assertEquals(1, service.searchMessages(ReadOnlyMailServiceTest.query(null), ReadOnlyMailServiceTest.context()).messages().size());
            assertReadOnly(server.commands());
        }
    }
    @Test void giantMailboxCountIsRejectedBeforeAngusAllocatesSequenceCache() throws Exception {
        try (Fixture server = new Fixture(); var service = service(server, ReadOnlyMailServiceTest.account())) {
            server.messageCount = Integer.MAX_VALUE;
            assertEquals(MailException.Code.LIMIT_EXCEEDED, assertThrows(MailException.class,
                    () -> service.searchMessages(ReadOnlyMailServiceTest.query(null), ReadOnlyMailServiceTest.context())).code());
            assertReadOnly(server.commands());
        }
    }
    @Test void tlsIdentityVerificationAndFailuresNeverDowngrade() {
        var cancellation = new MailCancellation();
        try (var sockets = new MailSocketFactory(cancellation, 2000)) {
            var properties = AngusMailTransport.properties(MailLimits.defaults(), sockets);
            assertEquals("true", properties.getProperty("mail.imaps.ssl.enable"));
            assertEquals("true", properties.getProperty("mail.imaps.ssl.checkserveridentity"));
            assertEquals("false", properties.getProperty("mail.imaps.ssl.socketFactory.fallback"));
            assertNull(properties.getProperty("mail.imaps.ssl.trust"));
            assertEquals("true", properties.getProperty("mail.imaps.peek"));
            assertEquals("true", properties.getProperty("mail.imaps.throwsearchexception"));
        }
        assertEquals(MailException.Code.TLS_FAILURE, AngusMailTransport.classify(new SSLHandshakeException("Synthetic certificate mismatch")).code());
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        var transport = new AngusMailTransport((a, s, c, l, sockets) -> {
            attempts.incrementAndGet(); throw new jakarta.mail.MessagingException("TLS", new SSLHandshakeException("Synthetic certificate mismatch"));
        });
        assertEquals(MailException.Code.TLS_FAILURE, assertThrows(MailException.class,
                () -> transport.connect(ReadOnlyMailServiceTest.account(), "synthetic".toCharArray(), cancellation, MailLimits.defaults())).code());
        assertEquals(1, attempts.get());
    }
    private static int index(List<String> commands, String command) {
        for (int i = 0; i < commands.size(); i++) if (commands.get(i).contains(command)) return i;
        return Integer.MAX_VALUE;
    }
    private static void assertReadOnly(List<String> commands) {
        for (String command : commands) {
            String value = command.toUpperCase(Locale.ROOT);
            assertFalse(value.matches(".*\\b(STORE|APPEND|COPY|MOVE|EXPUNGE|SELECT|SMTP|CLOSE)\\b.*"), command);
            assertFalse(value.contains("BODY[") && !value.contains("BODY.PEEK["), command);
        }
    }
    private static ReadOnlyMailService service(Fixture server, MailAccount account) {
        var transport = new AngusMailTransport((a, secret, cancellation, limits, sockets) -> {
            Properties p = new Properties();
            p.setProperty("mail.imap.peek", "true"); p.setProperty("mail.imap.throwsearchexception", "true");
            p.setProperty("mail.imap.timeout", "1500"); p.setProperty("mail.imap.connectiontimeout", "1500");
            p.setProperty("mail.imap.partialfetch", "true"); p.setProperty("mail.imap.fetchsize", "8192");
            // Production always uses TLS at the fixed provider. The package-private test seam alone permits this fixture.
            IMAPStore store = AngusMailTransport.createStore(Session.getInstance(p), "imap", false, a.provider() == MailAccount.Provider.NETEASE_163);
            store.connect("127.0.0.1", server.port(), a.email(), new String(secret));
            return store;
        });
        return new ReadOnlyMailService(() -> List.of(account), a -> "synthetic-secret".toCharArray(),
                new ReadOnlyMailService.AuthorizationBoundary() {
                    public void authorize(ReadOnlyMailService.CallContext c, MailAccount a, String f, boolean b) { }
                    public void beforeResult(ReadOnlyMailService.CallContext c, MailAccount a) { }
                }, transport, MailLimits.defaults(), Clock.systemUTC());
    }
    static final class Fixture implements AutoCloseable {
        private final ServerSocket server;
        private final List<String> log = new CopyOnWriteArrayList<>();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        volatile boolean hasId = true;
        volatile boolean requireId;
        volatile int messageCount = 2;
        volatile boolean unsafe;
        volatile boolean readWrite;
        volatile boolean badAuth;
        volatile boolean badCharset;
        volatile boolean stall;
        volatile boolean seen;
        volatile List<String> folders = List.of("INBOX", "Other");
        volatile List<String> noSelectFolders = List.of();
        final CountDownLatch stalled = new CountDownLatch(1);
        Fixture() throws IOException {
            server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) try {
                    Socket socket = server.accept(); sockets.add(socket); Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException ignored) { }
            });
        }
        int port() { return server.getLocalPort(); }
        List<String> commands() { return List.copyOf(log); }
        private void serve(Socket socket) {
            try (socket; BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                OutputStream output = socket.getOutputStream();
                send(output, "* OK Synthetic IMAP ready\r\n");
                String line;
                boolean identified = false;
                while ((line = input.readLine()) != null) {
                    log.add(line);
                    String tag = line.substring(0, line.indexOf(' '));
                    String command = line.substring(line.indexOf(' ') + 1);
                    String upper = command.toUpperCase(Locale.ROOT);
                    if (upper.startsWith("CAPABILITY")) send(output, "* CAPABILITY IMAP4rev1" + (hasId ? " ID" : "") + "\r\n" + tag + " OK capability\r\n");
                    else if (upper.startsWith("LOGIN")) send(output, tag + (badAuth ? " NO [AUTHENTICATIONFAILED] synthetic-secret invalid\r\n" : " OK login\r\n"));
                    else if (upper.startsWith("ID ")) { identified = true; send(output, "* ID (\"name\" \"Synthetic IMAP\")\r\n" + tag + " OK id\r\n"); }
                    else if (upper.startsWith("LIST")) {
                        if (stall) { stalled.countDown(); while (input.read() != -1) { } return; }
                        for (String folder : noSelectFolders) send(output, "* LIST (\\Noselect \\HasChildren) \"/\" \""
                                + BASE64MailboxEncoder.encode(folder) + "\"\r\n");
                        for (String folder : folders) send(output, "* LIST (\\HasNoChildren) \"/\" \""
                                + BASE64MailboxEncoder.encode(folder) + "\"\r\n");
                        send(output, tag + " OK list\r\n");
                    } else if (upper.startsWith("EXAMINE")) {
                        if (unsafe || (requireId && !identified)) send(output, tag + " NO Unsafe Login\r\n");
                        else send(output, "* FLAGS (\\Seen \\Deleted)\r\n* " + messageCount + " EXISTS\r\n* 0 RECENT\r\n* OK [UIDVALIDITY 7]\r\n* OK [UIDNEXT 3]\r\n" + tag + " OK [" + (readWrite ? "READ-WRITE" : "READ-ONLY") + "] examine\r\n");
                    } else if (upper.startsWith("UID FETCH")) {
                        String range = upper.split(" ")[2];
                        List<Integer> uids = range.contains(":") ? List.of(1, 2) : List.of(Integer.parseInt(range));
                        for (int uid : uids) if (uid <= 2) send(output, "* " + uid + " FETCH (UID " + uid + ")\r\n");
                        send(output, tag + " OK fetch\r\n");
                    } else if (upper.startsWith("SEARCH")) {
                        if (badCharset) {
                            // Angus may try a second supported charset. Both fail, never local scanning.
                            send(output, tag + " NO [BADCHARSET (US-ASCII)] unsupported\r\n");
                        } else send(output, "* SEARCH 1 2\r\n" + tag + " OK search\r\n");
                    } else if (upper.startsWith("FETCH")) {
                        String range = upper.split(" ")[1];
                        List<Integer> uids = range.contains(":") || range.contains(",") ? List.of(1, 2) : List.of(Integer.parseInt(range));
                        for (int uid : uids) {
                            if (upper.contains("BODY.PEEK[1]")) send(output, "* " + uid + " FETCH (BODY[1]<0> {11}\r\nHello mail!)\r\n");
                            else if (upper.contains("BODYSTRUCTURE")) send(output, "* " + uid + " FETCH (BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"7BIT\" 11 1 NIL NIL NIL NIL)(\"APPLICATION\" \"OCTET-STREAM\" (\"NAME\" \"large.bin\") NIL NIL \"BASE64\" 9999999 NIL (\"ATTACHMENT\" (\"FILENAME\" \"large.bin\")) NIL NIL) \"MIXED\" (\"BOUNDARY\" \"synthetic\") NIL NIL NIL))\r\n");
                            else send(output, "* " + uid + " FETCH (UID " + uid + " FLAGS () INTERNALDATE \"09-Nov-2026 00:00:00 +0000\" RFC822.SIZE 10000000 ENVELOPE (\"Mon, 09 Nov 2026 00:00:00 +0000\" \"subject\" ((\"Sender\" NIL \"sender\" \"example.invalid\")) ((\"Sender\" NIL \"sender\" \"example.invalid\")) ((\"Sender\" NIL \"sender\" \"example.invalid\")) ((NIL NIL \"synthetic\" \"qq.com\")) NIL NIL NIL \"<synthetic-" + uid + "@example.invalid>\"))\r\n");
                        }
                        send(output, tag + " OK fetch\r\n");
                    } else if (upper.startsWith("LOGOUT")) { send(output, "* BYE logout\r\n" + tag + " OK logout\r\n"); return; }
                    else if (upper.startsWith("NOOP")) send(output, tag + " OK noop\r\n");
                    else if (upper.contains("STORE")) { seen = true; send(output, tag + " BAD write prohibited\r\n"); }
                    else send(output, tag + " BAD unsupported\r\n");
                }
            } catch (Exception ignored) { }
        }
        private static void send(OutputStream output, String value) throws IOException { output.write(value.getBytes(StandardCharsets.UTF_8)); output.flush(); }
        @Override public void close() throws IOException { server.close(); for (Socket socket : sockets) socket.close(); }
    }
}
