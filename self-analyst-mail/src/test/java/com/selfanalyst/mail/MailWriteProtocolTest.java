package com.selfanalyst.mail;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.imap.protocol.BASE64MailboxEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.selfanalyst.mail.MailException.Code.*;
import static com.selfanalyst.mail.MailWriteService.Operation.*;
import static com.selfanalyst.mail.MailWriteServiceTest.QQ;
import static org.junit.jupiter.api.Assertions.*;

/** Real Angus IMAP and SMTP messages, using only synthetic loopback sockets and credentials. */
class MailWriteProtocolTest {
    private static final String SUBJECT = "合成测试";
    private static final String BODY = "仅测试\n.dot line\nBcc: this is still body text\n";
    private static final List<String> TO = List.of("one@example.org", "two@example.net");
    private static final String DRAFT_FOLDER = "Personal/草稿箱";
    private static final Pattern LITERAL = Pattern.compile("\\{([1-9][0-9]*)\\}$");

    @Test void imapDraftIsOneAppendToExactNestedSpecialUseFolderAndSends163Id() throws Exception {
        var account = new MailAccount("native-163", MailAccount.Provider.NETEASE_163, "sender@163.com", "Synthetic", true,
                "all", Set.of(), false, 3);
        try (var wire = Fixture.imap(List.of(DRAFT_FOLDER), "accept")) {
            var service = service(wire);
            var result = invoke(service, account, CREATE_DRAFT, () -> true);
            assertEquals(MailWriteService.Status.DRAFT_SAVED, result.status());
            wire.await();
            assertEquals(1, wire.received.size());
            assertMime(wire.received.getFirst(), account);
            String append = wire.commands.stream().filter(c -> c.contains(" APPEND ")).findFirst().orElseThrow();
            assertTrue(append.contains(BASE64MailboxEncoder.encode(DRAFT_FOLDER)), append);
            assertTrue(append.contains("(\\Draft)"), append);
            assertTrue(wire.commands.stream().filter(c -> c.contains(" ID ")).anyMatch(c -> c.contains("SelfAnalyst")), wire.commands.toString());
            assertTrue(index(wire.commands, " ID ") < index(wire.commands, " LIST "));
            assertNoUnrelatedMutation(wire.commands);
        }
    }

    @Test void imapUsesExtendedSpecialUseWhenAdvertisedWithoutGuessingMailbox() throws Exception {
        try (var wire = Fixture.imap(List.of(DRAFT_FOLDER), "extended")) {
            assertEquals(MailWriteService.Status.DRAFT_SAVED, invoke(service(wire), QQ, CREATE_DRAFT, () -> true).status());
            wire.await();
            assertTrue(wire.commands.stream().anyMatch(c -> c.contains(" LIST (SPECIAL-USE) ")), wire.commands.toString());
            assertEquals(1, wire.received.size());
            assertNoUnrelatedMutation(wire.commands);
        }
    }

    @Test void imapRejectsNoOrAmbiguousDraftsAndOutOfScopeWithoutAppend() throws Exception {
        for (List<String> drafts : List.of(List.<String>of(), List.of("DraftA", "DraftB"), List.of("INBOX"))) {
            try (var wire = Fixture.imap(drafts, "accept")) {
                assertEquals(DRAFTS_UNAVAILABLE, assertThrows(MailException.class,
                        () -> invoke(service(wire), QQ, CREATE_DRAFT, () -> true)).code());
                wire.await();
                assertFalse(wire.commands.stream().anyMatch(c -> c.contains(" APPEND ")));
                assertNoUnrelatedMutation(wire.commands);
            }
        }
        var selected = new MailAccount("fixture", MailAccount.Provider.QQ, "sender@qq.com", "Synthetic", true,
                "selected", Set.of("INBOX"), true, 7);
        try (var wire = Fixture.imap(List.of(DRAFT_FOLDER), "accept")) {
            assertEquals(DRAFTS_UNAVAILABLE, assertThrows(MailException.class,
                    () -> invoke(service(wire), selected, CREATE_DRAFT, () -> true)).code());
            wire.await();
            assertTrue(wire.received.isEmpty());
        }
    }

