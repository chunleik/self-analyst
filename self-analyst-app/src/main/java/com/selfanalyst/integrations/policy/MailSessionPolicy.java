package com.selfanalyst.integrations.policy;

import com.selfanalyst.desktop.store.ChatSessionStore;
import java.util.Objects;
import java.util.Set;

/** Durable, server-owned provenance and model-recipient grants; failures never allow disclosure. */
public final class MailSessionPolicy {
    private final ChatSessionStore sessions;
    public MailSessionPolicy(ChatSessionStore sessions) { this.sessions = Objects.requireNonNull(sessions); }
    public boolean containsMail(String sessionId) { return sessions.mailProvenance(sessionId).containsMail(); }
    public Set<String> allowedTargets(String sessionId) { return sessions.mailProvenance(sessionId).modelTargets(); }
    /** Call only after a trusted managed-desktop action has approved this exact session and target. */
    public void consentTarget(String sessionId, String target) {
        sessions.consentMailTarget(sessionId, ModelRecipient.normalize(target));
    }
    /** Must complete successfully before any mail data can be returned to an agent. */
    public void beforeMailResult(String sessionId, String target) {
        sessions.markMailOrigin(sessionId, ModelRecipient.normalize(target));
    }
    public void requireModelTarget(String sessionId, String target) {
        var provenance = sessions.mailProvenance(sessionId);
        if (provenance.containsMail() && !provenance.modelTargets().contains(ModelRecipient.normalize(target)))
            throw new ModelConsentRequiredException();
    }
    public boolean automaticMemoryAllowed(String sessionId) {
        try { return !containsMail(sessionId); }
        catch (RuntimeException unavailable) { return false; }
    }
    /** Shares the marker's monitor so a queued extraction cannot race a mail result or commit. */
    public boolean withoutMail(String sessionId, Runnable operation) {
        synchronized (sessions.mailOriginLock(sessionId)) {
            if (!automaticMemoryAllowed(sessionId)) return false;
            operation.run();
            return true;
        }
    }
    public static final class ModelConsentRequiredException extends IllegalStateException {
        public ModelConsentRequiredException() { super("MAIL_MODEL_CONSENT_REQUIRED"); }
    }
}
