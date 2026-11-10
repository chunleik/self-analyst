package com.selfanalyst.mail;

import java.util.Locale;
import java.util.Set;

/** Public account metadata. Secrets and caller-supplied endpoints are intentionally absent. */
public record MailAccount(String id, Provider provider, String email, String displayName,
                          boolean enabled, String folderScope, Set<String> allowedFolders, boolean bodyAccess,
                          long permissionRevision) {
    public enum Provider {
        QQ("imap.qq.com", "qq.com"), NETEASE_163("imap.163.com", "163.com");
        private final String host;
        private final String domain;
        Provider(String host, String domain) { this.host = host; this.domain = domain; }
        public String host() { return host; }
        public int port() { return 993; }
        public String domain() { return domain; }
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
        if (at <= 0 || !email.substring(0, at).matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
                || !email.substring(at + 1).toLowerCase(Locale.ROOT).equals(provider.domain())) {
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
        if (("selected".equals(folderScope) && allowedFolders.isEmpty()) || allowedFolders.size() > 32
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