    @Test void imapDroppedAckAndExplicitRejectionReportedDifferently() throws Exception {
        for (String mode : List.of("drop", "reject")) {
            try (var wire = Fixture.imap(List.of(DRAFT_FOLDER), mode)) {
                MailException failure = assertThrows(MailException.class, () -> invoke(service(wire), QQ, CREATE_DRAFT, () -> true));
                assertEquals(mode.equals("drop") ? OUTCOME_UNKNOWN : WRITE_REJECTED, failure.code());
                assertFalse(failure.toString().contains("private-server-echo"));
                wire.await();
                assertEquals(1, wire.received.size());
                assertEquals(1, wire.commands.stream().filter(c -> c.contains(" APPEND ")).count());
                assertNoUnrelatedMutation(wire.commands);
            }
        }
    }

    @Test void smtpEncodesExactPlainTextAndRecipientsOnceDespiteServerAdvertisingChunking() throws Exception {
        try (var wire = Fixture.smtp("accept")) {
            var result = invoke(service(wire), QQ, SEND, () -> true);
            assertEquals(MailWriteService.Status.ACCEPTED_BY_SMTP, result.status());
            wire.await();
            assertEquals(1, wire.received.size());
            assertMime(wire.received.getFirst(), QQ);
            assertEquals(List.of("RCPT TO:<one@example.org>", "RCPT TO:<two@example.net>"),
                    wire.commands.stream().filter(c -> c.startsWith("RCPT TO:")).toList());
            assertEquals(1, wire.commands.stream().filter(c -> c.equals("DATA")).count());
            assertEquals(1, wire.commands.stream().filter(c -> c.startsWith("MAIL FROM:<sender@qq.com>")).count());
            assertTrue(wire.commands.stream().noneMatch(c -> c.startsWith("BDAT")), wire.commands.toString());
        }
    }

    @Test void smtpExplicitRejectionDroppedAckAndInvalidRecipientDoNotRetryOrSendPartially() throws Exception {
        for (String mode : List.of("drop", "reject", "reject-recipient")) {
            try (var wire = Fixture.smtp(mode)) {
                var failure = assertThrows(MailException.class, () -> invoke(service(wire), QQ, SEND, () -> true));
                assertEquals(mode.equals("drop") ? OUTCOME_UNKNOWN : WRITE_REJECTED, failure.code(), mode);
                assertFalse(failure.toString().contains("private-server-echo"));
                wire.await();
                assertEquals(1, wire.commands.stream().filter(c -> c.startsWith("MAIL FROM:")).count());
                assertEquals(mode.equals("reject-recipient") ? 0 : 1, wire.received.size(), mode);
                if (mode.equals("reject-recipient")) assertFalse(wire.commands.contains("DATA"));
            }
        }
    }

    @Test void serverInteractionRevocationPreventsImapAppendAndSmtpFinalDataTerminator() throws Exception {
        for (boolean imap : List.of(true, false)) {
            try (var wire = imap ? Fixture.imap(List.of(DRAFT_FOLDER), "accept") : Fixture.smtp("accept")) {
                var allowed = new AtomicBoolean(true);
                wire.revokeInsideProtocol = () -> allowed.set(false);
                var op = imap ? CREATE_DRAFT : SEND;
                assertEquals(ACCESS_DENIED, assertThrows(MailException.class,
                        () -> invoke(service(wire), QQ, op, allowed::get)).code());
                wire.await();
                assertTrue(wire.received.isEmpty());
                assertFalse(wire.commands.stream().anyMatch(c -> c.contains(" APPEND ") || c.equals("END-OF-DATA")));
            }
        }
    }

    @Test void implicitTlsIdentityVerificationNoFallbackAndFixedSmtpProviders() {
        for (String prefix : List.of("imaps", "smtps")) {
            var properties = AngusMailWriteTransport.properties(prefix, true, Duration.ofSeconds(10), SocketFactory.getDefault());
            String p = "mail." + prefix + ".";
            assertEquals("true", properties.getProperty(p + "ssl.enable"));
            assertEquals("true", properties.getProperty(p + "ssl.checkserveridentity"));
            assertEquals("false", properties.getProperty(p + "socketFactory.fallback"));
            assertEquals("false", properties.getProperty(p + "starttls.enable"));
            assertEquals("TLSv1.3 TLSv1.2", properties.getProperty(p + "ssl.protocols"));
            assertNull(properties.getProperty(p + "ssl.trust"));
            assertEquals("false", properties.getProperty("mail.debug"));
        }
        assertEquals("smtp.qq.com", MailWriteService.smtpHost(MailAccount.Provider.QQ));
        assertEquals("smtp.163.com", MailWriteService.smtpHost(MailAccount.Provider.NETEASE_163));
        assertEquals(465, MailWriteService.SMTP_PORT);
    }

