package com.selfanalyst.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.wiki.WikiPrivacyPolicy;

import java.util.Iterator;
import java.util.Objects;

/** Removes private event objects before model-visible query results are budgeted. */
public final class EventPrivacyFilter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String INVALID_JSON = "{\"error\":\"EVENT_PRIVACY_INVALID_JSON\"}";

    private EventPrivacyFilter() {}

    public static String filter(String response, WikiPrivacyPolicy policy) {
        return filter(response, policy, false);
    }

    /** Embedded AQL serializes event data directly into timestamp/duration rows. */
    public static String filterAql(String response, WikiPrivacyPolicy policy) {
        return filter(response, policy, true);
    }

    private static String filter(String response, WikiPrivacyPolicy policy, boolean aql) {
        Objects.requireNonNull(policy);
        if (response == null) return INVALID_JSON;
        try {
            JsonNode root = JSON.readTree(response);
            if (root == null || root.isMissingNode()) return INVALID_JSON;
            return removeExcluded(root, policy, aql) ? JSON.writeValueAsString(root) : response;
        } catch (JsonProcessingException invalid) {
            // Never echo an unparseable response that may contain excluded data.
            return INVALID_JSON;
        }
    }

    public static boolean excludedData(JsonNode data, WikiPrivacyPolicy policy) {
        if (data == null || !data.isObject()) return false;
        return policy.excluded(data.path("app").asText(null), data.path("title").asText(null),
                data.path("url_host").asText(null), data.path("private_browsing").asBoolean(false))
                || policy.excludedTitle(data.path("context_title").asText(null));
    }

    private static boolean removeExcluded(JsonNode node, WikiPrivacyPolicy policy, boolean aql) {
        boolean changed = false;
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            JsonNode child = children.next();
            if (node.isArray() && child.isObject()
                    && (excludedData(child.get("data"), policy)
                    || (aql && child.has("timestamp") && child.has("duration") && excludedData(child, policy)))) {
                children.remove();
                changed = true;
            } else {
                changed |= removeExcluded(child, policy, aql);
            }
        }
        return changed;
    }
}
