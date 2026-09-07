package com.selfanalyst.events.store;

/** Safe validation error that identifies field names but never field values. */
public class ContentEventPolicyViolationException extends IllegalArgumentException {

    private final String field;

    public ContentEventPolicyViolationException(String field, String message) {
        super(message + ": " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
