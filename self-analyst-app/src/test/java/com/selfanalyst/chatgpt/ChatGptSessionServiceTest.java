package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.selfanalyst.chatgpt.ChatGptOAuthHttp.*;

class ChatGptSessionServiceTest {
    @TempDir Path temp;
    private HttpServer server;
    private java.util.concurrent.ExecutorService serverThreads;
    private HttpClient client;
    private ChatGptSessionService service;
    private ChatGptSessionService.Endpoints endpoints;
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final AtomicReference<Map<String, String>> posted = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> revoked = new AtomicReference<>();
    private final List<String> revokedTokens = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Applied only to refresh-time verification (nonce == null); login verification stays valid.
    private volatile RuntimeException refreshVerifyFailure;
    private volatile String subject = "subject-one";
    private volatile String scopes = ChatGptSessionService.SCOPE;
    private volatile int lifetime = 3600;
    private volatile String refreshError;
    private volatile String exchangeError;
    private volatile int revocationStatus = 200;
    private volatile CountDownLatch refreshEntered, refreshRelease;
    private volatile CountDownLatch exchangeEntered, exchangeRelease;

    @BeforeEach void setup() throws Exception {
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        endpoints = new ChatGptSessionService.Endpoints(URI.create(origin), URI.create(origin + "/authorize"),
                URI.create(origin + "/token"), URI.create(origin + "/discovery"), origin);
        server.createContext("/discovery", x -> {
            byte[] body = JSON.writeValueAsBytes(Map.of("issuer", origin, "revocation_endpoint", origin + "/revoke"));
            x.sendResponseHeaders(200, body.length); x.getResponseBody().write(body); x.close();
        });
        server.createContext("/revoke", x -> {
            revoked.set(ChatGptSessionService.parseQuery(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            revokedTokens.add(revoked.get().get("token"));
            x.sendResponseHeaders(revocationStatus, -1); x.close();
        });
        server.createContext("/token", x -> {
            Map<String, String> form = ChatGptSessionService.parseQuery(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            posted.set(form);
            boolean refresh = "refresh_token".equals(form.get("grant_type"));
            if (refresh) refreshes.incrementAndGet(); else exchanges.incrementAndGet();
            try {
                if (refresh && refreshEntered != null) { refreshEntered.countDown(); refreshRelease.await(5, TimeUnit.SECONDS); }
                if (!refresh && exchangeEntered != null) { exchangeEntered.countDown(); exchangeRelease.await(5, TimeUnit.SECONDS); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ObjectNode body = JSON.createObjectNode();
            int status = 200;
            if (refresh && refreshError != null) { body.put("error", refreshError); status = 400; }
            else if (!refresh && exchangeError != null) { body.put("error", exchangeError); status = 400; }
            else body.put("access_token", refresh ? "rotated-access-secret" : "access-secret")
                    .put("refresh_token", refresh ? "rotated-refresh-secret" : "refresh-secret")
                    .put("id_token", "identity-secret").put("token_type", "Bearer")
                    .put("scope", scopes).put("expires_in", lifetime);
            byte[] bytes = JSON.writeValueAsBytes(body);
            x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes); x.close();
        });
        server.start();
        service = createService();
    }

    private ChatGptSessionService createService() {
        return new ChatGptSessionService(temp.resolve("auth"), client, clock, endpoints,
                (token, clientId, nonce) -> {
                    RuntimeException failure = refreshVerifyFailure;
                    if (failure != null && nonce == null) throw failure;
                    return new ChatGptSessionService.Identity(subject, "same@example.test");
                });
    }
    @AfterEach void cleanup() { if (service != null) service.close(); server.stop(0); serverThreads.shutdownNow(); }

    @Test void lazyStatusHasNoFilesAndAuthorizationUsesFreshPkce() throws Exception {
        assertEquals("signed_out", service.status().get("status"));
        assertFalse(Files.exists(temp.resolve("auth")));
        Map<String, Object> first = service.startLogin(null);
        Map<String, String> q1 = query(first);
        assertEquals("dynamic_agent_client", q1.get("client_id"));
        assertEquals("SelfAnalyst", q1.get("agent_name_hint"));
        assertEquals(ChatGptSessionService.SCOPE, q1.get("scope"));
        assertTrue(q1.get("redirect_uri").startsWith("http://127.0.0.1:"));
        assertTrue(q1.get("redirect_uri").endsWith("/auth/callback"));
        assertEquals("S256", q1.get("code_challenge_method"));
        Map<String, String> q2 = query(service.startLogin(null));
        assertNotEquals(q1.get("state"), q2.get("state"));
        assertNotEquals(q1.get("nonce"), q2.get("nonce"));
        assertNotEquals(q1.get("code_challenge"), q2.get("code_challenge"));
        assertEquals(q1.get("ext_agent_host_id"), q2.get("ext_agent_host_id"));
        assertEquals(0, exchanges.get());
    }

    @Test void successfulLoginBindsIssuedClientAndNeverExposesTokens() throws Exception {
        Map<String, Object> attempt = service.startLogin(null);
        Map<String, String> q = query(attempt);
        assertEquals(200, callback(q, "client_id=issued-one&code=code-secret"));
        assertEquals("issued-one", posted.get().get("client_id"));
        assertEquals(q.get("redirect_uri"), posted.get().get("redirect_uri"));
        assertEquals(ChatGptSessionService.RESOURCE, posted.get().get("resource"));
        assertEquals(q.get("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(posted.get().get("code_verifier").getBytes(StandardCharsets.US_ASCII))));
        assertEquals("connected", service.status().get("status"));
        String rendered = JSON.writeValueAsString(service.status());
        for (String secret : List.of("access-secret", "refresh-secret", "identity-secret", "code-secret", "issued-one", "subject-one"))
            assertFalse(rendered.contains(secret));
        String id = activeId();
        assertEquals("access-secret", service.accessToken(id));
        service.close(); service = createService();
        assertTrue(service.isConnected(id));
        assertEquals("access-secret", service.accessToken(id));
    }

    @Test void invalidStateAndDuplicateQueryDoNotConsumeAttempt() throws Exception {
        Map<String, String> q = query(service.startLogin(null));
        assertEquals(400, get(q.get("redirect_uri") + "?state=wrong&code=x&client_id=issued"));
        assertEquals(400, callback(q, "state=" + encode(q.get("state")) + "&code=x&client_id=issued"));
        assertEquals("pending", service.status().get("status"));
        assertEquals(0, exchanges.get());
        assertEquals(200, callback(q, "code=x&client_id=issued"));
        assertEquals(1, exchanges.get());
        assertThrows(Exception.class, () -> callback(q, "code=x&client_id=issued"));
    }

    @Test void missingIssuedClientAndDeclinedConsentCannotExchange() throws Exception {
        Map<String, String> q = query(service.startLogin(null));
        assertEquals(400, callback(q, "code=x"));
        assertEquals(0, exchanges.get());
        q = query(service.startLogin(null));
        assertEquals(400, callback(q, "error=access_denied"));
        assertEquals(0, exchanges.get());
        assertEquals("chatgpt_authorization_declined", service.status().get("errorCode"));
    }

    @Test void cancellationAndExpiryReleaseListenerAndKeepPriorAccount() throws Exception {
        login("issued"); String id = activeId();
        Map<String, Object> attempt = service.startLogin(null);
        service.cancelLogin((String) attempt.get("attemptId"));
        assertEquals("connected", service.status().get("status"));
        assertTrue(service.isConnected(id));
        assertThrows(Exception.class, () -> callback(query(attempt), "code=x&client_id=other"));
        service.startLogin(null); clock.advance(301);
        assertEquals("chatgpt_login_expired", service.status().get("errorCode"));
        assertEquals("", service.status().get("loginAttemptId"));
    }

    @Test void sameEmailRegistrationsStaySeparateAndReturnMustMatchIdentityAndClient() throws Exception {
        login("issued-one"); String first = activeId();
        login("issued-two"); String second = activeId();
        assertNotEquals(first, second);
        assertEquals(2, ((List<?>) service.status().get("accounts")).size());
        Map<String, String> q = query(service.startLogin(first));
        assertEquals("issued-one", q.get("client_id"));
        assertFalse(q.containsKey("agent_name_hint"));
        assertFalse(q.containsKey("id_token_hint"));
        assertEquals(400, callback(q, "code=x&client_id=issued-two"));
        assertTrue(service.isConnected(first));
        q = query(service.startLogin(first)); subject = "wrong-subject";
        assertEquals(400, callback(q, "code=x"));
        assertEquals("chatgpt_identity_mismatch", service.status().get("errorCode"));
        assertTrue(service.isConnected(first));
        subject = "subject-one";
        q = query(service.startLogin(first)); assertEquals(200, callback(q, "code=x"));
        assertEquals(first, activeId());
    }

    @Test void missingDirectScopeRetainsRegistrationAndRequestsConsent() throws Exception {
        scopes = "openid email offline_access";
        login("issued"); String id = activeId();
        assertFalse(service.isConnected(id));
        assertEquals("chatgpt_plan_permission_required", service.status().get("errorCode"));
        assertEquals("chatgpt_plan_permission_required", assertThrows(IllegalStateException.class, () -> service.accessToken(id)).getMessage());
        Map<String, String> q = query(service.startLogin(id));
        assertEquals("issued", q.get("client_id"));
        assertEquals("consent", q.get("prompt"));
    }

    @Test void refreshRotatesOnceAcrossServiceInstancesAndPersistsLatest() throws Exception {
        lifetime = 30; login("issued"); String id = activeId(); lifetime = 3600;
        try (ChatGptSessionService other = createService(); var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                ChatGptSessionService selected = i % 2 == 0 ? service : other;
                futures.add(pool.submit(() -> selected.accessToken(id)));
            }
            for (var future : futures) assertEquals("rotated-access-secret", future.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, refreshes.get());
        assertEquals("issued", posted.get().get("client_id"));
        assertFalse(posted.get().containsKey("scope"));
        assertEquals("refresh-secret", posted.get().get("refresh_token"));
        assertTrue(Files.readString(temp.resolve("auth/credentials.json")).contains("rotated-refresh-secret"));
    }

    @Test void transientRefreshPreservesCredentialsButTerminalErrorClearsTokens() throws Exception {
        lifetime = 30; login("issued"); String id = activeId();
        refreshError = "temporarily_unavailable";
        assertEquals("chatgpt_refresh_failed", assertThrows(IllegalStateException.class, () -> service.accessToken(id)).getMessage());
        assertTrue(service.isConnected(id));
        refreshError = "refresh_token_reused";
        assertEquals("chatgpt_sign_in_required", assertThrows(IllegalStateException.class, () -> service.accessToken(id)).getMessage());
        assertFalse(service.isConnected(id));
        String saved = Files.readString(temp.resolve("auth/credentials.json"));
        assertTrue(saved.contains("issued")); assertFalse(saved.contains("access-secret"));
    }

    @Test void logoutRetainsRegistrationAndReportsFailedRemoteRevocation() throws Exception {
        Map<String, String> q = query(service.startLogin(null)); callback(q, "code=x&client_id=issued");
        String id = activeId(); revocationStatus = 503;
        assertEquals(false, service.signOut(id).get("remoteRevocationConfirmed"));
        assertEquals("refresh-secret", revoked.get().get("token"));
        assertEquals("issued", revoked.get().get("client_id"));
        assertEquals("refresh_token", revoked.get().get("token_type_hint"));
        assertFalse(service.isConnected(id));
        String saved = Files.readString(temp.resolve("auth/credentials.json"));
        assertFalse(saved.contains("access-secret")); assertFalse(saved.contains("refresh-secret")); assertFalse(saved.contains("identity-secret"));
        Map<String, String> again = query(service.startLogin(id));
        assertEquals("issued", again.get("client_id"));
        assertEquals(q.get("ext_agent_host_id"), again.get("ext_agent_host_id"));
    }

    @Test void cancelledInFlightExchangeCannotPersist() throws Exception {
        exchangeEntered = new CountDownLatch(1); exchangeRelease = new CountDownLatch(1);
        Map<String, Object> attempt = service.startLogin(null);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> { try { return callback(query(attempt), "code=x&client_id=issued"); } catch (Exception e) { return 0; } });
            assertTrue(exchangeEntered.await(3, TimeUnit.SECONDS));
            service.cancelLogin((String) attempt.get("attemptId"));
            exchangeRelease.countDown(); future.get(5, TimeUnit.SECONDS);
        }
        assertEquals("signed_out", service.status().get("status"));
        assertTrue(((List<?>) service.status().get("accounts")).isEmpty());
    }

    @Test void logoutWhileRefreshInFlightBlocksAndCannotResurrectTokens() throws Exception {
        lifetime = 30; login("issued"); String id = activeId(); lifetime = 3600;
        refreshEntered = new CountDownLatch(1); refreshRelease = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var token = pool.submit(() -> service.accessToken(id));
            assertTrue(refreshEntered.await(3, TimeUnit.SECONDS));
            var logout = pool.submit(() -> service.signOut(id));
            Thread.sleep(100);
            assertFalse(service.isConnected(id));
            refreshRelease.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> token.get(5, TimeUnit.SECONDS));
            assertEquals(true, logout.get(5, TimeUnit.SECONDS).get("remoteRevocationConfirmed"));
            // Sign-out revokes the stored token at once; the discarded late rotation is revoked too.
            assertTrue(revokedTokens.contains("refresh-secret"));
            assertTrue(revokedTokens.contains("rotated-refresh-secret"));
        }
        assertFalse(service.isConnected(id));
        assertFalse(Files.readString(temp.resolve("auth/credentials.json")).contains("rotated-access-secret"));
    }

    @Test void statusAndConnectionChecksDoNotWaitForInFlightRefresh() throws Exception {
        lifetime = 30; login("issued"); String id = activeId(); lifetime = 3600;
        refreshEntered = new CountDownLatch(1); refreshRelease = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var token = pool.submit(() -> service.accessToken(id));
            assertTrue(refreshEntered.await(3, TimeUnit.SECONDS));
            var status = pool.submit(() -> service.status());
            assertEquals("connected", status.get(1, TimeUnit.SECONDS).get("status"));
            assertTrue(service.isConnected(id));
            refreshRelease.countDown();
            assertEquals("rotated-access-secret", token.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void terminalErrorDoesNotClearNewerRotationSavedByAnotherProcess() throws Exception {
        lifetime = 30; login("issued"); String id = activeId(); lifetime = 3600;
        refreshError = "refresh_token_reused";
        refreshEntered = new CountDownLatch(1); refreshRelease = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var token = pool.submit(() -> service.accessToken(id));
            assertTrue(refreshEntered.await(3, TimeUnit.SECONDS));
            // Another process saved a newer rotation while our stale request was in flight.
            new ChatGptCredentialStore(temp.resolve("auth")).locked(tx -> {
                ((ObjectNode) tx.data().path("accounts").path(id)).put("accessToken", "other-process-access")
                        .put("refreshToken", "other-process-refresh").put("expiresAt", clock.instant().getEpochSecond() + 3600);
                tx.save(); return null;
            });
            refreshRelease.countDown();
            assertEquals("other-process-access", token.get(5, TimeUnit.SECONDS));
        }
        assertTrue(Files.readString(temp.resolve("auth/credentials.json")).contains("other-process-refresh"));
    }

    @Test void rotationIsSavedBeforeIdentityCheckAndRetriedWithoutRefreshingAgain() throws Exception {
        lifetime = 30; login("issued"); String id = activeId(); lifetime = 3600;
        refreshVerifyFailure = new IllegalStateException("chatgpt_identity_unavailable");
        assertEquals("chatgpt_refresh_failed", assertThrows(IllegalStateException.class, () -> service.accessToken(id)).getMessage());
        String saved = Files.readString(temp.resolve("auth/credentials.json"));
        assertTrue(saved.contains("rotated-refresh-secret"));
        assertTrue(saved.contains("pendingIdToken"));
        assertTrue(service.isConnected(id));
        refreshVerifyFailure = null;
        assertEquals("rotated-access-secret", service.accessToken(id));
        assertEquals(1, refreshes.get());
        assertFalse(Files.readString(temp.resolve("auth/credentials.json")).contains("pendingIdToken"));
    }

    @Test void definiteIdentityFailureAfterRotationClearsTokens() throws Exception {
        lifetime = 30; login("issued"); String id = activeId(); lifetime = 3600;
        refreshVerifyFailure = new IllegalStateException("chatgpt_invalid_identity");
        assertEquals("chatgpt_sign_in_required", assertThrows(IllegalStateException.class, () -> service.accessToken(id)).getMessage());
        assertFalse(service.isConnected(id));
        String saved = Files.readString(temp.resolve("auth/credentials.json"));
        assertTrue(saved.contains("issued")); assertFalse(saved.contains("rotated-access-secret"));
        assertFalse(saved.contains("rotated-refresh-secret"));
    }

    @Test void refreshedIdentityForAnotherSubjectClearsTokens() throws Exception {
        lifetime = 30; login("issued"); String id = activeId(); lifetime = 3600;
        subject = "other-subject";
        assertEquals("chatgpt_sign_in_required", assertThrows(IllegalStateException.class, () -> service.accessToken(id)).getMessage());
        assertFalse(service.isConnected(id));
        assertFalse(Files.readString(temp.resolve("auth/credentials.json")).contains("rotated-access-secret"));
    }

    @Test void invalidCodeRetryRetainsIssuedClientWithoutTrustingIdentity() throws Exception {
        exchangeError = "invalid_grant";
        Map<String, String> q = query(service.startLogin(null));
        assertEquals(400, callback(q, "code=used&client_id=issued"));
        assertTrue(((List<?>) service.status().get("accounts")).isEmpty());
        service.close(); service = createService();
        Map<String, String> retry = query(service.startLogin(null));
        assertEquals("issued", retry.get("client_id"));
        assertFalse(retry.containsKey("agent_name_hint"));
        assertEquals(q.get("ext_agent_host_id"), retry.get("ext_agent_host_id"));
        exchangeError = null;
        assertEquals(200, callback(retry, "code=new-code"));
        assertEquals("connected", service.status().get("status"));
    }

    @Test void transientCodeExchangeFailureRetainsOnlyUnverifiedRegistrationAcrossRestart() throws Exception {
        exchangeError = "temporarily_unavailable";
        Map<String, String> q = query(service.startLogin(null));
        assertEquals(400, callback(q, "code=x&client_id=issued"));
        service.close(); service = createService();
        assertTrue(((List<?>) service.status().get("accounts")).isEmpty());
        assertEquals("", activeId());
        Map<String, String> retry = query(service.startLogin(null));
        assertEquals("issued", retry.get("client_id"));
        exchangeError = null;
        assertEquals(200, callback(retry, "code=new"));
        assertFalse(Files.readString(temp.resolve("auth/credentials.json")).contains("pendingRegistration"));
    }

    @Test void earliestRefreshIsRespected() throws Exception {
        lifetime = 30; login("issued"); String id = activeId();
        new ChatGptCredentialStore(temp.resolve("auth")).locked(tx -> {
            ((ObjectNode) tx.data().path("accounts").path(id)).put("earliestRefreshAt", clock.instant().getEpochSecond() + 120);
            tx.save(); return null;
        });
        assertEquals("access-secret", service.accessToken(id));
        clock.advance(31);
        assertEquals("chatgpt_refresh_not_yet_allowed", assertThrows(IllegalStateException.class, () -> service.accessToken(id)).getMessage());
        assertEquals(0, refreshes.get());
    }

    private void login(String clientId) throws Exception { assertEquals(200, callback(query(service.startLogin(null)), "code=x&client_id=" + clientId)); }
    private String activeId() { return (String) service.status().get("activeAccountId"); }
    private static Map<String, String> query(Map<String, Object> attempt) { return ChatGptSessionService.parseQuery(URI.create((String) attempt.get("authorizationUrl")).getRawQuery()); }
    private int callback(Map<String, String> query, String rest) throws Exception { return get(query.get("redirect_uri") + "?state=" + encode(query.get("state")) + "&" + rest); }
    private int get(String url) throws Exception { return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(); }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T12:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
