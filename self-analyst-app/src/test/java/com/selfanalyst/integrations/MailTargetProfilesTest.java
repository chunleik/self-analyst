package com.selfanalyst.integrations;

import com.selfanalyst.mail.MailAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailTargetProfilesTest {
    @Test
    void approvedProviderProfilesSeparateAccountIdentityAndImapFromSmtp() {
        for (var provider : MailAccount.Provider.values()) {
            String email = "fixture@" + provider.domain();
            var row = new IntegrationConfig.MailAccount("profile", provider.id(), email, "Synthetic", false,
                    "all", List.of(), false, null, "session", 0);
            assertSame(provider, row.profile());
            assertEquals("imaps://" + provider.host() + ":993/" + email, row.credentialTarget().recipient());
            var smtp = CredentialTarget.mailSmtp("profile", provider.id(), email);
            assertEquals("smtps://" + provider.smtpHost() + ":465/" + email, smtp.recipient());
            assertNotEquals(row.credentialTarget(), smtp);
            assertNotEquals(CredentialTarget.mailSmtp("another", provider.id(), email), smtp);
        }
        assertThrows(IllegalArgumentException.class, () -> CredentialTarget.mail("profile", "126", "synthetic@163.com"));
        assertThrows(IllegalArgumentException.class, () -> CredentialTarget.mailSmtp("profile", "gmail", "synthetic@googlemail.com"));
    }
}
