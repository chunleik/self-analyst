package com.selfanalyst.mail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Isolated, one-shot draft/send path. Callers must obtain the managed user's exact-message approval. */
public final class MailWriteService {
    public enum Operation { CREATE_DRAFT, SEND }
    public enum Status { DRAFT_SAVED, ACCEPTED_BY_SMTP }
    public record Result(Operation operation, Status status) { }

    public static final int MAX_RECIPIENTS = 10;
    public static final int MAX_SUBJECT_CODE_POINTS = 200;
    public static final int MAX_BODY_BYTES = 12_000;
    static final int MAX_MIME_BYTES = 64_000;
    private static final Pattern ADDRESS = Pattern.compile(
            "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
    private final MailWriteTransport transport;
    private final Duration timeout;
    private final Semaphore slots = new Semaphore(8);

    @FunctionalInterface
    public interface EndpointResolver {
        java.net.InetSocketAddress resolve(String host, int port) throws java.io.IOException;
    }

    public MailWriteService(EndpointResolver resolver) {
        this(new AngusMailWriteTransport(Objects.requireNonNull(resolver)), Duration.ofSeconds(30));
    }
    MailWriteService(MailWriteTransport transport, Duration timeout) {
        this.transport = Objects.requireNonNull(transport);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("invalid_mail_write_timeout");
    }

    /** Authentication target is fixed by the supported provider, never chosen by model or MCP arguments. */
    public static String smtpHost(MailAccount.Provider provider) {
        return Objects.requireNonNull(provider).smtpHost();
    }
    public static final int SMTP_PORT = 465;

    /** No network I/O here. This same immutable payload must be displayed and later submitted. */
    public Prepared prepare(MailAccount account, Operation operation, List<String> to, String subject, String body) {
        if (account == null || !account.enabled()) throw new MailException(MailException.Code.ACCESS_DENIED);
        if (operation == null || to == null || to.size() > MAX_RECIPIENTS || operation == Operation.SEND && to.isEmpty())
            throw invalidMessage();
        validateText(subject, MAX_SUBJECT_CODE_POINTS * 2, false);
        if (subject.codePointCount(0, subject.length()) > MAX_SUBJECT_CODE_POINTS) throw invalidMessage();
        validateText(body, MAX_BODY_BYTES, true);
        String normalizedBody = body.replace("\r\n", "\n").replace('\r', '\n');
        if (normalizedBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) throw invalidMessage();
        var seen = new HashSet<String>();
        var recipients = new ArrayList<String>();
        for (Object raw : to) {
            if (!(raw instanceof String item) || recipients.size() >= MAX_RECIPIENTS
                    || item.length() > 254 || !ADDRESS.matcher(item).matches()) throw invalidMessage();
            int at = item.lastIndexOf('@');
            if (at > 64) throw invalidMessage();
            String address = item.substring(0, at + 1) + item.substring(at + 1).toLowerCase(Locale.ROOT);
            if (!seen.add(address.toLowerCase(Locale.ROOT))) throw invalidMessage();
            recipients.add(address);
        }
        if (operation == Operation.SEND && recipients.isEmpty()) throw invalidMessage();
        return new Prepared(this, account, operation, List.copyOf(recipients), subject, normalizedBody);
    }

    public static final class Prepared {
        private final MailWriteService owner;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final MailAccount account;
        private final Operation operation;
        private final List<String> to;
        private final String subject;
        private final String body;

        private Prepared(MailWriteService owner, MailAccount account, Operation operation, List<String> to,
                         String subject, String body) {
            this.owner = owner;
            this.account = account;
            this.operation = operation;
            this.to = to;
            this.subject = subject;
            this.body = body;
        }
        public String target() {
            return operation == Operation.CREATE_DRAFT ? "imaps://" + host() + ":" + port() : "smtps://" + host() + ":" + port();
        }
        public Map<String, Object> approvalDetails() {
            return Map.of("operation", operation == Operation.CREATE_DRAFT ? "create_draft" : "send_email",
                    "accountId", account.id(), "from", account.email(), "to", to, "subject", subject, "body", body,
                    "endpoint", target(), "accountRevision", account.permissionRevision(),
                    "destination", operation == Operation.CREATE_DRAFT ? "provider_special_use_drafts" : "smtp_recipients");
        }
        MailAccount account() { return account; }
        Operation operation() { return operation; }
        String host() { return operation == Operation.CREATE_DRAFT ? account.provider().host() : smtpHost(account.provider()); }
        int port() { return operation == Operation.CREATE_DRAFT ? account.provider().port() : account.provider().smtpPort(); }
        List<String> to() { return to; }
        String subject() { return subject; }
        String body() { return body; }
    }

    /** Takes ownership of the exact target's authorization code and wipes the caller's array on every exit. */
    public Result execute(Prepared prepared, char[] authorizationCode, BooleanSupplier stillAuthorized,
                          MailCancellation cancellation) {
        try {
            if (prepared == null || prepared.owner != this || !prepared.consumed.compareAndSet(false, true)
                    || stillAuthorized == null || cancellation == null)
                throw new MailException(MailException.Code.ACCESS_DENIED);
            cancellation.check();
            requireAuthorized(stillAuthorized);
            char[] protocolCode = prepared.account().provider().protocolAuthorizationCode(authorizationCode);
            char[] ownedCode = protocolCode == authorizationCode ? authorizationCode.clone() : protocolCode;
            if (!slots.tryAcquire()) {
                Arrays.fill(ownedCode, '\0');
                throw new MailException(MailException.Code.LIMIT_EXCEEDED);
            }
            Arrays.fill(authorizationCode, '\0');
            return run(prepared, ownedCode, stillAuthorized, cancellation);
        } finally {
            if (authorizationCode != null) Arrays.fill(authorizationCode, '\0');
        }
    }

    private Result run(Prepared prepared, char[] ownedCode, BooleanSupplier stillAuthorized, MailCancellation owner) {
        var result = new CompletableFuture<Result>();
        var gate = new WriteGate(stillAuthorized);
        Thread worker = Thread.ofVirtual().name("authorized-mail-write").unstarted(() -> {
            try {
                gate.check();
                transport.write(prepared, ownedCode, gate, timeout);
                gate.complete();
                result.complete(new Result(prepared.operation(), prepared.operation() == Operation.CREATE_DRAFT
                        ? Status.DRAFT_SAVED : Status.ACCEPTED_BY_SMTP));
            } catch (MailException failure) {
                result.completeExceptionally(failure);
            } catch (Exception failure) {
                result.completeExceptionally(gate.failure(MailException.Code.CONNECTION_FAILED));
            } finally {
                Arrays.fill(ownedCode, '\0');
                slots.release();
            }
        });
        try (AutoCloseable ignored = owner.onCancel(() -> abort(gate, worker, result, MailException.Code.CANCELLED))) {
            worker.start();
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw abort(gate, worker, result, MailException.Code.TIMEOUT);
        } catch (InterruptedException e) {
            MailException interrupted = abort(gate, worker, result, MailException.Code.CANCELLED);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MailException failure) throw failure;
            throw gate.failure(MailException.Code.CONNECTION_FAILED);
        } catch (MailException e) {
            throw e;
        } catch (Exception e) {
            throw abort(gate, worker, result, MailException.Code.CONNECTION_FAILED);
        }
    }

