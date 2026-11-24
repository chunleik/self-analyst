package com.selfanalyst.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.SendFailedException;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.BadCommandException;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.Literal;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.eclipse.angus.mail.imap.protocol.ListInfo;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;

import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A separate, fixed-endpoint transport. Read-only transport interfaces and protocol paths never enter here. */
final class AngusMailWriteTransport implements MailWriteTransport {
    private static final int MAX_WIRE_BYTES = 512_000;
    private static final String IMAPS = "imaps";
    private static final String SMTPS = "smtps";
    @FunctionalInterface interface AddressResolver {
        InetSocketAddress resolve(MailWriteService.Prepared message) throws IOException;
    }
    private final AddressResolver resolver;
    private final boolean tls;
    private final boolean synthetic;

    AngusMailWriteTransport(MailWriteService.EndpointResolver resolver) {
        this(message -> resolver.resolve(message.host(), message.port()), true, false);
    }
    // Fixtures are always limited to loopback and never resolve live provider names.
    AngusMailWriteTransport(AddressResolver resolver, boolean tls) { this(resolver, tls, true); }
    private AngusMailWriteTransport(AddressResolver resolver, boolean tls, boolean synthetic) {
        this.resolver = java.util.Objects.requireNonNull(resolver);
        this.tls = tls;
        this.synthetic = synthetic;
    }

    @Override public void write(MailWriteService.Prepared message, char[] authorizationCode,
                                MailWriteService.WriteGate gate, Duration timeout) {
        gate.check();
        String protocol = message.operation() == MailWriteService.Operation.CREATE_DRAFT
                ? tls ? IMAPS : "imap" : tls ? SMTPS : "smtp";
        try (var sockets = sockets(message, gate)) {
            gate.check();
            Session session = Session.getInstance(properties(protocol, tls, timeout, sockets));
            MimeMessage mime = mimeMessage(session, message);
            if (message.operation() == MailWriteService.Operation.CREATE_DRAFT)
                appendDraft(session, protocol, mime, message, authorizationCode, gate, sockets);
            else send(session, protocol, mime, message, authorizationCode, gate, sockets);
        } catch (MailException safe) {
            throw safe;
        } catch (MessagingException failure) {
            var diagnostic = classify(failure);
            if (diagnostic == MailException.Code.CONNECTION_FAILED && explicitRejection(failure))
                throw new MailException(MailException.Code.WRITE_REJECTED);
            throw gate.failure(diagnostic);
        } catch (IOException failure) {
            throw gate.failure(classify(failure));
        }
    }

    private WireSockets sockets(MailWriteService.Prepared message, MailWriteService.WriteGate gate) throws IOException {
        InetSocketAddress address = resolver.resolve(message);
        if (address == null || address.isUnresolved()) throw new IOException("unresolved_mail_destination");
        if (synthetic && !address.getAddress().isLoopbackAddress()) throw new IOException("non_loopback_test_destination");
        return new WireSockets(address, synthetic ? address.getAddress().getHostAddress() : message.host(),
                synthetic ? address.getPort() : message.port(), gate);
    }

