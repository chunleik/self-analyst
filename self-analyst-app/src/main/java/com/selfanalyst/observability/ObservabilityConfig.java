package com.selfanalyst.observability;

import java.util.*;

/** Public settings only. An enable decision is deliberately never persisted in this record. */
public record ObservabilityConfig(
        int schemaVersion,
        long revision,
        String endpoint,
        String publicKey,
        double sampleRate,
        String captureMode,
        Set<String> categories,
        Set<String> toolSources,
        Set<String> mailAccounts,
        String scope,
        String sessionId,
        String caCertificatePem) {
    public static final Set<String> CATEGORIES =
            Set.of("modelText", "toolText", "toolCatalog", "mailHeaders", "mailBody");

    public ObservabilityConfig {
        if (schemaVersion != 1
                || revision < 0
                || !Double.isFinite(sampleRate)
                || sampleRate < 0
                || sampleRate > 1) throw new IllegalArgumentException("invalid_config");
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(publicKey);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(caCertificatePem);
        if (!endpoint.isEmpty())
            endpoint = ObservabilityEndpointPolicy.normalize(endpoint).toASCIIString();
        if (publicKey.length() > 256
                || publicKey.contains(":")
                || publicKey.chars().anyMatch(c -> c < 33 || c > 126)
                || sessionId.length() > 128
                || sessionId.chars().anyMatch(Character::isISOControl)
                || caCertificatePem.length() > 32768)
            throw new IllegalArgumentException("invalid_config");
        if (!Set.of("metadata", "content").contains(captureMode)
                || !Set.of("session", "all-runtime").contains(scope))
            throw new IllegalArgumentException("invalid_config");
        categories = Set.copyOf(categories);
        toolSources = Set.copyOf(toolSources);
        mailAccounts = Set.copyOf(mailAccounts);
        if (!CATEGORIES.containsAll(categories)
                || toolSources.size() > 64
                || mailAccounts.size() > 16) throw new IllegalArgumentException("invalid_config");
        for (String id : toolSources) validateId(id);
        for (String id : mailAccounts) validateId(id);
        if (!caCertificatePem.isBlank()) ObservabilityEndpointPolicy.sslContext(caCertificatePem);
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IllegalArgumentException("invalid_source");
    }

    public static ObservabilityConfig defaults() {
        return new ObservabilityConfig(
                1, 0, "", "", 1, "metadata", Set.of(), Set.of(), Set.of(), "all-runtime", "", "");
    }

    public ObservabilityConfig revision(long next) {
        return new ObservabilityConfig(
                schemaVersion,
                next,
                endpoint,
                publicKey,
                sampleRate,
                captureMode,
                categories,
                toolSources,
                mailAccounts,
                scope,
                sessionId,
                caCertificatePem);
    }

    public void validateActivation() {
        if (endpoint.isBlank()
                || publicKey.isBlank()
                || scope.equals("session") && sessionId.isBlank()
                || captureMode.equals("content") && categories.isEmpty())
            throw new IllegalArgumentException("incomplete_config");
    }
}
