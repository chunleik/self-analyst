package com.selfanalyst.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Four mail operations. Authorization and persisted provenance belong to the application boundary. */
public final class ReadOnlyMailService implements AutoCloseable {
    @FunctionalInterface public interface AccountProvider { List<MailAccount> accounts(); }
    @FunctionalInterface public interface CredentialSupplier {
        /** Return a new temporary array, bound to this exact provider/email. Service clears it after use. */
        char[] authorizationCode(MailAccount account);
    }
    public interface AuthorizationBoundary {
        /** Must check current trusted session permission; model arguments are never permission. */
        void authorize(CallContext context, MailAccount account, String folder, boolean body);
        /** Must persist source marking and verify the actual model recipient before any result is exposed. */
        void beforeResult(CallContext context, MailAccount account);
    }
    public record CallContext(String sessionId, String turnId, MailCancellation cancellation) {
        public CallContext {
            if (sessionId == null || sessionId.isBlank() || turnId == null || turnId.isBlank()
                    || sessionId.length() > 200 || turnId.length() > 200) throw new MailException(MailException.Code.ACCESS_DENIED);
            cancellation = cancellation == null ? new MailCancellation() : cancellation;
        }
    }
    public record SearchRequest(String accountId, String folder, Instant after, Instant before,
                                String from, String subject, Boolean unread, int pageSize, String cursor) {
        public SearchRequest {
            folder = folder == null ? "INBOX" : folder;
            pageSize = pageSize == 0 ? 20 : pageSize;
            if (pageSize < 1 || pageSize > 50 || !MailAccount.validFolderName(folder)
                    || invalidFilter(from) || invalidFilter(subject)
                    || (after != null && before != null && !after.isBefore(before))) {
                throw new MailException(MailException.Code.SEARCH_UNSUPPORTED);
            }
        }
        private static boolean invalidFilter(String value) {
            return value != null && (value.length() > 256 || value.chars().anyMatch(Character::isISOControl));
        }
        private String queryKey() {
            return Objects.toString(after, "") + "\u0000" + Objects.toString(before, "") + "\u0000"
                    + Objects.toString(from, "") + "\u0000" + Objects.toString(subject, "") + "\u0000" + unread + "\u0000" + pageSize;
        }
    }
    public record MessageSummary(String reference, long uid, String subject, List<String> from,
                                 List<String> to, Instant date, boolean unread, String messageId, int size) { }
    public record Coverage(Instant requestedAfter, Instant requestedBefore, int examinedMessages,
                           long lowestExaminedUid, boolean truncated, String providerVisibleRange) { }
    public record SearchResult(List<MessageSummary> messages, Coverage coverage, String nextCursor) { }
    public record MessageResult(MessageSummary message, MailBodyReader.Body body) { }
    public record ConnectionTest(boolean connected, List<String> folders, boolean readOnly, String providerVisibleRange) { }
    private record Reference(String accountId, String folder, long validity, long uid, long revision,
                             String query, String sessionId, Instant expires) { }
    private record Cursor(String accountId, String folder, long validity, long beforeUid, long revision,
                          String query, String sessionId, Instant expires) { }