    private static MailException abort(WriteGate gate, Thread worker, CompletableFuture<Result> result,
                                       MailException.Code reason) {
        MailException failure = gate.abort(reason);
        result.completeExceptionally(failure);
        worker.interrupt();
        gate.cancellation().cancel();
        return failure;
    }

    /** Marks the last protocol phase at which cancellation can still avoid a possible submission. */
    static final class WriteGate {
        private enum Phase { OPEN, SUBMITTING, COMPLETED, ABORTED, ABORTED_AFTER_SUBMISSION }
        private final BooleanSupplier stillAuthorized;
        private final MailCancellation cancellation = new MailCancellation();
        private Phase phase = Phase.OPEN;
        WriteGate(BooleanSupplier stillAuthorized) { this.stillAuthorized = stillAuthorized; }
        MailCancellation cancellation() { return cancellation; }
        void check() {
            cancellation.check();
            requireAuthorized(stillAuthorized);
            synchronized (this) {
                if (phase != Phase.OPEN) throw new MailException(MailException.Code.CANCELLED);
            }
        }
        void beginSubmission() {
            check();
            synchronized (this) {
                if (phase != Phase.OPEN) throw new MailException(MailException.Code.CANCELLED);
                phase = Phase.SUBMITTING;
            }
        }
        synchronized void complete() {
            if (phase != Phase.SUBMITTING) throw failure(MailException.Code.CONNECTION_FAILED);
            phase = Phase.COMPLETED;
        }
        synchronized MailException abort(MailException.Code reason) {
            MailException failure = failure(reason);
            phase = phase == Phase.SUBMITTING || phase == Phase.COMPLETED || phase == Phase.ABORTED_AFTER_SUBMISSION
                    ? Phase.ABORTED_AFTER_SUBMISSION : Phase.ABORTED;
            return failure;
        }
        synchronized MailException failure(MailException.Code beforeSubmission) {
            return new MailException(phase == Phase.SUBMITTING || phase == Phase.COMPLETED
                    || phase == Phase.ABORTED_AFTER_SUBMISSION ? MailException.Code.OUTCOME_UNKNOWN : beforeSubmission);
        }
    }

    private static void requireAuthorized(BooleanSupplier stillAuthorized) {
        boolean allowed;
        try { allowed = stillAuthorized.getAsBoolean(); }
        catch (RuntimeException failure) { allowed = false; }
        if (!allowed) throw new MailException(MailException.Code.ACCESS_DENIED);
    }
    private static void validateText(String text, int maxChars, boolean multiline) {
        if (text == null || text.length() > maxChars || text.codePoints().anyMatch(c ->
                c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE
                        || Character.isISOControl(c) && !(multiline && (c == '\t' || c == '\r' || c == '\n'))))
            throw invalidMessage();
    }
    private static MailException invalidMessage() { return new MailException(MailException.Code.INVALID_MESSAGE); }
}
