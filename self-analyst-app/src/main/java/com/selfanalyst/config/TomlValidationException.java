package com.selfanalyst.config;

import java.util.List;

/**
 * Raised when submitted TOML text is syntactically invalid, contains an
 * unsupported structure, or fails known-key type checks. Carries one
 * human-readable message per problem (syntax errors include line/column:
 * {@code "第 N 行第 M 列: <原因>"}). SPEC-TOML-API-001b/c, SPEC-TOML-FMT-003c.
 */
public class TomlValidationException extends RuntimeException {

    private final List<String> messages;

    public TomlValidationException(List<String> messages) {
        super(String.join("; ", messages));
        this.messages = List.copyOf(messages);
    }

    /** Individual problem messages, in reporting order. */
    public List<String> messages() {
        return messages;
    }
}