    static Properties properties(String protocol, boolean tls, Duration timeout, SocketFactory sockets) {
        var properties = new Properties();
        String prefix = "mail." + protocol + ".";
        String bound = Long.toString(Math.min(10_000, timeout.toMillis()));
        properties.put(prefix + "socketFactory", sockets);
        properties.setProperty(prefix + "socketFactory.fallback", "false");
        properties.setProperty(prefix + "ssl.enable", Boolean.toString(tls));
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        properties.setProperty(prefix + "ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.setProperty(prefix + "starttls.enable", "false");
        properties.setProperty(prefix + "connectiontimeout", bound);
        properties.setProperty(prefix + "timeout", bound);
        properties.setProperty(prefix + "writetimeout", bound);
        properties.setProperty(prefix + "auth", "true");
        properties.setProperty(prefix + "sasl.enable", "false");
        properties.setProperty(prefix + "auth.mechanisms", "LOGIN PLAIN");
        properties.setProperty(prefix + "sendpartial", "false");
        properties.setProperty(prefix + "allow8bitmime", "false");
        properties.setProperty(prefix + "chunksize", "0");
        properties.setProperty(prefix + "localhost", "localhost");
        properties.setProperty(prefix + "quitwait", "false");
        properties.setProperty(prefix + "connectionpoolsize", "1");
        properties.setProperty("mail.mime.allowutf8", "false");
        properties.setProperty("mail.debug", "false");
        properties.setProperty("mail.debug.auth", "false");
        return properties;
    }

    private static MimeMessage mimeMessage(Session session, MailWriteService.Prepared message) throws MessagingException, IOException {
        MimeMessage mime = new MimeMessage(session) {
            @Override protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", "<" + UUID.randomUUID() + "@" + message.account().provider().domain() + ">");
            }
        };
        mime.setFrom(new InternetAddress(message.account().email(), true));
        if (!message.to().isEmpty()) mime.setRecipients(Message.RecipientType.TO, addresses(message));
        mime.setSubject(message.subject(), StandardCharsets.UTF_8.name());
        mime.setText(message.body(), StandardCharsets.UTF_8.name());
        mime.setSentDate(new Date());
        if (message.operation() == MailWriteService.Operation.CREATE_DRAFT) mime.setFlag(Flags.Flag.DRAFT, true);
        mime.saveChanges();
        mime.writeTo(new OutputStream() {
            private int count;
            @Override public void write(int value) { counted(1); }
            @Override public void write(byte[] data, int offset, int length) { counted(length); }
            private void counted(int length) {
                if (length > MailWriteService.MAX_MIME_BYTES - count) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                count += length;
            }
        });
        return mime;
    }

    private static InternetAddress[] addresses(MailWriteService.Prepared message) throws MessagingException {
        var result = new InternetAddress[message.to().size()];
        for (int i = 0; i < result.length; i++) result[i] = new InternetAddress(message.to().get(i), true);
        return result;
    }

    private void appendDraft(Session session, String protocol, MimeMessage mime, MailWriteService.Prepared message,
                             char[] secret, MailWriteService.WriteGate gate, WireSockets sockets)
            throws MessagingException, IOException {
        boolean identify = message.account().provider().requiresImapId();
        IMAPStore store = AngusMailTransport.createStore(session, protocol, tls, identify);
        byte[] data = canonicalMime(mime);
        try {
            gate.check();
            store.connect(sockets.host, sockets.port, message.account().email(), new String(secret));
            gate.check();
            // A closed default folder uses the authenticated Store protocol; neither SELECT nor CLOSE is needed.
            ((IMAPFolder) store.getDefaultFolder()).doCommand(p -> {
                if (identify) {
                    if (!p.hasCapability("ID")) throw new MailException(MailException.Code.PROVIDER_RESTRICTED);
                    String version = getClass().getPackage().getImplementationVersion();
                    p.id(Map.of("name", "SelfAnalyst", "version", version == null ? "development" : version,
                            "vendor", "SelfAnalyst"));
                }
                gate.check();
                String folder = uniqueDrafts(p, message.account());
                gate.beginSubmission();
                p.append(folder, new Flags(Flags.Flag.DRAFT), null, new Literal() {
                    @Override public int size() { return data.length; }
                    @Override public void writeTo(OutputStream out) throws IOException { out.write(data); }
                });
                return null;
            });
        } finally {
            Arrays.fill(data, (byte) 0);
            sockets.closeSockets();
            try { store.close(); } catch (MessagingException ignored) { }
        }
    }

    private static byte[] canonicalMime(MimeMessage mime) throws IOException, MessagingException {
        var buffer = new ByteArrayOutputStream();
        // Angus' typed IMAP APPEND expects the literal length after CRLF canonicalization.
        try (var normalized = new org.eclipse.angus.mail.util.CRLFOutputStream(buffer)) {
            mime.writeTo(normalized);
        }
        if (buffer.size() > MailWriteService.MAX_MIME_BYTES) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
        return buffer.toByteArray();
    }

