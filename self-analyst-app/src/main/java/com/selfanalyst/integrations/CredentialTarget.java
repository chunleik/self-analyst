package com.selfanalyst.integrations;

import java.util.Locale;

/** Connection identity plus exact receiving endpoint, never a user-controlled vault item name. */
public record CredentialTarget(String connectionId, String recipient) {
    public CredentialTarget {
        if (!IntegrationConfig.LEGACY_WEBSEARCH_ID.equals(connectionId)) IntegrationConfig.validateId(connectionId);
        IntegrationConfig.text(recipient, 4096, "invalid_credential_target");
    }
    public static CredentialTarget mcp(String id, String endpoint) {
        return new CredentialTarget(id, PublicEndpointPolicy.validate(endpoint).toASCIIString());
    }
    public static CredentialTarget mail(String id, String provider, String email) {
        if (!"qq".equals(provider) && !"163".equals(provider)) throw new IllegalArgumentException("unsupported_provider");
        String host = provider.equals("qq") ? "imap.qq.com" : "imap.163.com";
        return new CredentialTarget(id, "imaps://" + host + ":993/" + email.toLowerCase(Locale.ROOT));
    }
}
