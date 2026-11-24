package com.selfanalyst.mail;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import org.jsoup.Jsoup;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Walk metadata first. Only selected text parts are streamed; attachment streams are never opened. */
public final class MailBodyReader {
    public record Attachment(String filename, String contentType, int size, boolean inline) { }
    public record Body(String text, boolean truncated, List<Attachment> attachments, List<String> limitations) { }
    private record Candidate(Part part, boolean html) { }
    private final MailLimits limits;
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<String> limitations = new ArrayList<>();
    private int parts;
    private int decodedBytes;
    private boolean truncated;

    public MailBodyReader(MailLimits limits) { this.limits = limits; }
    public Body read(Part root) {
        try {
            collect(root, 0);
            boolean plainAvailable = candidates.stream().anyMatch(c -> !c.html());
            StringBuilder text = new StringBuilder();
            for (Candidate candidate : candidates) {
                if (plainAvailable && candidate.html()) continue;
                if (decodedBytes >= limits.maxDecodedBytes() || text.codePointCount(0, text.length()) >= limits.maxBodyCodePoints()) {
                    limit("body_budget"); break;
                }
                int size = candidate.part().getSize();
                if (size > limits.maxWireBytes()) { limit("encoded_part_too_large"); continue; }
                int available = limits.maxDecodedBytes() - decodedBytes;
                byte[] bytes;
                try (InputStream stream = candidate.part().getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] block = new byte[Math.min(8192, available)];
                    while (buffer.size() < available) {
                        int count = stream.read(block, 0, Math.min(block.length, available - buffer.size()));
                        if (count < 0) break;
                        if (count == 0) continue;
                        buffer.write(block, 0, count);
                    }
                    if (buffer.size() == available && stream.read() != -1) limit("decoded_byte_budget");
                    bytes = buffer.toByteArray();
                }
                decodedBytes += bytes.length;
                String value = new String(bytes, charset(candidate.part()));
                if (candidate.html()) {
                    var html = Jsoup.parse(value);
                    html.select("script,style,noscript,template,iframe,object,embed,head,svg,math").remove();
                    value = html.text();
                }
                if (!text.isEmpty() && !value.isBlank()) text.append('\n');
                int remaining = limits.maxBodyCodePoints() - text.codePointCount(0, text.length());
                if (value.codePointCount(0, value.length()) > remaining) {
                    value = value.substring(0, value.offsetByCodePoints(0, Math.max(0, remaining)));
                    limit("character_budget");
                }
                text.append(value);
            }
            return new Body(text.toString(), truncated, List.copyOf(attachments), List.copyOf(limitations));
        } catch (MailException e) { throw e; }
        catch (Exception e) { throw AngusMailTransport.classify(e); }
    }
    private void collect(Part part, int depth) throws Exception {
        if (++parts > limits.maxMimeParts()) { limit("mime_part_budget"); return; }
        if (depth > limits.maxMimeDepth()) { limit("mime_depth_budget"); return; }
        String filename = part.getFileName();
        String disposition = part.getDisposition();
        String type = baseType(part.getContentType());
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || filename != null
                || part.isMimeType("message/rfc822") || (!part.isMimeType("multipart/*") && !part.isMimeType("text/plain") && !part.isMimeType("text/html"));
        if (attachment) {
            attachments.add(new Attachment(bounded(filename, 256), bounded(type, 128), part.getSize(), Part.INLINE.equalsIgnoreCase(disposition)));
            return;
        }
        if (part.isMimeType("multipart/*")) {
            // Angus returns a BODYSTRUCTURE-backed Multipart; this does not fetch child bodies.
            Object content = part.getContent();
            if (!(content instanceof Multipart multipart)) { limit("unsupported_mime"); return; }
            int count = multipart.getCount();
            for (int i = 0; i < count; i++) {
                if (parts >= limits.maxMimeParts()) { limit("mime_part_budget"); break; }
                collect(multipart.getBodyPart(i), depth + 1);
            }
        } else if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            candidates.add(new Candidate(part, part.isMimeType("text/html")));
        }
    }
    private Charset charset(Part part) {
        try {
            String charset = new ContentType(part.getContentType()).getParameter("charset");
            return charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset);
        } catch (Exception e) { limitations.add("unknown_charset_utf8_fallback"); return StandardCharsets.UTF_8; }
    }
    private static String baseType(String value) {
        try { return new ContentType(value).getBaseType(); }
        catch (Exception e) { return "application/octet-stream"; }
    }
    private void limit(String reason) { truncated = true; if (!limitations.contains(reason)) limitations.add(reason); }
    static String bounded(String value, int maxCodePoints) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return clean.codePointCount(0, clean.length()) <= maxCodePoints ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, maxCodePoints));
    }
}
