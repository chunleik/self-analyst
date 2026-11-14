package com.selfanalyst.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityTlsTest {
    @TempDir Path temporary;

    @Test void scopedCaTrustsValidHostButNeverWrongHostnameOrUntrustedCertificate() throws Exception {
        Path storeFile = temporary.resolve("synthetic.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "synthetic", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", storeFile.toString(), "-storepass", "synthetic-test-only",
                "-keypass", "synthetic-test-only", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-ext", "BC=ca:true", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("keytool.log").toFile()).start();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, keytool.exitValue(), "synthetic certificate generation");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storeFile)) { keys.load(input, "synthetic-test-only".toCharArray()); }
        String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keys.getCertificate("synthetic").getEncoded()) + "\n-----END CERTIFICATE-----\n";
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(keys, "synthetic-test-only".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(manager.getKeyManagers(), null, null);
        exchange(context, "localhost", pem, true);
        exchange(context, "wrong.example", pem, false);
        exchange(context, "localhost", "", false);
    }

    private void exchange(SSLContext context, String host, String ca, boolean succeeds) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        var policy = new ObservabilityEndpointPolicy(ignored -> new InetAddress[]{loopback});
        AtomicBoolean receivedHttp = new AtomicBoolean();
        try (var server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1, loopback);
             var transport = new ObservabilityTransport(policy)) {
            server.setSoTimeout(5000);
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    if (request != null) receivedHttp.set(true);
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException expectedHandshakeFailure) { }
            });
            var settings = new ObservabilityConfig(1, 0, "https://" + host + ":" + server.getLocalPort(),
                    "pk-synthetic", 1, "metadata", Set.of(), Set.of(), Set.of(), "all-runtime", "", ca);
            var approved = policy.approve(settings);
            if (succeeds) assertEquals(200, transport.send(approved, "Basic synthetic", "{}".getBytes(StandardCharsets.US_ASCII)));
            else assertEquals("tls_failure", assertThrows(IOException.class,
                    () -> transport.send(approved, "Basic synthetic", "{}".getBytes(StandardCharsets.US_ASCII))).getMessage());
            responder.get(6, TimeUnit.SECONDS);
            assertEquals(succeeds, receivedHttp.get(), "HTTP credentials must follow a successful verified TLS handshake");
        } finally { policy.close(); }
    }
}
