package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static com.selfanalyst.chatgpt.ChatGptOAuthHttp.*;

/** Public-client SIWC sessions. Only explicitly allowlisted metadata leaves status(). */
public final class ChatGptSessionService implements AutoCloseable {
    static final String RESOURCE = "https://api.openai.com/v1";
    static final String SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    static final String DIRECT_SCOPE = "chatgpt.tokens.use.direct";
    static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    // Refresh, then verify the returned identity, then hand out the token; extra steps absorb supersession.
    private static final int MAX_TOKEN_STEPS = 4;
    private static final Set<String> TERMINAL_REFRESH = Set.of("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused");
    private final ChatGptCredentialStore store;
    private final ChatGptOAuthHttp http;
    private final Endpoints endpoints;
    private final Clock clock;
    private final IdTokenVerifier verifier;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Long> signingOut = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong sessionEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chatgpt-auth-expiry"); thread.setDaemon(true); return thread;
    });
    private volatile Attempt pending;
    private volatile boolean closed;
    private volatile String errorCode = "";
    private boolean ownsClient;

    public ChatGptSessionService(Path directory) {
        this(directory, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC(), Endpoints.production(), null);
        ownsClient = true;
    }

    // Package-private test seam; production destinations cannot be selected by configuration or UI.
    ChatGptSessionService(Path directory, HttpClient client, Clock clock, Endpoints endpoints, IdTokenVerifier verifier) {
        this.store = new ChatGptCredentialStore(directory);
        this.clock = clock;
        this.endpoints = endpoints;
        this.http = new ChatGptOAuthHttp(client, endpoints.origin);
        this.verifier = verifier == null ? new ChatGptIdTokenVerifier(http, endpoints.discovery, endpoints.issuer, clock) : verifier;
    }

    public Map<String, Object> status() {
        expireAttempt();
        ObjectNode data = store.read();
        List<Map<String, Object>> accounts = new ArrayList<>();
        data.path("accounts").fields().forEachRemaining(entry -> {
            JsonNode account = entry.getValue();
            accounts.add(Map.of("id", entry.getKey(), "label", account.path("label").asText(),
                    "email", account.path("email").asText(), "connected", connected(entry.getKey(), account),
                    "planUsageEnabled", hasDirectScope(account)));
        });
        String activeId = data.path("activeAccountId").asText("");
        JsonNode active = data.path("accounts").path(activeId);
        String error = errorCode;
        if (error.isEmpty() && active.hasNonNull("accessToken") && !hasDirectScope(active)) error = "chatgpt_plan_permission_required";
        Attempt attempt = pending;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", attempt != null ? "pending" : !error.isEmpty() ? "error"
                : connected(activeId, active) ? "connected" : "signed_out");
        result.put("accounts", accounts);
        result.put("activeAccountId", activeId);
        result.put("loginAttemptId", attempt == null ? "" : attempt.id);
        result.put("errorCode", error);
        return result;
    }

    public synchronized Map<String, Object> startLogin(String accountId) {
        requireOpen();
        discardAttempt();
        ObjectNode data = store.locked(tx -> tx.data().deepCopy());
        String selectedId = accountId == null ? "" : accountId.trim();
        JsonNode selected = data.path("accounts").path(selectedId);
        if (!selectedId.isEmpty() && !selected.isObject()) throw failure("unknown_account");
        String provisionalClient = data.path("pendingRegistration").path("clientId").asText("");
        String clientId = selectedId.isEmpty() ? (provisionalClient.isEmpty() ? DYNAMIC_CLIENT : provisionalClient) : required(selected, "clientId");
        String state = randomToken(), nonce = randomToken(), pkce = randomToken();
        try {
            HttpServer listener = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            String callback = "http://127.0.0.1:" + listener.getAddress().getPort() + "/auth/callback";
            Attempt attempt = new Attempt(UUID.randomUUID().toString(), selectedId, clientId, state, nonce, pkce,
                    callback, clock.instant().plus(Duration.ofMinutes(5)), listener, sessionEpoch.get());
            listener.createContext("/auth/callback", exchange -> callback(attempt, exchange));
            listener.setExecutor(scheduler);
            pending = attempt;
            errorCode = "";
            listener.start();
            scheduler.schedule(() -> expire(attempt), 5, TimeUnit.MINUTES);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("client_id", clientId);
            if (DYNAMIC_CLIENT.equals(clientId)) query.put("agent_name_hint", "SelfAnalyst");
            query.put("ext_agent_host_id", required(data, "hostId"));
            query.put("response_type", "code");
            query.put("redirect_uri", callback);
            query.put("scope", SCOPE);
            query.put("resource", RESOURCE);
            query.put("state", state);
            query.put("nonce", nonce);
            query.put("code_challenge_method", "S256");
            query.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(pkce.getBytes(StandardCharsets.US_ASCII))));
            // Email is sufficient as an optional hint; do not put retained ID tokens in a UI-visible URL.
            if (!selected.path("email").asText().isBlank()) query.put("login_hint", selected.path("email").asText());
            if (!selectedId.isEmpty() && selected.has("scopes") && !hasDirectScope(selected)) query.put("prompt", "consent");
            return Map.of("authorizationUrl", endpoints.authorize + "?" + form(query), "attemptId", attempt.id,
                    "expiresAt", attempt.expiresAt.toString());
        } catch (Exception e) {
            discardAttempt();
            throw failure("login_start_failed");
        }
    }

    public synchronized void cancelLogin(String attemptId) {
        if (pending != null && pending.id.equals(attemptId)) {
            discardAttempt();
            errorCode = "";
        }
    }

    public Map<String, Object> signOut(String accountId) {
        String id = accountId == null || accountId.isBlank() ? resolveId(accountId, store.read()) : accountId;
        if (id.isEmpty()) return Map.of("remoteRevocationConfirmed", true);
        long epoch = sessionEpoch.incrementAndGet();
        signingOut.put(id, epoch); // Block new requests immediately, including an in-flight refresh's write-back.
        synchronized (this) { discardAttempt(); }
        String[] revocable;
        try {
            // Clear locally first; the remote revocation below runs outside the storage lock.
            revocable = store.locked(tx -> {
                ObjectNode account = account(tx.data(), id);
                if (account == null) throw failure("unknown_account");
                String[] value = account.hasNonNull("refreshToken")
                        ? new String[] {required(account, "refreshToken"), required(account, "clientId")} : null;
                clearTokens(account);
                if (id.equals(tx.data().path("activeAccountId").asText())) tx.data().put("activeAccountId", "");
                tx.save();
                return value;
            });
        } catch (IllegalStateException e) {
            if ("chatgpt_unknown_account".equals(e.getMessage())) signingOut.remove(id, epoch);
            throw e;
        }
        errorCode = "";
        boolean confirmed = revocable == null || revokeQuietly(revocable[0], revocable[1]);
        // Keep this account blocked until a successful explicit login, including when local cleanup fails.
        return Map.of("remoteRevocationConfirmed", confirmed);
    }

    public boolean isConnected(String accountId) {
        if (closed || (accountId != null && signingOut.containsKey(accountId))) return false;
        ObjectNode data = store.read();
        String id = resolveId(accountId, data);
        return connected(id, data.path("accounts").path(id));
    }

    public String accessToken(String accountId) {
        requireOpen();
        if (accountId != null && signingOut.containsKey(accountId)) throw failure("sign_in_required");
        Ticket first = store.locked(tx -> ticket(tx, accountId));
        if (first.accessToken != null) return guardedToken(first.id, first.accessToken);
        // Network waits happen under the refresh lock only; the storage lock is held for short
        // read/compare/write steps, so status and connection checks never wait on the remote side.
        return store.refreshing(() -> {
            for (int step = 0; step < MAX_TOKEN_STEPS; step++) {
                Ticket current = store.locked(tx -> ticket(tx, first.id)); // Another waiter may have refreshed already.
                if (current.accessToken != null) return guardedToken(current.id, current.accessToken);
                if (current.pendingIdToken != null) verifyPending(current);
                else refresh(current);
            }
            throw failure("refresh_failed");
        });
    }

    private Ticket ticket(ChatGptCredentialStore.Transaction tx, String accountId) {
        String id = resolveId(accountId, tx.data());
        ObjectNode account = account(tx.data(), id);
        if (closed || signingOut.containsKey(id) || account == null || !account.hasNonNull("accessToken"))
            throw failure("sign_in_required");
        if (!hasDirectScope(account)) throw failure("plan_permission_required");
        String clientId = required(account, "clientId");
        // A rotated token is never handed out before its returned identity has been verified.
        if (account.hasNonNull("pendingIdToken"))
            return Ticket.verify(id, clientId, required(account, "pendingIdToken"));
        long now = clock.instant().getEpochSecond();
        long expiresAt = account.path("expiresAt").asLong(0);
        long earliestRefresh = account.path("earliestRefreshAt").asLong(0);
        if (expiresAt > now + 60 || (expiresAt > now && earliestRefresh > now))
            return Ticket.ready(id, required(account, "accessToken"));
        if (earliestRefresh > now) throw failure("refresh_not_yet_allowed");
        if (!account.hasNonNull("refreshToken")) throw failure("sign_in_required");
        return Ticket.refresh(id, clientId, required(account, "refreshToken"));
    }

    /** Remote refresh outside the storage lock; the result is written back only if still current. */
    private void refresh(Ticket ticket) {
        Result result = http.post(endpoints.token, Map.of("grant_type", "refresh_token", "client_id", ticket.clientId,
                "refresh_token", ticket.refreshToken, "resource", RESOURCE));
        if (result.status != 200) {
            if (!TERMINAL_REFRESH.contains(oauthError(result))) throw failure("refresh_failed");
            boolean cleared = store.locked(tx -> {
                ObjectNode account = account(tx.data(), ticket.id);
                // A newer rotation saved by another process is not invalidated by our stale token.
                if (account == null || !ticket.refreshToken.equals(account.path("refreshToken").asText(null))) return false;
                clearTokens(account); tx.save(); return true;
            });
            if (cleared) throw failure("sign_in_required");
            return; // Superseded: the caller re-reads the latest stored credential.
        }
        JsonNode response = parse(result.body);
        boolean kept = store.locked(tx -> {
            ObjectNode account = account(tx.data(), ticket.id);
            if (closed || signingOut.containsKey(ticket.id) || account == null
                    || !ticket.refreshToken.equals(account.path("refreshToken").asText(null))) return false;
            ObjectNode updated = account.deepCopy();
            applyTokens(updated, response, false);
            ((ObjectNode) tx.data().path("accounts")).set(ticket.id, updated);
            // The rotated refresh token is saved before identity checks, so a later failure cannot lose it.
            try { tx.save(); }
            catch (RuntimeException e) { signingOut.put(ticket.id, sessionEpoch.incrementAndGet()); throw e; }
            return true;
        });
        if (!kept && response.path("refresh_token").isTextual())
            revokeQuietly(response.path("refresh_token").textValue(), ticket.clientId); // Signed out or replaced meanwhile.
    }

    private void verifyPending(Ticket ticket) {
        Identity identity = null;
        try { identity = verifier.verify(ticket.pendingIdToken, ticket.clientId, null); }
        catch (RuntimeException e) {
            // Keep the saved credential and retry verification later; never refresh again for it.
            if ("chatgpt_identity_unavailable".equals(e.getMessage())) throw failure("refresh_failed");
        }
        Identity verified = identity;
        boolean valid = store.locked(tx -> {
            ObjectNode account = account(tx.data(), ticket.id);
            if (account == null || !ticket.pendingIdToken.equals(account.path("pendingIdToken").asText(null))) return true;
            if (verified == null || !verified.subject.equals(account.path("subject").asText())) {
                clearTokens(account); tx.save(); return false;
            }
            account.put("idToken", ticket.pendingIdToken).remove("pendingIdToken");
            tx.save();
            return true;
        });
        if (!valid) throw failure("sign_in_required");
    }

    private String guardedToken(String id, String token) {
        if (closed || signingOut.containsKey(id)) throw failure("sign_in_required");
        return token;
    }

    private void callback(Attempt attempt, HttpExchange exchange) throws IOException {
        int status = 400;
        String message = "Sign-in could not be completed. Return to SelfAnalyst.";
        boolean claimed = false;
        try {
            if (!"GET".equals(exchange.getRequestMethod()) || !"/auth/callback".equals(exchange.getRequestURI().getRawPath())
                    || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) throw failure("invalid_callback");
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            synchronized (this) {
                if (pending != attempt || attempt.consumed || attempt.cancelled || !clock.instant().isBefore(attempt.expiresAt)
                        || !ChatGptIdTokenVerifier.constantEquals(attempt.state, query.getOrDefault("state", "")))
                    throw failure("invalid_callback");
                attempt.consumed = true;
                claimed = true;
            }
            if (query.containsKey("error")) throw failure("authorization_declined");
            String code = query.getOrDefault("code", "");
            if (code.isBlank()) throw failure("invalid_callback");
            String issuedClient = query.getOrDefault("client_id", attempt.clientId);
            if (issuedClient.isBlank() || DYNAMIC_CLIENT.equals(issuedClient)
                    || (!DYNAMIC_CLIENT.equals(attempt.clientId) && !issuedClient.equals(attempt.clientId))) throw failure("client_mismatch");
            if (attempt.accountId.isEmpty()) {
                synchronized (this) {
                    if (pending != attempt || attempt.cancelled || closed) throw failure("login_cancelled");
                    store.locked(tx -> {
                        // A callback-issued client is registration metadata, NOT a verified identity or usable account.
                        tx.data().putObject("pendingRegistration").put("clientId", issuedClient);
                        tx.save(); return null;
                    });
                }
            }
            Result result = http.post(endpoints.token, Map.of("grant_type", "authorization_code", "client_id", issuedClient,
                    "code", code, "code_verifier", attempt.pkce, "redirect_uri", attempt.callback, "resource", RESOURCE));
            if (result.status != 200) throw failure("code_exchange_failed");
            JsonNode response = parse(result.body);
            Identity identity = verifier.verify(required(response, "id_token"), issuedClient, attempt.nonce);
            synchronized (this) {
                if (pending != attempt || attempt.cancelled || closed || !clock.instant().isBefore(attempt.expiresAt))
                    throw failure("login_cancelled");
                String id = store.locked(tx -> {
                    ObjectNode accounts = (ObjectNode) tx.data().path("accounts");
                    String targetId = attempt.accountId;
                    ObjectNode existing = targetId.isEmpty() ? null : account(tx.data(), targetId);
                    if (!targetId.isEmpty() && (existing == null || !identity.subject.equals(required(existing, "subject"))
                            || !issuedClient.equals(required(existing, "clientId")))) throw failure("identity_mismatch");
                    if (targetId.isEmpty()) {
                        var entries = accounts.fields();
                        while (entries.hasNext()) {
                            var entry = entries.next();
                            if (issuedClient.equals(entry.getValue().path("clientId").asText())) {
                                if (!identity.subject.equals(entry.getValue().path("subject").asText())) throw failure("identity_mismatch");
                                targetId = entry.getKey(); existing = (ObjectNode) entry.getValue(); break;
                            }
                        }
                    }
                    if (targetId.isEmpty()) targetId = UUID.randomUUID().toString();
                    ObjectNode updated = existing == null ? JSON.createObjectNode() : existing.deepCopy();
                    updated.put("clientId", issuedClient).put("subject", identity.subject).put("email", identity.email);
                    if (!updated.has("label")) updated.put("label", (identity.email.isBlank() ? "ChatGPT" : identity.email)
                            + " · " + targetId.substring(0, 8));
                    applyTokens(updated, response, true);
                    accounts.set(targetId, updated);
                    tx.data().put("activeAccountId", targetId);
                    if (issuedClient.equals(tx.data().path("pendingRegistration").path("clientId").asText()))
                        tx.data().remove("pendingRegistration");
                    tx.save();
                    errorCode = hasDirectScope(updated) ? "" : "chatgpt_plan_permission_required";
                    return targetId;
                });
                Long blockedAt = signingOut.get(id);
                if (blockedAt != null && blockedAt <= attempt.epoch) signingOut.remove(id, blockedAt);
                pending = null;
            }
            status = 200;
            message = "Sign-in completed. Return to SelfAnalyst to review your connection.";
        } catch (RuntimeException e) {
            if (claimed) {
                synchronized (this) {
                    if (pending == attempt) { errorCode = safeError(e); pending = null; }
                }
            }
        } finally {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            try {
                byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
                if (claimed) attempt.listener.stop(0);
            }
        }
    }

    private void applyTokens(ObjectNode account, JsonNode response, boolean initial) {
        String access = required(response, "access_token");
        String refresh = response.hasNonNull("refresh_token") ? required(response, "refresh_token") : null;
        if (!initial && refresh == null) throw failure("invalid_response");
        if (!"Bearer".equalsIgnoreCase(required(response, "token_type"))) throw failure("invalid_response");
        JsonNode lifetime = response.path("expires_in");
        if (!lifetime.isIntegralNumber() || lifetime.asLong() <= 0 || lifetime.asLong() > 86400) throw failure("invalid_response");
        if (response.has("scope") && !response.path("scope").isTextual()) throw failure("invalid_response");
        String scopes = response.has("scope") ? response.path("scope").asText() : initial ? "" : account.path("scopes").asText();
        if (List.of(scopes.split("\\s+")).contains("offline_access") && refresh == null) throw failure("invalid_response");
        account.put("accessToken", access).put("scopes", scopes)
                .put("expiresAt", clock.instant().getEpochSecond() + lifetime.asLong());
        if (refresh == null) account.remove("refreshToken"); else account.put("refreshToken", refresh);
        if (response.has("id_token")) {
            // Login verifies before calling this; a refresh identity is verified after the rotation is saved.
            account.put(initial ? "idToken" : "pendingIdToken", required(response, "id_token"));
        }
        if (response.hasNonNull("earliest_refresh_at")) {
            JsonNode value = response.get("earliest_refresh_at");
            long earliest;
            try { earliest = value.isIntegralNumber() ? value.longValue() : Instant.parse(value.asText()).getEpochSecond(); }
            catch (RuntimeException e) { throw failure("invalid_response"); }
            account.put("earliestRefreshAt", earliest);
        } else account.put("earliestRefreshAt", 0);
    }

    private boolean revoke(String refresh, String clientId) {
        JsonNode discovery = http.get(endpoints.discovery);
        if (!endpoints.issuer.equals(required(discovery, "issuer"))) throw failure("discovery_failed");
        URI uri = http.trusted(required(discovery, "revocation_endpoint"));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Result result = http.post(uri, Map.of("token", refresh, "token_type_hint", "refresh_token", "client_id", clientId));
                if (result.status == 200) return true;
                if (result.status < 500) return false;
            } catch (RuntimeException ignored) {
                if (Thread.currentThread().isInterrupted()) return false;
            }
            if (attempt == 0) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private boolean revokeQuietly(String refresh, String clientId) {
        try { return revoke(refresh, clientId); } catch (RuntimeException ignored) { return false; }
    }

    private boolean connected(String id, JsonNode account) {
        return !closed && !id.isEmpty() && !signingOut.containsKey(id) && account.hasNonNull("accessToken")
                && hasDirectScope(account) && (account.path("expiresAt").asLong() > clock.instant().getEpochSecond()
                || account.hasNonNull("refreshToken"));
    }
    private static boolean hasDirectScope(JsonNode account) {
        return List.of(account.path("scopes").asText("").split("\\s+")).contains(DIRECT_SCOPE);
    }
    private static String resolveId(String id, JsonNode data) { return id == null || id.isBlank() ? data.path("activeAccountId").asText("") : id; }
    private static ObjectNode account(ObjectNode data, String id) {
        JsonNode node = data.path("accounts").path(id); return node instanceof ObjectNode object ? object : null;
    }
    private static void clearTokens(ObjectNode account) {
        account.remove(List.of("accessToken", "refreshToken", "idToken", "pendingIdToken", "expiresAt", "earliestRefreshAt"));
    }
    private static String oauthError(Result result) {
        try { return parse(result.body).path("error").asText(""); } catch (RuntimeException e) { return ""; }
    }
    private static String safeError(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.matches("chatgpt_[a-z_]{1,50}") ? message : "chatgpt_authorization_failed";
    }
    static Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.length() > 16384) throw failure("invalid_callback");
        Map<String, String> query = new HashMap<>();
        try {
            for (String part : raw.split("&", -1)) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) throw failure("invalid_callback");
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (key.isBlank() || query.putIfAbsent(key, value) != null) throw failure("invalid_callback");
            }
            return query;
        } catch (IllegalArgumentException e) { throw failure("invalid_callback"); }
    }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void requireOpen() { if (closed) throw failure("service_closed"); }
    private synchronized void expireAttempt() { if (pending != null && !clock.instant().isBefore(pending.expiresAt)) expire(pending); }
    private synchronized void expire(Attempt attempt) {
        if (pending == attempt && !clock.instant().isBefore(attempt.expiresAt)) { discardAttempt(); errorCode = "chatgpt_login_expired"; }
    }
    private void discardAttempt() {
        Attempt old = pending; pending = null;
        if (old != null) { old.cancelled = true; old.listener.stop(0); }
    }
    @Override public synchronized void close() { closed = true; discardAttempt(); scheduler.shutdownNow(); if (ownsClient) http.shutdown(); }

    @FunctionalInterface interface IdTokenVerifier { Identity verify(String token, String clientId, String nonce); }
    static final class Identity {
        final String subject;
        final String email;
        Identity(String subject, String email) { this.subject = subject; this.email = email; }
    }
    static final class Endpoints {
        final URI origin, authorize, token, discovery;
        final String issuer;
        Endpoints(URI origin, URI authorize, URI token, URI discovery, String issuer) {
            boolean production = "https://auth.openai.com".equals(origin.toString());
            boolean test = "http".equals(origin.getScheme()) && "127.0.0.1".equals(origin.getHost());
            if (!production && !test) throw failure("untrusted_endpoint");
            for (URI endpoint : List.of(authorize, token, discovery)) {
                if (!origin.getScheme().equals(endpoint.getScheme()) || !origin.getHost().equals(endpoint.getHost())
                        || origin.getPort() != endpoint.getPort() || endpoint.getUserInfo() != null
                        || endpoint.getQuery() != null || endpoint.getFragment() != null) throw failure("untrusted_endpoint");
            }
            this.origin = origin; this.authorize = authorize; this.token = token; this.discovery = discovery; this.issuer = issuer;
        }
        static Endpoints production() {
            return new Endpoints(URI.create("https://auth.openai.com"), URI.create("https://auth.openai.com/api/accounts/authorize"),
                    URI.create("https://auth.openai.com/api/accounts/oauth/token"), URI.create("https://auth.openai.com/.well-known/openid-configuration"),
                    "https://auth.openai.com");
        }
    }
    // Deliberately not a record: record.toString() would expose credentials.
    private static final class Ticket {
        final String id, accessToken, clientId, refreshToken, pendingIdToken;
        private Ticket(String id, String accessToken, String clientId, String refreshToken, String pendingIdToken) {
            this.id = id; this.accessToken = accessToken; this.clientId = clientId;
            this.refreshToken = refreshToken; this.pendingIdToken = pendingIdToken;
        }
        static Ticket ready(String id, String accessToken) { return new Ticket(id, accessToken, null, null, null); }
        static Ticket refresh(String id, String clientId, String refresh) { return new Ticket(id, null, clientId, refresh, null); }
        static Ticket verify(String id, String clientId, String idToken) { return new Ticket(id, null, clientId, null, idToken); }
    }
    private static final class Attempt {
        final String id, accountId, clientId, state, nonce, pkce, callback;
        final Instant expiresAt;
        final HttpServer listener;
        final long epoch;
        volatile boolean consumed, cancelled;
        Attempt(String id, String accountId, String clientId, String state, String nonce, String pkce, String callback,
                Instant expiresAt, HttpServer listener, long epoch) {
            this.id = id; this.accountId = accountId; this.clientId = clientId; this.state = state; this.nonce = nonce;
            this.pkce = pkce; this.callback = callback; this.expiresAt = expiresAt; this.listener = listener; this.epoch = epoch;
        }
    }
}
