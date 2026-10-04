package com.selfanalyst.ontology;

import java.net.URI;
import java.util.*;

/** Contains only public configuration. The password is never a configuration value. */
public record Neo4jSyncConfig(boolean enabled, String uri, String database, String username,
                              String passwordEnv, String namespace, int timeoutSeconds) {
    public Neo4jSyncConfig {
        uri = clean(uri); database = clean(database); username = clean(username);
        passwordEnv = clean(passwordEnv); namespace = clean(namespace);
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    public static Neo4jSyncConfig disabled() {
        return new Neo4jSyncConfig(false, "", "neo4j", "neo4j", "SELF_ANALYST_NEO4J_PASSWORD", "", 15);
    }
    public void validate() {
        try {
            URI target = URI.create(uri);
            String scheme = target.getScheme();
            String host = target.getHost();
            if (scheme == null || host == null || target.getRawUserInfo() != null
                    || target.getRawQuery() != null || target.getRawFragment() != null
                    || !(target.getRawPath() == null || target.getRawPath().isEmpty())
                    || target.getPort() == 0 || target.getPort() > 65535) throw new IllegalArgumentException();
            boolean tls = Set.of("bolt+s", "neo4j+s").contains(scheme);
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host.toLowerCase(Locale.ROOT));
            if (!tls && !(local && "bolt".equals(scheme))) throw new IllegalArgumentException();
            if (!database.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,62}") || database.equalsIgnoreCase("system")
                    || username.isBlank() || username.length() > 128 || username.chars().anyMatch(Character::isISOControl)
                    || !passwordEnv.matches("[A-Z_][A-Z0-9_]{0,127}")
                    || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || timeoutSeconds < 1 || timeoutSeconds > 120) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            // Never include the untrusted URI, which may accidentally contain credentials.
            throw new IllegalArgumentException("neo4j.invalidConfig");
        }
    }
    public Map<String, Object> publicTarget() {
        // Invalid targets are not echoed: a malformed URI might contain a password.
        String safeUri = "";
        try { validate(); safeUri = uri; } catch (IllegalArgumentException ignored) { }
        return Map.of("enabled", enabled, "uri", safeUri, "database", database, "username", username,
                "passwordEnv", passwordEnv, "namespace", namespace, "timeoutSeconds", timeoutSeconds);
    }
    public String fingerprint() {
        // An invalid URI can contain an accidentally pasted password: never hash it for public status.
        try { validate(); } catch (IllegalArgumentException invalid) { return ""; }
        return Ontology.stableId("neo4j-target", enabled + "\n" + uri + "\n" + database + "\n"
                + username + "\n" + passwordEnv + "\n" + namespace + "\n" + timeoutSeconds);
    }
    @Override public String toString() { return "Neo4jSyncConfig[enabled=" + enabled + "]"; }
}
