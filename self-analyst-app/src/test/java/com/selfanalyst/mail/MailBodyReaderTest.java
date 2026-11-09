package com.selfanalyst.mail;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class MailBodyReaderTest {
    private static MailLimits limits(int points, int bytes, int parts, int depth) { return new MailLimits(500, points, bytes, parts, depth, 100_000, Duration.ofSeconds(5)); }
    @Test void prefersPlainTextAndNeverOpensAttachmentStreams() throws Exception {
        MimeBodyPart plain = new MimeBodyPart(); plain.setText("Plain content", "UTF-8");
        MimeBodyPart html = new MimeBodyPart(); html.setContent("<p>HTML duplicate</p>", "text/html");
        MimeBodyPart attachment = new MimeBodyPart() {
            @Override public InputStream getInputStream() { fail("Attachment stream must never be opened"); return null; }
            @Override public Object getContent() { fail("Attachment content must never be opened"); return null; }
            @Override public int getSize() { return Integer.MAX_VALUE; }
        };
        attachment.setFileName("large.bin"); attachment.setDisposition(Part.ATTACHMENT);
        MimeMultipart parts = new MimeMultipart("mixed"); parts.addBodyPart(plain); parts.addBodyPart(html); parts.addBodyPart(attachment);
        MimeMessage root = new MimeMessage(Session.getInstance(new Properties())); root.setContent(parts); root.saveChanges();
        var body = new MailBodyReader(MailLimits.defaults()).read(root);
        assertEquals("Plain content", body.text());
        assertEquals(1, body.attachments().size());
        assertEquals(Integer.MAX_VALUE, body.attachments().getFirst().size());
        assertFalse(body.truncated());
    }
    @Test void htmlDoesNotExecuteOrKeepScriptsAndRemoteResources() throws Exception {
        var body = readRaw("Content-Type: text/html; charset=UTF-8\r\n\r\n<p>Hello</p><script>steal()</script><style>private</style><img src='https://tracker.invalid/pixel'><iframe src='https://tracker.invalid'></iframe>", MailLimits.defaults());
        assertEquals("Hello", body.text());
        assertFalse(body.text().contains("tracker"));
    }
    @Test void decodedBytesAndUnicodePointsAreSeparatelyBounded() throws Exception {
        String text = "😀".repeat(40);
        String encoded = java.util.Base64.getMimeEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        var body = readRaw("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + encoded, limits(10, 80, 8, 5));
        assertEquals(10, body.text().codePointCount(0, body.text().length()));
        assertTrue(body.truncated());
        assertTrue(body.limitations().contains("decoded_byte_budget"));
        assertTrue(body.limitations().contains("character_budget"));
    }
    @Test void mimeDepthAndPartCountsBoundTraversal() throws Exception {
        MimeBodyPart leaf = new MimeBodyPart(); leaf.setText("deep");
        for (int i = 0; i < 8; i++) { MimeMultipart parts = new MimeMultipart(); parts.addBodyPart(leaf); MimeBodyPart next = new MimeBodyPart(); next.setContent(parts); leaf = next; }
        MimeMessage saved = new MimeMessage(Session.getInstance(new Properties()));
        saved.setContent((MimeMultipart) leaf.getContent()); saved.saveChanges();
        var body = new MailBodyReader(limits(100, 100, 5, 2)).read(saved);
        assertTrue(body.truncated());
        assertTrue(body.limitations().contains("mime_depth_budget"));
        MimeMultipart many = new MimeMultipart();
        for (int i = 0; i < 10; i++) { MimeBodyPart p = new MimeBodyPart(); p.setText("piece"); many.addBodyPart(p); }
        MimeBodyPart root = new MimeBodyPart(); root.setContent(many);
        saved = new MimeMessage(Session.getInstance(new Properties()));
        saved.setContent(many); saved.saveChanges();
        body = new MailBodyReader(limits(100, 100, 3, 10)).read(saved);
        assertTrue(body.limitations().contains("mime_part_budget"));
    }
    @Test void encodedPartTooLargeIsNotOpened() throws Exception {
        MimeBodyPart large = new MimeBodyPart() {
            @Override public int getSize() { return 5_000_000; }
            @Override public InputStream getInputStream() { fail("Over-budget part must not be opened"); return null; }
        };
        large.setHeader("Content-Type", "text/plain");
        var body = new MailBodyReader(MailLimits.defaults()).read(large);
        assertTrue(body.truncated()); assertEquals("", body.text());
    }
    private static MailBodyReader.Body readRaw(String raw, MailLimits limits) throws Exception {
        return new MailBodyReader(limits).read(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
    }
}