    @Test void smtpRejectsUntrustedTlsBeforeAnyCredentialOrMessageBytes(@TempDir Path temporary) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .startsWith("windows") ? "keytool.exe" : "keytool");
        Path keysPath = temporary.resolve("synthetic-mail.p12");
        String password = "synthetic-only";
        Process keygen = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keysPath.toString(), "-storepass", password, "-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("keytool.log").toFile()).start();
        assertTrue(keygen.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, keygen.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keysPath)) { keys.load(input, password.toCharArray()); }
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(keys, password.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(manager.getKeyManagers(), null, null);
        try (SSLServerSocket server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 8,
                InetAddress.getByName("127.0.0.1"))) {
            var received = new CompletableFuture<Boolean>();
            Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(4000);
                    socket.startHandshake();
                    received.complete(true); // Completing TLS itself would already be a trust violation.
                } catch (IOException denied) { received.complete(false); }
            });
            var service = new MailWriteService(new AngusMailWriteTransport(
                    message -> (InetSocketAddress) server.getLocalSocketAddress(), true), Duration.ofSeconds(5));
            assertEquals(TLS_FAILURE, assertThrows(MailException.class, () -> invoke(service, QQ, SEND, () -> true)).code());
            assertFalse(received.get(5, TimeUnit.SECONDS));
            server.setSoTimeout(150);
            assertThrows(java.net.SocketTimeoutException.class, server::accept, "No second/fallback TCP connection");
        }
    }

    private static MailWriteService service(Fixture wire) {
        return new MailWriteService(new AngusMailWriteTransport(message -> wire.address(), false), Duration.ofSeconds(6));
    }
    private static MailWriteService.Result invoke(MailWriteService service, MailAccount account, MailWriteService.Operation operation,
                                                  java.util.function.BooleanSupplier authorized) {
        return service.execute(service.prepare(account, operation, TO, SUBJECT, BODY), "synthetic-only-secret".toCharArray(),
                authorized, new MailCancellation());
    }
    private static int index(List<String> commands, String token) {
        for (int i = 0; i < commands.size(); i++) if (commands.get(i).contains(token)) return i;
        return Integer.MAX_VALUE;
    }
    private static void assertMime(byte[] bytes, MailAccount account) throws Exception {
        var mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
        assertEquals(account.email(), ((InternetAddress) mime.getFrom()[0]).getAddress());
        assertEquals(SUBJECT, mime.getSubject());
        assertEquals(TO, Arrays.stream(mime.getRecipients(Message.RecipientType.TO)).map(InternetAddress.class::cast)
                .map(InternetAddress::getAddress).toList());
        assertNull(mime.getRecipients(Message.RecipientType.BCC));
        assertNull(mime.getRecipients(Message.RecipientType.CC));
        assertTrue(mime.isMimeType("text/plain"));
        assertEquals(BODY.stripTrailing(), ((String) mime.getContent()).replace("\r\n", "\n").stripTrailing());
        assertFalse(new String(bytes, StandardCharsets.US_ASCII).contains("synthetic-only-secret"));
    }
    private static void assertNoUnrelatedMutation(List<String> commands) {
        assertFalse(commands.stream().anyMatch(c -> c.matches("(?i).* (SELECT|EXAMINE|STORE|COPY|MOVE|EXPUNGE|CLOSE|CREATE|FETCH|DELETE)(?: .*|$)")),
                commands.toString());
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<byte[]> received = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final ServerSocket server;
        private final boolean imap;
        private final List<String> drafts;
        private final String mode;
        private volatile Socket active;
        volatile Runnable revokeInsideProtocol = () -> { };

        static Fixture smtp(String mode) throws IOException { return new Fixture(false, List.of(), mode); }
        static Fixture imap(List<String> drafts, String mode) throws IOException { return new Fixture(true, drafts, mode); }
        Fixture(boolean imap, List<String> drafts, String mode) throws IOException {
            this.imap = imap;
            this.drafts = drafts;
            this.mode = mode;
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    active = socket;
                    socket.setSoTimeout(5000);
                    InputStream input = new BufferedInputStream(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    if (this.imap) imap(input, output); else smtp(input, output);
                    done.complete(null);
                } catch (Throwable failure) { done.completeExceptionally(failure); }
            });
        }
        InetSocketAddress address() { return (InetSocketAddress) server.getLocalSocketAddress(); }
        void await() throws Exception { done.get(6, TimeUnit.SECONDS); }
        @Override public void close() throws IOException {
            server.close();
            if (active != null) active.close();
        }

        private void imap(InputStream in, OutputStream out) throws IOException {
            send(out, "* OK Synthetic IMAP ready\r\n");
            String command;
            while ((command = line(in)) != null) {
                commands.add(command);
                String tag = command.split(" ", 2)[0];
                String upper = command.toUpperCase(Locale.ROOT);
                if (upper.contains(" CAPABILITY")) send(out, "* CAPABILITY IMAP4rev1 ID"
                        + (mode.equals("extended") ? " SPECIAL-USE" : "") + "\r\n" + tag + " OK capability\r\n");
                else if (upper.contains(" LOGIN ")) send(out, tag + " OK login\r\n");
                else if (upper.contains(" ID ")) send(out, "* ID NIL\r\n" + tag + " OK id\r\n");
                else if (upper.contains(" LIST ")) {
                    revokeInsideProtocol.run();
                    send(out, "* LIST () \"/\" \"INBOX\"\r\n");
                    if (!mode.equals("extended") || upper.contains("(SPECIAL-USE)")) for (String draft : drafts)
                        send(out, "* LIST (\\Drafts) \"/\" \"" + BASE64MailboxEncoder.encode(draft) + "\"\r\n");
                    send(out, tag + " OK list\r\n");
                } else if (upper.contains(" APPEND ")) {
                    var size = LITERAL.matcher(command);
                    assertTrue(size.find(), command);
                    int expected = Integer.parseInt(size.group(1));
                    send(out, "+ continue\r\n");
                    byte[] payload = in.readNBytes(expected);
                    assertEquals(expected, payload.length);
                    assertEquals("", line(in));
                    received.add(payload);
                    if (mode.equals("drop")) return;
                    send(out, tag + (mode.equals("reject") ? " NO private-server-echo\r\n" : " OK appended\r\n"));
                } else if (upper.contains(" LOGOUT")) {
                    send(out, "* BYE\r\n" + tag + " OK logout\r\n"); return;
                } else throw new AssertionError("Unexpected synthetic IMAP command: " + command);
            }
        }
        private void smtp(InputStream in, OutputStream out) throws IOException {
            send(out, "220 synthetic.local ESMTP\r\n");
            String command;
            while ((command = line(in)) != null) {
                commands.add(command.startsWith("AUTH") ? "AUTH synthetic" : command);
                if (command.startsWith("EHLO")) send(out, "250-synthetic.local\r\n250-AUTH PLAIN\r\n250-8BITMIME\r\n250 CHUNKING\r\n");
                else if (command.startsWith("AUTH PLAIN")) send(out, "235 2.7.0 accepted\r\n");
                else if (command.startsWith("MAIL FROM:") || command.equals("RSET")) send(out, "250 good\r\n");
                else if (command.startsWith("RCPT TO:")) send(out, mode.equals("reject-recipient") && command.contains("two@example")
                        ? "550 private-server-echo\r\n" : "250 accepted recipient\r\n");
                else if (command.equals("DATA")) {
                    revokeInsideProtocol.run();
                    send(out, "354 data follows\r\n");
                    var body = new ByteArrayOutputStream();
                    String data;
                    while ((data = line(in)) != null && !data.equals(".")) {
                        if (data.startsWith("..")) data = data.substring(1);
                        body.write((data + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }
                    if (data == null) return;
                    commands.add("END-OF-DATA");
                    received.add(body.toByteArray());
                    if (mode.equals("drop")) return;
                    send(out, mode.equals("reject") ? "554 private-server-echo\r\n" : "250 queued\r\n");
                } else if (command.equals("QUIT")) {
                    send(out, "221 goodbye\r\n"); return;
                } else throw new AssertionError("Unexpected synthetic SMTP command: " + command);
            }
        }
        private static String line(InputStream in) throws IOException {
            var result = new ByteArrayOutputStream();
            int ch;
            while ((ch = in.read()) >= 0 && ch != '\n') {
                if (ch != '\r') result.write(ch);
                if (result.size() > 70_000) throw new IOException("synthetic line over test budget");
            }
            return ch < 0 && result.size() == 0 ? null : result.toString(StandardCharsets.ISO_8859_1);
        }
        private static void send(OutputStream out, String reply) throws IOException {
            out.write(reply.getBytes(StandardCharsets.US_ASCII)); out.flush();
        }
    }
}
