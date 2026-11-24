package com.selfanalyst.mail;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

/** Dependency/service loading only; no connection, credential or package build. */
class MailProviderServiceLoadingTest {
    @Test void packagedProbeAlsoLoadsMimeActivationWithoutNetwork() throws Exception {
        MailRuntimeCheck.verify();
    }
    @Test void runtimeClasspathFindsImapsProviderWithoutNetwork() throws Exception {
        var session=Session.getInstance(new Properties());
        var provider=session.getProvider("imaps");
        assertEquals("org.eclipse.angus.mail.imap.IMAPSSLStore",provider.getClassName());
        var store=session.getStore("imaps");
        assertInstanceOf(org.eclipse.angus.mail.imap.IMAPSSLStore.class,store);
        assertFalse(store.isConnected());
        store.close();
    }
}
