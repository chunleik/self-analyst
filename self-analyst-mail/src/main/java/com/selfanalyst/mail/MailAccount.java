package com.selfanalyst.mail;

import java.util.Locale;
import java.util.Set;

/** Public account metadata. Secrets and caller-supplied endpoints are intentionally absent. */
public record MailAccount(String id, Provider provider, String email, String displayName,
                          boolean enabled, String folderScope, Set<String> allowedFolders, boolean bodyAccess,
                          long permissionRevision) {
    /** Fixed personal-mail profiles; account configuration can select an ID, not supply a host. */
    public enum Provider {
        QQ("qq", "qq.com", "imap.qq.com", "smtp.qq.com", false),
        NETEASE_163("163", "163.com", "imap.163.com", "smtp.163.com", true),
        NETEASE_126("126", "126.com", "imap.126.com", "smtp.126.com", true),
        GMAIL("gmail", "gmail.com", "imap.gmail.com", "smtp.gmail.com", false);

        private final String id;
        private final String domain;
        private final String host;
        private final String smtpHost;
        private final boolean requiresImapId;

        Provider(String id, String domain, String host, String smtpHost, boolean requiresImapId) {
            this.id = id;
            this.domain = domain;
            this.host = host;
            this.smtpHost = smtpHost;
            this.requiresImapId = requiresImapId;
        }
        public static Provider fromId(String id) {
            for (Provider provider : values()) if (provider.id.equals(id)) return provider;
            throw new IllegalArgumentException("unsupported_provider");
        }
        public String id() { return id; }
        public String host() { return host; }
        public int port() { return 993; }
        public String smtpHost() { return smtpHost; }
        public int smtpPort() { return 465; }
        public String domain() { return domain; }
        public boolean requiresImapId() { return requiresImapId; }

        /** Returns a lowercased identity for the configuration and vault, not a network destination. */
        public String bindingEmail(String email) {
            if (!allowsAddress(email)) throw new IllegalArgumentException("invalid_email");
            return email.toLowerCase(Locale.ROOT);
        }
        private boolean allowsAddress(String email) {
            if (email == null || email.length() > 254) return false;
            int at = email.lastIndexOf('@');
            return at > 0 && email.substring(0, at).matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
                    && email.substring(at + 1).toLowerCase(Locale.ROOT).equals(domain);
        }

        /** Checks credential syntax only. Account eligibility is verified by the provider during TLS authentication. */
        public boolean acceptsAuthorizationCode(char[] code) {
            if (code == null || code.length == 0 || code.length > 256) return false;
            if (this != GMAIL) return true;
            if (code.length != 16 && code.length != 19) return false;
            for (int i = 0; i < code.length; i++) {
                char c = code[i];
                if (code.length == 19 && i % 5 == 4) {
                    if (c != ' ') return false;
                } else if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) {
                    return false;
                }
            }
            return true;
        }
        // If a grouped Google app password was pasted, use the exact 16 characters over the wire.
        char[] protocolAuthorizationCode(char[] code) {
            if (!acceptsAuthorizationCode(code)) throw new MailException(MailException.Code.AUTHENTICATION_REQUIRED);
            if (this != GMAIL || code.length == 16) return code;
            char[] compact = new char[16];
            for (int i = 0, at = 0; i < code.length; i++) if (code[i] != ' ') compact[at++] = code[i];
            return compact;
        }
    }
    /** Existing callers keep selected-folder semantics, including the default INBOX. */
    public MailAccount(String id, Provider provider, String email, String displayName,
                       boolean enabled, Set<String> allowedFolders, boolean bodyAccess, long permissionRevision) {
        this(id, provider, email, displayName, enabled, "selected", allowedFolders, bodyAccess, permissionRevision);
    }
    public MailAccount {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}") || provider == null
                || email == null || email.length() > 254 || permissionRevision < 0) {
            throw new MailException(MailException.Code.INVALID_ACCOUNT);
        }
        email = email.trim();
        int at = email.lastIndexOf('@');
        if (!provider.allowsAddress(email)) {
            throw new MailException(MailException.Code.INVALID_ACCOUNT);
        }
        email = email.substring(0, at + 1) + provider.domain();
        displayName = displayName == null || displayName.isBlank() ? email : displayName;
        if (displayName.length() > 160 || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new MailException(MailException.Code.INVALID_ACCOUNT);
        }
        if (!"all".equals(folderScope) && !"selected".equals(folderScope)) {
            throw new MailException(MailException.Code.INVALID_ACCOUNT);
        }
        allowedFolders = allowedFolders == null ? Set.of("INBOX") : Set.copyOf(allowedFolders);
        if (("selected".equals(folderScope) && allowedFolders.isEmpty()) || allowedFolders.size() > 256
                || allowedFolders.stream().anyMatch(f -> !validFolderName(f))) {
            throw new MailException(MailException.Code.INVALID_ACCOUNT);
        }
    }
    /** A full IMAP name is an exact name, never a pattern or a stale enumeration. */
    public boolean allowsFolder(String folder) {
        return validFolderName(folder) && ("all".equals(folderScope) || allowedFolders.contains(folder));
    }
    static boolean validFolderName(String folder) {
        return folder != null && !folder.isBlank() && folder.length() <= MailLimits.MAX_FOLDER_NAME_LENGTH
                && folder.codePoints().noneMatch(Character::isISOControl);
    }
}
