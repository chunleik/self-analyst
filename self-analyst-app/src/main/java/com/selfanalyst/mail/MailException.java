package com.selfanalyst.mail;

/** Safe public errors deliberately never include server replies, addresses, or credentials. */
public final class MailException extends RuntimeException {
    public enum Code {
        INVALID_ACCOUNT, ACCOUNT_REQUIRED, ACCESS_DENIED, BODY_ACCESS_DENIED, AUTHENTICATION_REQUIRED,
        AUTHENTICATION_FAILED, PROVIDER_RESTRICTED, TLS_FAILURE, PROTOCOL_UNAVAILABLE, SEARCH_UNSUPPORTED,
        TIMEOUT, CANCELLED, LIMIT_EXCEEDED, STALE_REFERENCE, INVALID_CURSOR, CONNECTION_FAILED
    }
    private final Code code;
    public MailException(Code code) { super(code.name().toLowerCase(java.util.Locale.ROOT)); this.code = code; }
    public Code code() { return code; }
}
