package com.selfanalyst.mail;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.imap.IMAPSSLStore;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Offline packaged-runtime probe. Never connects or loads application/user configuration. */
public final class MailRuntimeCheck {
    private MailRuntimeCheck() {}

    public static void main(String[] args) throws Exception {
        verify();
        System.out.println("MAIL_RUNTIME_CHECK_OK");
    }

    public static void verify() throws Exception {
        Session session = Session.getInstance(new Properties());
        if (!IMAPSSLStore.class.getName().equals(session.getProvider("imaps").getClassName()))
            throw new IllegalStateException("Unexpected IMAPS provider");
        try (var store = session.getStore("imaps")) {
            if (!(store instanceof IMAPSSLStore) || store.isConnected())
                throw new IllegalStateException("IMAPS service loading failed");
        }
        String fixture = "MIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=probe\r\n\r\n"
                + "--probe\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nsynthetic plain\r\n"
                + "--probe\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>synthetic html</p>\r\n"
                + "--probe--\r\n";
        var message = new MimeMessage(session,
                new ByteArrayInputStream(fixture.getBytes(StandardCharsets.UTF_8)));
        if (!(message.getContent() instanceof Multipart parts) || parts.getCount() != 2
                || !"synthetic plain".equals(parts.getBodyPart(0).getContent())
                || !"<p>synthetic html</p>".equals(parts.getBodyPart(1).getContent()))
            throw new IllegalStateException("MIME activation service loading failed");
    }
}
