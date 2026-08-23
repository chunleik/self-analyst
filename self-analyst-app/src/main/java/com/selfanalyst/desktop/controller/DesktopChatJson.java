package com.selfanalyst.desktop.controller;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;

/** Shared resource limits and JSON parser for localhost desktop chat mutations. */
final class DesktopChatJson {

    static final int MAX_BODY_BYTES = 256 * 1024;
    static final int MAX_JSON_DEPTH = 32;

    static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_DEPTH)
                    .maxStringLength(MAX_BODY_BYTES)
                    .build())
            .build())
            .registerModule(new JavaTimeModule());

    private DesktopChatJson() {
    }

    static String readBoundedBody(Context ctx) {
        String body = ctx.body();
        if (body == null) return "";
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new PayloadTooLargeException();
        }
        return body;
    }

    static final class PayloadTooLargeException extends IllegalArgumentException {
        private PayloadTooLargeException() {
            super("Chat request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }
}
