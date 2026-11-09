package com.selfanalyst.mail;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic localhost certificate only; does not change the JVM/global or production trust store. */
class MailTlsTest {
    @TempDir static Path temporary;
    private static SSLContext trustedFixtureContext;
    @BeforeAll static void certificate() throws Exception {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        Path keystore = temporary.resolve("synthetic-mail.p12");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "synthetic-mail",
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", "synthetic-only", "-keypass", "synthetic-only", "-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Synthetic keytool timed out");
        assertEquals(0, process.exitValue(), () -> { try { return Files.readString(temporary.resolve("keytool.log")); } catch (IOException e) { return "keytool failed"; } });
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) { keys.load(input, "synthetic-only".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "synthetic-only".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(keys);
        trustedFixtureContext = SSLContext.getInstance("TLS"); trustedFixtureContext.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
    }
    @Test void rejectsUntrustedCertificateWithNoPlaintextFallback() throws Exception {
        try (TlsServer server = new TlsServer(false); MailSocketFactory factory = new MailSocketFactory(new MailCancellation(), 5000);
             SSLSocket socket = (SSLSocket) factory.createSocket("localhost", server.port())) {
            assertThrows(SSLHandshakeException.class, socket::startHandshake);
        }
    }
    @Test void rejectsHostnameMismatchEvenWhenCertificateIsTrusted() throws Exception {
        try (TlsServer server = new TlsServer(false);
             MailSocketFactory factory = new MailSocketFactory(new MailCancellation(), 5000, trustedFixtureContext.getSocketFactory());
             SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", server.port())) {
            assertEquals("HTTPS", socket.getSSLParameters().getEndpointIdentificationAlgorithm());
            assertThrows(SSLHandshakeException.class, socket::startHandshake);
        }
    }
    @Test void countsEncodedProtocolBytesAndClosesOverBudget() throws Exception {
        try (TlsServer server = new TlsServer(true);
             MailSocketFactory factory = new MailSocketFactory(new MailCancellation(), 1024, trustedFixtureContext.getSocketFactory());
             SSLSocket socket = (SSLSocket) factory.createSocket("localhost", server.port())) {
            socket.startHandshake();
            IOException failure = assertThrows(IOException.class, () -> socket.getInputStream().readAllBytes());
            assertEquals("mail_wire_budget_exceeded", failure.getMessage());
            assertTrue(socket.isClosed());
        }
    }
    @Test void cancellationClosesBlockedTlsRead() throws Exception {
        MailCancellation cancellation = new MailCancellation();
        try (TlsServer server = new TlsServer(false);
             MailSocketFactory factory = new MailSocketFactory(cancellation, 5000, trustedFixtureContext.getSocketFactory());
             SSLSocket socket = (SSLSocket) factory.createSocket("localhost", server.port())) {
            socket.startHandshake();
            CompletableFuture<Void> pending = CompletableFuture.runAsync(() -> {
                try { assertEquals(-1, socket.getInputStream().read()); } catch (IOException expectedAfterClose) { }
            });
            assertTrue(server.handshake.await(2, TimeUnit.SECONDS));
            cancellation.cancel(); pending.get(2, TimeUnit.SECONDS);
            assertTrue(socket.isClosed());
        }
    }
    private static final class TlsServer implements AutoCloseable {
        private final SSLServerSocket server;
        private final List<SSLSocket> sockets = new CopyOnWriteArrayList<>();
        final CountDownLatch handshake = new CountDownLatch(1);
        TlsServer(boolean payload) throws IOException {
            server = (SSLServerSocket) trustedFixtureContext.getServerSocketFactory().createServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
            Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    sockets.add(socket); socket.startHandshake(); handshake.countDown();
                    if (payload) { socket.getOutputStream().write(new byte[4096]); socket.getOutputStream().flush(); }
                    else socket.getInputStream().read();
                } catch (IOException ignored) { }
            });
        }
        int port() { return server.getLocalPort(); }
        public void close() throws IOException { server.close(); for (SSLSocket socket : sockets) socket.close(); }
    }
}
