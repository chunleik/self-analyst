package com.selfanalyst.mail;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.URLName;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchException;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.Utility;
import org.eclipse.angus.mail.imap.ResyncData;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.MailboxInfo;
import org.eclipse.angus.mail.imap.protocol.ListInfo;

import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Angus is responsible for IMAP encoding. All production endpoints and transport properties are fixed here. */
public final class AngusMailTransport implements MailTransport {
    @FunctionalInterface interface StoreConnector {
        IMAPStore connect(MailAccount account, char[] secret, MailCancellation cancellation, MailLimits limits,
                          MailSocketFactory sockets) throws MessagingException;
    }
    private final StoreConnector connector;
    public AngusMailTransport() { this(AngusMailTransport::connectTls); }
    // Package-private injection exists only for synthetic protocol fixtures, not application configuration.
    AngusMailTransport(StoreConnector connector) { this.connector = connector; }
    static Properties properties(MailLimits limits, MailSocketFactory sockets) {
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.ssl.enable", "true");
        properties.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        properties.setProperty("mail.imaps.ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.setProperty("mail.imaps.ssl.socketFactory.fallback", "false");
        properties.put("mail.imaps.ssl.socketFactory", sockets);
        properties.setProperty("mail.imaps.connectiontimeout", "10000");
        properties.setProperty("mail.imaps.timeout", String.valueOf(Math.min(10000, limits.timeout().toMillis())));
        properties.setProperty("mail.imaps.writetimeout", "10000");
        properties.setProperty("mail.imaps.peek", "true");
        properties.setProperty("mail.imaps.partialfetch", "true");
        properties.setProperty("mail.imaps.fetchsize", "8192");
        properties.setProperty("mail.imaps.connectionpoolsize", "1");
        properties.setProperty("mail.imaps.separatestoreconnection", "false");
        properties.setProperty("mail.imaps.connectionpooltimeout", "1000");
        properties.setProperty("mail.imaps.closefoldersonstorefailure", "true");
        // Disable Angus' otherwise automatic local-search fallback (it could fetch unbounded bodies).
        properties.setProperty("mail.imaps.throwsearchexception", "true");
        properties.setProperty("mail.imaps.allowreadonlyselect", "false");
        properties.setProperty("mail.debug", "false");
        properties.setProperty("mail.debug.auth", "false");
        return properties;
    }
    private static IMAPStore connectTls(MailAccount account, char[] secret, MailCancellation cancellation,
                                         MailLimits limits, MailSocketFactory sockets) throws MessagingException {
        Session session = Session.getInstance(properties(limits, sockets));
        IMAPStore store = createStore(session, "imaps", true, account.provider() == MailAccount.Provider.NETEASE_163);
        try {
            cancellation.check();
            store.connect(account.provider().host(), account.provider().port(), account.email(), new String(secret));
            return store;
        } catch (Exception e) {
            sockets.close();
            try { store.close(); } catch (MessagingException ignored) { }
            if (e instanceof MessagingException messaging) throw messaging;
            throw e;
        }
    }
    static IMAPStore createStore(Session session, String protocolName, boolean ssl, boolean identityRequired) {
        return new IMAPStore(session, new URLName(protocolName, null, -1, null, null, null), protocolName, ssl) {
            @Override protected IMAPFolder newIMAPFolder(String name, char separator, Boolean namespace) { return new SafeFolder(name, separator, this, namespace); }
            @Override protected IMAPFolder newIMAPFolder(String name, char separator) { return new SafeFolder(name, separator, this, null); }
            @Override protected IMAPFolder newIMAPFolder(ListInfo info) { return new SafeFolder(info, this); }
            @Override protected IMAPProtocol newIMAPProtocol(String host, int port) throws java.io.IOException, ProtocolException {
                return new IMAPProtocol(protocolName, host, port, session.getProperties(), ssl, logger) {
                    @Override public MailboxInfo examine(String mailbox, ResyncData resync) throws ProtocolException {
                        // ID belongs to the authenticated physical connection, not just the logical Store.
                        if (identityRequired) {
                            if (!hasCapability("ID")) throw new MailException(MailException.Code.PROVIDER_RESTRICTED);
                            id(Map.of("name", "SelfAnalyst", "version", applicationVersion(), "vendor", "SelfAnalyst"));
                        }
                        MailboxInfo info = super.examine(mailbox, resync);
                        if (info.mode != Folder.READ_ONLY) { disconnect(); throw new MailException(MailException.Code.ACCESS_DENIED); }
                        // Angus eagerly allocates its sequence cache after EXAMINE; bound this before allocation.
                        if (info.total < 0 || info.total > 100_000) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                        if (info.uidvalidity < 1 || info.uidvalidity > 0xffff_ffffL || info.uidnext < 1 || info.uidnext > 0x1_0000_0000L) {
                            throw new MailException(MailException.Code.PROTOCOL_UNAVAILABLE);
                        }
                        return info;
                    }
                    @Override public MailboxInfo select(String mailbox, ResyncData resync) {
                        throw new MailException(MailException.Code.ACCESS_DENIED);
                    }
                    @Override public void close() { disconnect(); }
                };
            }
        };
    }
    @Override public Connection connect(MailAccount account, char[] authorizationCode, MailCancellation cancellation, MailLimits limits) {
        if (authorizationCode == null || authorizationCode.length == 0 || authorizationCode.length > 256) {
            throw new MailException(MailException.Code.AUTHENTICATION_REQUIRED);
        }
        cancellation.check();
        MailSocketFactory sockets = new MailSocketFactory(cancellation, limits.maxWireBytes());
        IMAPStore store = null;
        try {
            store = connector.connect(account, authorizationCode, cancellation, limits, sockets);
            cancellation.check();
            if (account.provider() == MailAccount.Provider.NETEASE_163) {
                if (!store.hasCapability("ID")) throw new MailException(MailException.Code.PROVIDER_RESTRICTED);
                store.id(Map.of("name", "SelfAnalyst", "version", applicationVersion(), "vendor", "SelfAnalyst"));
            }
            return new AngusConnection(store, sockets, cancellation);
        } catch (Exception e) {
            sockets.close();
            if (store != null) try { store.close(); } catch (MessagingException ignored) { }
            cancellation.check();
            throw classify(e);
        }
    }
    private static String applicationVersion() {
        String version = AngusMailTransport.class.getPackage().getImplementationVersion();
        if (version != null) return version;
        try (InputStream stream = AngusMailTransport.class.getResourceAsStream("/META-INF/maven/com.selfanalyst/self-analyst-app/pom.properties")) {
            if (stream != null) { Properties p = new Properties(); p.load(stream); return p.getProperty("version", "development"); }
        } catch (Exception ignored) { }
        return "development";
    }
    private static final class AngusConnection implements Connection {
        private final IMAPStore store;
        private final MailSocketFactory sockets;
        private final MailCancellation cancellation;
        AngusConnection(IMAPStore store, MailSocketFactory sockets, MailCancellation cancellation) {
            this.store = store; this.sockets = sockets; this.cancellation = cancellation;
        }
        @Override public List<String> listFolders() {
            return listFolderDetails().stream().map(MailTransport.FolderInfo::name).toList();
        }
        @Override public List<MailTransport.FolderInfo> listFolderDetails() {
            try {
                cancellation.check();
                Folder[] folders = store.getDefaultFolder().list("*");
                if (folders.length > MailLimits.MAX_LISTED_FOLDERS) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                List<MailTransport.FolderInfo> result = new ArrayList<>();
                for (Folder folder : folders) if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                    cancellation.check();
                    if (folder.getFullName().length() > MailLimits.MAX_FOLDER_NAME_LENGTH) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                    char separator = folder.getSeparator();
                    result.add(new MailTransport.FolderInfo(folder.getFullName(), separator == 0 ? "" : String.valueOf(separator), true));
                }
                return List.copyOf(result);
            } catch (Exception e) { cancellation.check(); throw classify(e); }
        }
        @Override public Mailbox openReadOnly(String folder) {
            IMAPFolder mailbox = null;
            try {
                cancellation.check();
                mailbox = (IMAPFolder) store.getFolder(folder);
                mailbox.open(Folder.READ_ONLY);
                if (mailbox.getMode() != Folder.READ_ONLY) throw new MailException(MailException.Code.ACCESS_DENIED);
                return new AngusMailbox(mailbox, cancellation);
            } catch (Exception e) {
                if (mailbox != null && mailbox.isOpen()) try { mailbox.forceClose(); } catch (Exception ignored) { }
                cancellation.check(); throw classify(e);
            }
        }
        @Override public void close() {
            // Close sockets first: no EXPUNGE/CLOSE commands can escape through error cleanup.
            sockets.close();
            try { store.close(); } catch (MessagingException ignored) { }
        }
    }
    private static final class SafeFolder extends IMAPFolder {
        SafeFolder(String name, char separator, IMAPStore store, Boolean namespace) { super(name, separator, store, namespace); }
        SafeFolder(ListInfo info, IMAPStore store) { super(info, store); }
        Message[] searchBounded(SearchTerm term, Message[] candidates, int maxExamined) throws MessagingException {
            return (Message[]) doCommand(protocol -> {
                // IMAPFolder.search(term, candidates) ignores throwsearchexception in Angus 2.0.5.
                // Use the SDK typed SEARCH API under its message-cache lock, without its local fallback.
                try {
                    var sequenceSet = Utility.toMessageSetSorted(candidates, null);
                    if (sequenceSet == null) throw new MailException(MailException.Code.STALE_REFERENCE);
                    int[] matching = protocol.search(sequenceSet, term);
                    if (matching.length > maxExamined) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                    return getMessagesBySeqNumbers(matching);
                } catch (CommandFailedException | SearchException unsupported) {
                    throw new MailException(MailException.Code.SEARCH_UNSUPPORTED);
                }
            });
        }
    }
    private static final class AngusMailbox implements Mailbox {
        private final IMAPFolder folder;
        private final MailCancellation cancellation;
        AngusMailbox(IMAPFolder folder, MailCancellation cancellation) { this.folder = folder; this.cancellation = cancellation; }
        @Override public long uidValidity() {
            try { cancellation.check(); return folder.getUIDValidity(); }
            catch (Exception e) { cancellation.check(); throw classify(e); }
        }
        @Override public SearchBatch search(ReadOnlyMailService.SearchRequest request, Long beforeUid, int maxExamined) {
            try {
                cancellation.check();
                long next = folder.getUIDNext();
                if (next < 1) throw new MailException(MailException.Code.PROTOCOL_UNAVAILABLE);
                long upper = Math.min(next - 1, beforeUid == null ? next - 1 : beforeUid - 1);
                if (upper < 1) return new SearchBatch(List.of(), 1, 0, false);
                long lower = Math.max(1, upper - maxExamined + 1);
                Message[] candidates = folder.getMessagesByUID(lower, upper);
                if (candidates.length > maxExamined) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                SearchTerm term = terms(request);
                Message[] matches = candidates.length == 0 || term == null ? candidates
                        : ((SafeFolder) folder).searchBounded(term, candidates, maxExamined);
                if (matches.length > maxExamined) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                FetchProfile profile = new FetchProfile();
                profile.add(FetchProfile.Item.ENVELOPE); profile.add(FetchProfile.Item.FLAGS);
                profile.add(FetchProfile.Item.SIZE); profile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(matches, profile);
                List<Envelope> result = new ArrayList<>();
                for (Message message : matches) {
                    Envelope envelope = envelope(message);
                    // Server date search has day granularity. Enforce exact requested instants locally on envelopes.
                    if (request.after() != null && (envelope.date() == null || envelope.date().isBefore(request.after()))) continue;
                    if (request.before() != null && (envelope.date() == null || !envelope.date().isBefore(request.before()))) continue;
                    if (envelope.uid() < lower || envelope.uid() > upper) throw new MailException(MailException.Code.PROTOCOL_UNAVAILABLE);
                    result.add(envelope);
                }
                result.sort(Comparator.comparingLong(Envelope::uid).reversed());
                return new SearchBatch(List.copyOf(result), lower, candidates.length, lower > 1);
            } catch (Exception e) { cancellation.check(); throw classify(e); }
        }
        @Override public Envelope envelope(long uid) {
            try {
                cancellation.check();
                Message message = folder.getMessageByUID(uid);
                return message == null || message.isExpunged() ? null : envelope(message);
            } catch (Exception e) { cancellation.check(); throw classify(e); }
        }
        private Envelope envelope(Message message) throws MessagingException {
            Date received = message.getReceivedDate();
            String messageId = message instanceof IMAPMessage imap ? imap.getMessageID() : null;
            return new Envelope(folder.getUID(message), MailBodyReader.bounded(message.getSubject(), 512),
                    addresses(message.getFrom()), addresses(message.getRecipients(Message.RecipientType.TO)),
                    received == null ? null : received.toInstant(), !message.isSet(Flags.Flag.SEEN),
                    MailBodyReader.bounded(messageId, 256), message.getSize());
        }
        private static List<String> addresses(Address[] addresses) {
            if (addresses == null) return List.of();
            return Arrays.stream(addresses).limit(20).map(a -> MailBodyReader.bounded(a.toString(), 256)).toList();
        }
        @Override public MailBodyReader.Body readBody(long uid, MailLimits limits) {
            try {
                cancellation.check();
                Message message = folder.getMessageByUID(uid);
                if (!(message instanceof IMAPMessage imap) || message.isExpunged()) throw new MailException(MailException.Code.STALE_REFERENCE);
                imap.setPeek(true);
                MailBodyReader.Body result = new MailBodyReader(limits).read(imap);
                cancellation.check();
                return result;
            } catch (Exception e) { cancellation.check(); throw classify(e); }
        }
        @Override public void close() { try { if (folder.isOpen()) folder.forceClose(); } catch (Exception ignored) { } }
    }
    private static SearchTerm terms(ReadOnlyMailService.SearchRequest request) {
        List<SearchTerm> terms = new ArrayList<>();
        if (request.after() != null) terms.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(request.after())));
        // The server uses local calendar dates. Include the full final day then enforce exact bounds above.
        if (request.before() != null) terms.add(new ReceivedDateTerm(ComparisonTerm.LE, Date.from(request.before())));
        if (request.from() != null && !request.from().isBlank()) terms.add(new FromStringTerm(request.from()));
        if (request.subject() != null && !request.subject().isBlank()) terms.add(new SubjectTerm(request.subject()));
        if (request.unread() != null) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), !request.unread()));
        return terms.isEmpty() ? null : terms.size() == 1 ? terms.getFirst() : new AndTerm(terms.toArray(SearchTerm[]::new));
    }
    static MailException classify(Throwable failure) {
        boolean auth = false;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof MailException m) return m;
            if (current instanceof SSLException || current instanceof java.security.cert.CertificateException) return new MailException(MailException.Code.TLS_FAILURE);
            if (current instanceof SocketTimeoutException) return new MailException(MailException.Code.TIMEOUT);
            if (current instanceof SearchException) return new MailException(MailException.Code.SEARCH_UNSUPPORTED);
            if (current instanceof AuthenticationFailedException) auth = true;
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("unsafe login") || message.contains("id command")) return new MailException(MailException.Code.PROVIDER_RESTRICTED);
            if (message.contains("badcharset")) return new MailException(MailException.Code.SEARCH_UNSUPPORTED);
            if (message.contains("mail_wire_budget_exceeded")) return new MailException(MailException.Code.LIMIT_EXCEEDED);
            current = current.getCause() != null ? current.getCause()
                    : current instanceof MessagingException m ? m.getNextException() : null;
        }
        return new MailException(auth ? MailException.Code.AUTHENTICATION_FAILED : MailException.Code.CONNECTION_FAILED);
    }
}
