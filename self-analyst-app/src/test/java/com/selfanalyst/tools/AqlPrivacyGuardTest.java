package com.selfanalyst.tools;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AqlPrivacyGuardTest {
    @Test void rejectsEveryProtectedKeyAcrossExecutedCallForms() {
        for (String key : List.of("app", "title", "context_title", "url_host", "private_browsing")) {
            for (String query : List.of(
                    "RETURN = tag(query_bucket('bucket'), '" + key + ":changed');",
                    "RETURN = sort_by_duration(tag (query_bucket('bucket'), 'project:ok, " + key + " : changed'));",
                    "e = query_bucket('bucket'); t = tag(e, " + key + ":changed); RETURN = t;",
                    "RETURN = tag(tag(query_bucket('bucket'), '" + key + ":changed'), 'project:ok');",
                    "RETURN = concat(query_bucket('bucket'), tag(query_bucket('bucket'), '" + key + ":changed'));")) {
                assertThrows(AqlPrivacyGuard.UnsafeQueryException.class, () -> AqlPrivacyGuard.validate(query), query);
            }
        }
    }

    @Test void preservesSafeTagsAggregatesAndTextMentions() {
        for (String query : List.of(
                "RETURN = tag(query_bucket('bucket'), 'project:work,category:personal');",
                "RETURN = tag(query_bucket('bucket'), project:work);",
                "RETURN = tag(query_bucket('bucket'), 'note:tag(events app:changed)');",
                "RETURN = filter_keyvals(query_bucket('bucket'), 'title', ['tag(events, app:changed)']);",
                "RETURN = filter_keyvals_regex(query_bucket('bucket'), 'title', 'tag(events, app:changed)');",
                "RETURN = sum_durations(query_bucket('bucket'));",
                "events = query_bucket('bucket'); RETURN = {\"tag(events, app:changed)\": events};",
                "RETURN = tag(query_bucket('bucket'), 'app:changed'); RETURN = query_bucket('bucket');",
                "ignored = [tag(events, app:changed)]; RETURN = [];")) {
            assertDoesNotThrow(() -> AqlPrivacyGuard.validate(query), query);
        }
    }

    @Test void rejectsNonliteralAndAmbiguousTagArguments() {
        for (String spec : List.of("tags", "['project:work']", "query_bucket('bucket')", "unknown()")) {
            String query = "tags = query_bucket('bucket'); RETURN = tag(query_bucket('bucket'), " + spec + ");";
            assertThrows(AqlPrivacyGuard.UnsafeQueryException.class, () -> AqlPrivacyGuard.validate(query), query);
        }
        assertThrows(AqlPrivacyGuard.UnsafeQueryException.class,
                () -> AqlPrivacyGuard.validate("RETURN = tag(query_bucket('bucket'));"));
    }

    @Test void literalUnicodeEscapesAreNotDecodedAsKeysOrCalls() {
        String escape = "\\" + "u0061";
        assertDoesNotThrow(() -> AqlPrivacyGuard.validate("RETURN = tag(query_bucket('bucket'), '" + escape + "pp:changed');"));
        assertThrows(IllegalArgumentException.class,
                () -> AqlPrivacyGuard.validate("RETURN = t" + escape + "g(query_bucket('bucket'), 'app:changed');"));
    }
}
