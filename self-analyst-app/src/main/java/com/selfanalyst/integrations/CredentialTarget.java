package com.selfanalyst.integrations;

import com.selfanalyst.mail.MailAccount.Provider;

/** Connection identity plus exact receiving endpoint, never a user-controlled vault item name. */
public record CredentialTarget(String connectionId, String recipient) {
    public CredentialTarget {
        IntegrationConfig.validateId(connectionId);
        IntegrationConfig.text(recipient, 4096, "invalid_credential_target");
    }
    public static CredentialTarget mcp(String id, String endpoint) {
        return new CredentialTarget(id, PublicEndpointPolicy.validate(endpoint).toASCIIString());
    }
    public static CredentialTarget mail(String id, String provider, String email) {
        Provider profile = Provider.fromId(provider);
        return new CredentialTarget(id, "imaps://" + profile.host() + ":" + profile.port() + "/" + profile.bindingEmail(email));
    }
    public static CredentialTarget mailSmtp(String id, String provider, String email) {
        Provider profile = Provider.fromId(provider);
        return new CredentialTarget(id, "smtps://" + profile.smtpHost() + ":" + profile.smtpPort() + "/" + profile.bindingEmail(email));
    }
}