    private static ListInfo[] listDraftCandidates(IMAPProtocol protocol) throws ProtocolException {
        // RFC 6154: special-use flags are optional in ordinary LIST. Ask explicitly if advertised;
        // compatible older servers may expose the flag in their ordinary LIST. All arguments are fixed.
        if (!protocol.hasCapability("SPECIAL-USE")) return protocol.list("", "*");
        var options = new Argument().writeAtom("SPECIAL-USE");
        var responses = protocol.command("LIST", new Argument().writeArgument(options).writeString("").writeString("*"));
        var tagged = responses[responses.length - 1];
        var folders = new ArrayList<ListInfo>();
        if (tagged.isOK()) for (int i = 0; i < responses.length - 1; i++) {
            if (responses[i] instanceof IMAPResponse response && response.keyEquals("LIST")) {
                if (folders.size() >= MailLimits.MAX_LISTED_FOLDERS) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                folders.add(new ListInfo(response));
                responses[i] = null;
            }
        }
        protocol.notifyResponseHandlers(responses);
        protocol.handleResult(tagged);
        return folders.toArray(ListInfo[]::new);
    }

    private static String uniqueDrafts(IMAPProtocol protocol, MailAccount account) throws ProtocolException {
        ListInfo[] folders = listDraftCandidates(protocol);
        if (folders == null || folders.length == 0) throw new MailException(MailException.Code.DRAFTS_UNAVAILABLE);
        if (folders.length > MailLimits.MAX_LISTED_FOLDERS) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
        String draft = null;
        for (ListInfo folder : folders) {
            if (folder == null || folder.attrs == null || !folder.canOpen || Arrays.stream(folder.attrs)
                    .noneMatch(attribute -> "\\Drafts".equalsIgnoreCase(attribute))) continue;
            if (draft != null || !MailAccount.validFolderName(folder.name) || "INBOX".equalsIgnoreCase(folder.name)
                    || Arrays.stream(folder.attrs).anyMatch(attribute -> Set.of("\\sent", "\\trash", "\\junk", "\\all",
                            "\\archive", "\\flagged", "\\nonexistent").contains(attribute.toLowerCase(Locale.ROOT))))
                throw new MailException(MailException.Code.DRAFTS_UNAVAILABLE);
            draft = folder.name;
        }
        if (draft == null || !account.allowsFolder(draft)) throw new MailException(MailException.Code.DRAFTS_UNAVAILABLE);
        return draft;
    }

    private void send(Session session, String protocol, MimeMessage mime, MailWriteService.Prepared message,
                      char[] secret, MailWriteService.WriteGate gate, WireSockets sockets) throws MessagingException {
        try (SMTPTransport smtp = new SMTPTransport(session, new URLName(protocol, null, -1, null, null, null), protocol, tls) {
            @Override protected void mailFrom() throws MessagingException { gate.check(); super.mailFrom(); }
            @Override protected void rcptTo() throws MessagingException { gate.check(); super.rcptTo(); }
            @Override protected OutputStream data() throws MessagingException { gate.check(); return super.data(); }
            @Override protected void finishData() throws IOException, MessagingException {
                // No BDAT, partial send, retry, or implicit saving to Sent/Drafts.
                gate.beginSubmission();
                super.finishData();
            }
            @Override public synchronized void close() {
                sockets.closeSockets();
                try { super.close(); } catch (MessagingException ignored) { }
            }
        }) {
            gate.check();
            smtp.connect(sockets.host, sockets.port, message.account().email(), new String(secret));
            gate.check();
            smtp.sendMessage(mime, addresses(message));
        }
    }