    private final AccountProvider accounts;
    private final CredentialSupplier credentials;
    private final AuthorizationBoundary authorization;
    private final MailTransport transport;
    private final MailLimits limits;
    private final Clock clock;
    private final Map<String, Reference> references = boundedMap();
    private final Map<String, Cursor> cursors = boundedMap();
    private final ReentrantLock[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);
    private final java.util.Set<MailCancellation> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ReadOnlyMailService(AccountProvider accounts, CredentialSupplier credentials, AuthorizationBoundary authorization) {
        this(accounts, credentials, authorization, new AngusMailTransport(), MailLimits.defaults(), Clock.systemUTC());
    }
    public ReadOnlyMailService(AccountProvider accounts, CredentialSupplier credentials, AuthorizationBoundary authorization,
                               MailTransport transport, MailLimits limits, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts); this.credentials = Objects.requireNonNull(credentials);
        this.authorization = Objects.requireNonNull(authorization); this.transport = Objects.requireNonNull(transport);
        this.limits = Objects.requireNonNull(limits); this.clock = Objects.requireNonNull(clock);
    }
    private static <T> Map<String, T> boundedMap() {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, T> eldest) { return size() > 2048; }
        });
    }
    public List<MailAccount> listAccounts(CallContext context) {
        check(context);
        List<MailAccount> result = new ArrayList<>();
        for (MailAccount account : currentAccounts()) {
            if (!account.enabled()) continue;
            try { authorization.authorize(context, account, null, false); }
            catch (MailException e) { if (e.code() == MailException.Code.ACCESS_DENIED) continue; throw e; }
            validate(account, null, false, context);
            authorization.beforeResult(context, account);
            validate(account, null, false, context);
            result.add(account);
        }
        for (MailAccount account : result) validate(account, null, false, context);
        check(context);
        return List.copyOf(result);
    }
    public List<String> listFolders(String accountId, CallContext context) {
        MailAccount account = resolve(accountId);
        return operate(account, null, false, context, (connection) -> validateFolders(connection.listFolders()).stream()
                .filter(account::allowsFolder).toList());
    }
    public SearchResult searchMessages(SearchRequest request, CallContext context) {
        Objects.requireNonNull(request);
        MailAccount account = resolve(request.accountId());
        Cursor cursor = request.cursor() == null || request.cursor().isBlank() ? null : cursors.get(request.cursor());
        if (request.cursor() != null && !request.cursor().isBlank() && (cursor == null
                || !cursor.accountId().equals(account.id()) || !cursor.folder().equals(request.folder())
                || cursor.revision() != account.permissionRevision() || !cursor.query().equals(request.queryKey())
                || !cursor.sessionId().equals(context.sessionId()) || !clock.instant().isBefore(cursor.expires()))) {
            throw new MailException(MailException.Code.INVALID_CURSOR);
        }
        return operate(account, request.folder(), false, context, connection -> {
            try (MailTransport.Mailbox box = connection.openReadOnly(request.folder())) {
                long validity = box.uidValidity();
                if (cursor != null && cursor.validity() != validity) throw new MailException(MailException.Code.STALE_REFERENCE);
                MailTransport.SearchBatch batch = box.search(request, cursor == null ? null : cursor.beforeUid(), limits.maxSearchMessages());
                List<MailTransport.Envelope> matches = batch.messages().stream().sorted(Comparator.comparingLong(MailTransport.Envelope::uid).reversed()).toList();
                List<MessageSummary> messages = matches.stream().limit(request.pageSize())
                        .map(e -> summary(account, request.folder(), validity, request.queryKey(), context, e)).toList();
                boolean extra = matches.size() > request.pageSize();
                boolean more = extra || batch.moreAvailable();
                String next = null;
                if (more) {
                    long before = extra ? matches.get(request.pageSize() - 1).uid() : batch.lowestExaminedUid();
                    if (before > 1) {
                        next = UUID.randomUUID().toString();
                        cursors.put(next, new Cursor(account.id(), request.folder(), validity, before, account.permissionRevision(),
                                request.queryKey(), context.sessionId(), clock.instant().plus(Duration.ofMinutes(15))));
                    }
                }
                return new SearchResult(messages, new Coverage(request.after(), request.before(), batch.examined(),
                        batch.lowestExaminedUid(), more, "unknown"), next);
            }
        });
    }
    public MessageResult getMessage(String reference, CallContext context) {
        Reference ref = reference == null ? null : references.get(reference);
        if (ref == null || !clock.instant().isBefore(ref.expires()) || !ref.sessionId().equals(context.sessionId())) {
            throw new MailException(MailException.Code.STALE_REFERENCE);
        }
        MailAccount account = resolve(ref.accountId());
        if (ref.revision() != account.permissionRevision()) throw new MailException(MailException.Code.STALE_REFERENCE);
        return operate(account, ref.folder(), true, context, connection -> {
            try (MailTransport.Mailbox box = connection.openReadOnly(ref.folder())) {
                if (box.uidValidity() != ref.validity()) throw new MailException(MailException.Code.STALE_REFERENCE);
                MailTransport.Envelope envelope = box.envelope(ref.uid());
                if (envelope == null) throw new MailException(MailException.Code.STALE_REFERENCE);
                return new MessageResult(summary(account, ref.folder(), ref.validity(), ref.query(), context, envelope), box.readBody(ref.uid(), limits));
            }
        });
    }
    /** Desktop setup only: no account enablement, message access, provenance or model callback. */
    public List<MailTransport.FolderInfo> discoverFolders(MailAccount draft, char[] authorizationCode, MailCancellation cancellation) {
        char[] copy = authorizationCode == null ? null : authorizationCode.clone();
        try {
            return timed(draft.id(), cancellation == null ? new MailCancellation() : cancellation, local -> {
                try (MailTransport.Connection connection = transport.connect(draft, copy, local, limits)) {
                    List<MailTransport.FolderInfo> folders = connection.listFolderDetails();
                    if (folders == null) throw new MailException(MailException.Code.PROTOCOL_UNAVAILABLE);
                    if (folders.size() > MailLimits.MAX_LISTED_FOLDERS) throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                    var names = new java.util.HashSet<String>();
                    for (var folder : folders) {
                        if (folder != null && folder.name() != null && folder.name().length() > MailLimits.MAX_FOLDER_NAME_LENGTH)
                            throw new MailException(MailException.Code.LIMIT_EXCEEDED);
                        if (folder == null || !MailAccount.validFolderName(folder.name()) || !folder.selectable()
                                || folder.separator() == null || folder.separator().length() > 1
                                || folder.separator().chars().anyMatch(Character::isISOControl)
                                || !names.add(folder.name())) throw new MailException(MailException.Code.PROTOCOL_UNAVAILABLE);
                    }
                    local.check();
                    return List.copyOf(folders);
                }
            });
        } finally { if (copy != null) Arrays.fill(copy, '\0'); }
    }
    /** Draft testing never asks the credential repository to save or enables an account. */
    public ConnectionTest testConnection(MailAccount draft, char[] authorizationCode, MailCancellation cancellation) {
        char[] copy = authorizationCode == null ? null : authorizationCode.clone();
        try {
            return timed(draft.id(), cancellation == null ? new MailCancellation() : cancellation, local -> {
                try (MailTransport.Connection connection = transport.connect(draft, copy, local, limits)) {
                    List<String> folders = validateFolders(connection.listFolders());
                    try (MailTransport.Mailbox box = connection.openReadOnly("INBOX")) {
                        box.uidValidity();
                        return new ConnectionTest(true, List.copyOf(folders), true, "unknown");
                    }
                }
            });
        } finally { if (copy != null) Arrays.fill(copy, '\0'); }
    }
    private <T> T operate(MailAccount account, String folder, boolean body, CallContext context,
                          Function<MailTransport.Connection, T> action) {
        check(context);
        validate(account, folder, body, context);
        return timed(account.id(), context.cancellation(), local -> {
            validate(account, folder, body, context);
            char[] secret = credentials.authorizationCode(account);
            if (secret == null || secret.length == 0) throw new MailException(MailException.Code.AUTHENTICATION_REQUIRED);
            try (MailTransport.Connection connection = transport.connect(account, secret, local, limits)) {
                T result = action.apply(connection);
                local.check();
                validate(account, folder, body, context);
                authorization.beforeResult(context, account);
                validate(account, folder, body, context);
                local.check();
                return result;
            } finally { Arrays.fill(secret, '\0'); }
        });
    }
    private void validate(MailAccount expected, String folder, boolean body, CallContext context) {
        validateSnapshot(expected, folder, body);
        authorization.authorize(context, expected, folder, body);
        // The boundary can wait for user consent. Recheck without another consent callback.
        validateSnapshot(expected, folder, body);
    }
    private void validateSnapshot(MailAccount expected, String folder, boolean body) {
        MailAccount latest = resolve(expected.id());
        if (!latest.equals(expected) || (folder != null && !latest.allowsFolder(folder))) {
            throw new MailException(MailException.Code.ACCESS_DENIED);
        }
        if (body && !latest.bodyAccess()) throw new MailException(MailException.Code.BODY_ACCESS_DENIED);
    }
    private static List<String> validateFolders(List<String> folders) {
        if (folders == null) throw new MailException(MailException.Code.PROTOCOL_UNAVAILABLE);
        if (folders.size() > MailLimits.MAX_LISTED_FOLDERS
                || folders.stream().anyMatch(f -> f != null && f.length() > MailLimits.MAX_FOLDER_NAME_LENGTH))
            throw new MailException(MailException.Code.LIMIT_EXCEEDED);
        if (folders.stream().anyMatch(f -> !MailAccount.validFolderName(f)))
            throw new MailException(MailException.Code.PROTOCOL_UNAVAILABLE);
        return List.copyOf(folders);
    }
    private List<MailAccount> currentAccounts() {
        List<MailAccount> result = List.copyOf(accounts.accounts());
        if (result.size() > 64 || result.stream().map(MailAccount::id).distinct().count() != result.size()) {
            throw new MailException(MailException.Code.ACCESS_DENIED);
        }
        return result;
    }
    private MailAccount resolve(String id) {
        List<MailAccount> enabled = currentAccounts().stream().filter(MailAccount::enabled).toList();
        if (id == null || id.isBlank()) {
            if (enabled.size() != 1) throw new MailException(MailException.Code.ACCOUNT_REQUIRED);
            return enabled.getFirst();
        }
        return enabled.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(() -> new MailException(MailException.Code.ACCESS_DENIED));
    }
    private MessageSummary summary(MailAccount account, String folder, long validity, String query,
                                   CallContext context, MailTransport.Envelope envelope) {
        String ref = UUID.randomUUID().toString();
        references.put(ref, new Reference(account.id(), folder, validity, envelope.uid(), account.permissionRevision(), query,
                context.sessionId(), clock.instant().plus(Duration.ofMinutes(15))));
        return new MessageSummary(ref, envelope.uid(), envelope.subject(), envelope.from(), envelope.to(), envelope.date(),
                envelope.unread(), envelope.messageId(), envelope.size());
    }
    private void check(CallContext context) {
        if (closed) throw new MailException(MailException.Code.CANCELLED);
        Objects.requireNonNull(context).cancellation().check();
    }
    private <T> T timed(String accountId, MailCancellation caller, Function<MailCancellation, T> work) {
        if (closed) throw new MailException(MailException.Code.CANCELLED);
        caller.check();
        MailCancellation local = new MailCancellation();
        active.add(local);
        CompletableFuture<T> result = new CompletableFuture<>();
        ReentrantLock lock = locks[Math.floorMod(accountId.hashCode(), locks.length)];
        Thread worker = Thread.ofVirtual().name("readonly-mail").unstarted(() -> {
            boolean held = false;
            try {
                lock.lockInterruptibly(); held = true; local.check();
                result.complete(work.apply(local));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); result.completeExceptionally(new MailException(MailException.Code.CANCELLED)); }
            catch (Throwable e) { result.completeExceptionally(e); }
            finally { if (held) lock.unlock(); active.remove(local); }
        });
        try (AutoCloseable ignored = caller.onCancel(() -> { local.cancel(); worker.interrupt(); result.completeExceptionally(new MailException(MailException.Code.CANCELLED)); })) {
            worker.start();
            return result.get(limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) { local.cancel(); worker.interrupt(); throw new MailException(MailException.Code.TIMEOUT); }
        catch (InterruptedException e) { local.cancel(); worker.interrupt(); Thread.currentThread().interrupt(); throw new MailException(MailException.Code.CANCELLED); }
        catch (ExecutionException e) { if (e.getCause() instanceof MailException m) throw m; throw new MailException(MailException.Code.CONNECTION_FAILED); }
        catch (MailException e) { throw e; }
        catch (Exception e) { throw new MailException(MailException.Code.CONNECTION_FAILED); }
    }
    @Override public void close() {
        closed = true; active.forEach(MailCancellation::cancel); references.clear(); cursors.clear();
    }
}
