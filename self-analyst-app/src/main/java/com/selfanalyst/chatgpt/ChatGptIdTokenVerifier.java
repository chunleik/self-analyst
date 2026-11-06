package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import static com.selfanalyst.chatgpt.ChatGptOAuthHttp.*;

/** Verifies ID tokens with trusted discovery/JWKS, never with JWT-supplied key URLs. */
final class ChatGptIdTokenVerifier implements ChatGptSessionService.IdTokenVerifier {
    private final ChatGptOAuthHttp http;
    private final URI discovery;
    private final String issuer;
    private final Clock clock;

    ChatGptIdTokenVerifier(ChatGptOAuthHttp http, URI discovery, String issuer, Clock clock) {
        this.http = http;
        this.discovery = discovery;
        this.issuer = issuer;
        this.clock = clock;
    }

    @Override
    public ChatGptSessionService.Identity verify(String token, String clientId, String nonce) {
        try {
            if (token == null || token.length() > 65536) throw failure("invalid_identity");
            String[] pieces = token.split("\\.", -1);
            if (pieces.length != 3) throw failure("invalid_identity");
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonNode header = parse(decoder.decode(pieces[0]));
            if (!"RS256".equals(required(header, "alg")) || header.has("crit")) throw failure("invalid_identity");
            String kid = required(header, "kid");
            JsonNode metadata = fetch(discovery);
            if (!issuer.equals(required(metadata, "issuer"))) throw failure("invalid_identity");
            JsonNode keys = fetch(http.trusted(required(metadata, "jwks_uri"))).path("keys");
            if (!keys.isArray()) throw failure("invalid_identity");
            JsonNode selected = null;
            for (JsonNode key : keys) {
                if (kid.equals(key.path("kid").asText()) && "RSA".equals(key.path("kty").asText())
                        && (!key.has("alg") || "RS256".equals(key.path("alg").asText()))
                        && (!key.has("use") || "sig".equals(key.path("use").asText()))) {
                    if (selected != null) throw failure("invalid_identity");
                    selected = key;
                }
            }
            if (selected == null) throw failure("invalid_identity");
            if (selected.has("key_ops")) {
                boolean verify = false;
                for (JsonNode op : selected.path("key_ops")) verify |= "verify".equals(op.asText());
                if (!verify) throw failure("invalid_identity");
            }
            BigInteger modulus = new BigInteger(1, decoder.decode(required(selected, "n")));
            if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192) throw failure("invalid_identity");
            BigInteger exponent = new BigInteger(1, decoder.decode(required(selected, "e")));
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(pieces[2]))) throw failure("invalid_identity");
            JsonNode claims = parse(decoder.decode(pieces[1]));
            long now = clock.instant().getEpochSecond();
            if (!issuer.equals(required(claims, "iss")) || !claims.path("exp").isIntegralNumber()
                    || claims.path("exp").asLong() <= now
                    || (claims.has("nbf") && (!claims.path("nbf").isIntegralNumber() || claims.path("nbf").asLong() > now + 30))
                    || (claims.has("iat") && (!claims.path("iat").isIntegralNumber() || claims.path("iat").asLong() > now + 30)))
                throw failure("invalid_identity");
            JsonNode aud = claims.path("aud");
            boolean audience = aud.isTextual() && clientId.equals(aud.asText());
            if (aud.isArray()) {
                for (JsonNode item : aud) audience |= item.isTextual() && clientId.equals(item.asText());
                if (aud.size() > 1 && !clientId.equals(claims.path("azp").asText())) throw failure("invalid_identity");
            }
            if (!audience || (claims.has("azp") && !clientId.equals(claims.path("azp").asText()))) throw failure("invalid_identity");
            if (nonce != null && !constantEquals(nonce, required(claims, "nonce"))) throw failure("invalid_identity");
            String subject = required(claims, "sub");
            String email = claims.path("email").isTextual() ? claims.path("email").asText() : "";
            if (subject.length() > 1024 || email.length() > 320) throw failure("invalid_identity");
            return new ChatGptSessionService.Identity(subject, email);
        } catch (KeysUnavailable e) {
            // Transient: the token may be valid, so callers must keep credentials and retry later.
            throw failure("identity_unavailable");
        } catch (Exception e) {
            // Neither the token, remote body, nor nested exception is allowed across this boundary.
            throw failure("invalid_identity");
        }
    }

    /** Only transport/response failures of the trusted metadata or JWKS fetch are "unavailable". */
    private JsonNode fetch(URI uri) {
        try { return http.get(uri); }
        catch (RuntimeException e) { throw new KeysUnavailable(); }
    }

    private static final class KeysUnavailable extends RuntimeException {
        private KeysUnavailable() { super(null, null, false, false); }
    }

    static boolean constantEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
