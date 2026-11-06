package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static com.selfanalyst.chatgpt.ChatGptOAuthHttp.*;

class ChatGptIdTokenVerifierTest {
    private HttpServer server;
    private KeyPair keys;
    private ChatGptIdTokenVerifier verifier;
    private String issuer;
    private volatile String jwksOverride;
    private ObjectNode claims;

    @BeforeEach void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/discovery", x -> {
            byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("issuer", issuer)
                    .put("jwks_uri", jwksOverride == null ? issuer + "/jwks" : jwksOverride));
            x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close();
        });
        server.createContext("/jwks", x -> {
            RSAPublicKey publicKey = (RSAPublicKey) keys.getPublic();
            ObjectNode body = JSON.createObjectNode();
            body.putArray("keys").addObject().put("kid", "key-one").put("kty", "RSA").put("alg", "RS256")
                    .put("use", "sig").put("n", unsigned(publicKey.getModulus())).put("e", unsigned(publicKey.getPublicExponent()));
            byte[] bytes = JSON.writeValueAsBytes(body);
            x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); x.close();
        });
        server.start();
        verifier = new ChatGptIdTokenVerifier(new ChatGptOAuthHttp(HttpClient.newHttpClient(), URI.create(issuer)),
                URI.create(issuer + "/discovery"), issuer, Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
        claims = JSON.createObjectNode().put("iss", issuer).put("sub", "subject").put("aud", "issued-client")
                .put("exp", 2000).put("iat", 900).put("nonce", "saved-nonce").put("email", "user@example.test");
    }
    @AfterEach void cleanup() { server.stop(0); }

    @Test void validSignatureAndClaimsAreAccepted() throws Exception {
        ChatGptSessionService.Identity identity = verifier.verify(sign(claims), "issued-client", "saved-nonce");
        assertEquals("subject", identity.subject); assertEquals("user@example.test", identity.email);
    }
    @Test void wrongNonceAudienceIssuerAndExpiryAreRejected() throws Exception {
        ObjectNode original = claims.deepCopy();
        for (String name : new String[]{"nonce", "aud", "iss"}) {
            claims = original.deepCopy().put(name, "wrong"); assertInvalid(sign(claims));
        }
        claims = original.deepCopy().put("exp", 1000); assertInvalid(sign(claims));
        claims = original.deepCopy().put("nbf", 2000); assertInvalid(sign(claims));
        claims = original.deepCopy().put("iat", 2000); assertInvalid(sign(claims));
    }
    @Test void unsignedAndModifiedPayloadAreRejected() throws Exception {
        String signed = sign(claims);
        String[] parts = signed.split("\\.");
        claims.put("sub", "attacker");
        assertInvalid(parts[0] + "." + encode64(JSON.writeValueAsBytes(claims)) + "." + parts[2]);
        String header = encode64("{\"alg\":\"none\",\"kid\":\"key-one\"}".getBytes(StandardCharsets.UTF_8));
        assertInvalid(header + "." + parts[1] + ".");
    }
    @Test void multipleAudiencesRequireMatchingAuthorizedParty() throws Exception {
        claims.putArray("aud").add("issued-client").add("other");
        assertInvalid(sign(claims));
        claims.put("azp", "other"); assertInvalid(sign(claims));
        claims.put("azp", "issued-client");
        assertEquals("subject", verifier.verify(sign(claims), "issued-client", "saved-nonce").subject);
    }
    @Test void discoveryCannotRedirectJwksToAnotherOrigin() throws Exception {
        jwksOverride = "https://example.invalid/stolen-jwks";
        assertInvalid(sign(claims));
    }
    @Test void unreachableMetadataIsReportedAsTemporarilyUnavailable() throws Exception {
        URI closed = URI.create("http://127.0.0.1:1");
        var offline = new ChatGptIdTokenVerifier(new ChatGptOAuthHttp(HttpClient.newHttpClient(), closed),
                URI.create(closed + "/discovery"), issuer, Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> offline.verify(sign(claims), "issued-client", "saved-nonce"));
        assertEquals("chatgpt_identity_unavailable", error.getMessage()); assertNull(error.getCause());
    }
    @Test void duplicateAndTrailingJsonAreRejected() {
        assertThrows(IllegalStateException.class, () -> parse("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> parse("{} {}".getBytes(StandardCharsets.UTF_8)));
    }
    private void assertInvalid(String token) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> verifier.verify(token, "issued-client", "saved-nonce"));
        assertEquals("chatgpt_invalid_identity", error.getMessage()); assertNull(error.getCause());
    }
    private String sign(ObjectNode payload) throws Exception {
        String input = encode64("{\"alg\":\"RS256\",\"kid\":\"key-one\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encode64(JSON.writeValueAsBytes(payload));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(keys.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encode64(signature.sign());
    }
    private static String unsigned(BigInteger number) {
        byte[] bytes = number.toByteArray();
        if (bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return encode64(bytes);
    }
    private static String encode64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