    private static MailException.Code classify(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthenticationFailedException) return MailException.Code.AUTHENTICATION_FAILED;
            if (cause instanceof SSLException) return MailException.Code.TLS_FAILURE;
            if (cause instanceof SocketTimeoutException) return MailException.Code.TIMEOUT;
            if (cause instanceof WireLimit) return MailException.Code.LIMIT_EXCEEDED;
            if (cause.getCause() == cause) break;
        }
        return MailException.Code.CONNECTION_FAILED;
    }

    private static boolean explicitRejection(MessagingException failure) {
        Throwable cause = failure;
        for (int i = 0; cause != null && i < 12; i++) {
            if (cause instanceof SMTPSendFailedException smtp && smtp.getReturnCode() >= 400 && smtp.getReturnCode() <= 599)
                return smtp.getValidSentAddresses() == null || smtp.getValidSentAddresses().length == 0;
            if (cause instanceof CommandFailedException || cause instanceof BadCommandException) return true;
            if (cause instanceof SendFailedException noRecipients && !(cause instanceof SMTPSendFailedException)
                    && noRecipients.getInvalidAddresses() != null && noRecipients.getInvalidAddresses().length > 0
                    && (noRecipients.getValidSentAddresses() == null || noRecipients.getValidSentAddresses().length == 0)) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private static final class WireLimit extends IOException { }

    /** Angus layers TLS over this pinned direct socket, then verifies the certificate against the original hostname. */
    private static final class WireSockets extends SocketFactory implements AutoCloseable {
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicLong received = new AtomicLong();
        private final InetSocketAddress address;
        private final String host;
        private final int port;
        private final MailWriteService.WriteGate gate;
        private final AutoCloseable cancel;

        WireSockets(InetSocketAddress address, String host, int port, MailWriteService.WriteGate gate) throws IOException {
            if (address == null || address.isUnresolved()) throw new IOException("unresolved_mail_destination");
            this.address = address;
            this.host = host;
            this.port = port;
            this.gate = gate;
            cancel = gate.cancellation().onCancel(this::closeSockets);
        }

        @Override public Socket createSocket() {
            Socket socket = new Socket(Proxy.NO_PROXY) {
                @Override public void connect(SocketAddress target, int timeout) throws IOException {
                    gate.check();
                    if (!(target instanceof InetSocketAddress requested) || requested.getPort() != port
                            || !host.equalsIgnoreCase(requested.getHostString())) throw new IOException("incorrect_mail_destination");
                    super.connect(address, timeout);
                }
                @Override public void connect(SocketAddress target) throws IOException { connect(target, 10_000); }
                @Override public InputStream getInputStream() throws IOException {
                    return new FilterInputStream(super.getInputStream()) {
                        private void count(int length) throws IOException {
                            if (length > 0 && received.addAndGet(length) > MAX_WIRE_BYTES) {
                                closeSockets();
                                throw new WireLimit();
                            }
                        }
                        @Override public int read() throws IOException {
                            int value = in.read();
                            if (value != -1) count(1);
                            return value;
                        }
                        @Override public int read(byte[] data, int offset, int length) throws IOException {
                            int n = in.read(data, offset, Math.min(length, (int) Math.max(1, MAX_WIRE_BYTES - received.get() + 1)));
                            count(n);
                            return n;
                        }
                        @Override public long skip(long n) throws IOException {
                            byte[] discard = new byte[(int) Math.max(0, Math.min(n, 4096))];
                            long total = 0;
                            while (total < n) {
                                int count = read(discard, 0, (int) Math.min(n - total, discard.length));
                                if (count < 0) break;
                                total += count;
                            }
                            return total;
                        }
                    };
                }
            };
            sockets.add(socket);
            return socket;
        }
        @Override public Socket createSocket(String target, int port) throws IOException { throw disabledFactoryMethod(); }
        @Override public Socket createSocket(String target, int port, InetAddress local, int localPort) throws IOException { throw disabledFactoryMethod(); }
        @Override public Socket createSocket(InetAddress target, int port) throws IOException { throw disabledFactoryMethod(); }
        @Override public Socket createSocket(InetAddress target, int port, InetAddress local, int localPort) throws IOException { throw disabledFactoryMethod(); }
        private static IOException disabledFactoryMethod() { return new IOException("unbounded_socket_creation_disabled"); }
        void closeSockets() {
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
        @Override public void close() {
            closeSockets();
            try { cancel.close(); } catch (Exception ignored) { }
        }
    }
}
