package com.selfanalyst.mail;

import java.time.Instant;
import java.util.List;

/** Read-only transport surface: no raw commands, writes, SMTP or attachment-content methods. */
public interface MailTransport {
    Connection connect(MailAccount account, char[] authorizationCode, MailCancellation cancellation, MailLimits limits);
    interface Connection extends AutoCloseable {
        List<String> listFolders();
        Mailbox openReadOnly(String folder);
        @Override void close();
    }
    interface Mailbox extends AutoCloseable {
        long uidValidity();
        SearchBatch search(ReadOnlyMailService.SearchRequest request, Long beforeUid, int maxExamined);
        Envelope envelope(long uid);
        MailBodyReader.Body readBody(long uid, MailLimits limits);
        @Override void close();
    }
    record Envelope(long uid, String subject, List<String> from, List<String> to, Instant date,
                    boolean unread, String messageId, int size) { }
    record SearchBatch(List<Envelope> messages, long lowestExaminedUid, int examined, boolean moreAvailable) { }
}
