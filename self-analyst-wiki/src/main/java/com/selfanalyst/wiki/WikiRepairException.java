package com.selfanalyst.wiki;

/** Stable public repair failures; never exposes a rejected response or raw database error. */
public final class WikiRepairException extends IllegalArgumentException {
    private final String code;
    public WikiRepairException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
