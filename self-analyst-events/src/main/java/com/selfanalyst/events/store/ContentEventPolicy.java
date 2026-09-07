package com.selfanalyst.events.store;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

/** Field allowlist for persisted title-only content events. */
public final class ContentEventPolicy {

    public static final String CONTENT_BUCKET_PREFIX = "watcher-content_";
    public static final String LEGACY_CONTENT_BUCKET_PREFIX = "aw-watcher-content_";
    public static final String CONTENT_CLIENT = "watcher-content";
    public static final String LEGACY_CONTENT_CLIENT = "aw-watcher-content";

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "schema_version",
            "app",
            "title",
            "context_title",
            "context_kind",
            "title_source",
            "title_confidence",
            "uia_chars",
            "ocr_chars");
    private static final Set<String> KNOWN_FORBIDDEN_FIELDS = Set.of(
            "text_content", "uia_text", "ocr_text", "raw_text", "raw_tree",
            "content", "body", "sample_id", "screenshot", "image");
    private static final Set<String> TITLE_SOURCES = Set.of(
            "window", "uia_document", "uia_context", "ocr_title");
    private static final Set<String> CONTEXT_KINDS = Set.of(
            "chat", "article", "document", "page", "unknown");
    private static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");
    private static final Set<String> GENERIC_CONTEXT_TITLES = Set.of(
            "微信", "weixin", "wechat", "更多", "聊天记录", "聊天記錄",
            "搜索", "返回", "前进", "刷新", "菜单", "设置", "确定", "取消",
            "发送", "分享", "收藏", "阅读原文", "点赞", "在看",
            "search", "back", "forward", "refresh", "menu", "settings", "ok",
            "cancel", "send", "share", "favorite", "favourites");
    private static final Pattern PURE_URL = Pattern.compile(
            "(?i)^(?:[a-z][a-z0-9+.-]*:|www\\.)\\S+$");

    private ContentEventPolicy() {}

    public static boolean isContentBucket(String bucketId, String client) {
        return matchesContentBucketId(bucketId) || matchesContentClient(client);
    }

    public static boolean matchesContentBucketId(String bucketId) {
        return bucketId != null
                && (bucketId.startsWith(CONTENT_BUCKET_PREFIX)
                || bucketId.startsWith(LEGACY_CONTENT_BUCKET_PREFIX));
    }

    public static boolean matchesContentClient(String client) {
        return CONTENT_CLIENT.equalsIgnoreCase(client)
                || LEGACY_CONTENT_CLIENT.equalsIgnoreCase(client);
    }

    public static void validate(String bucketId, String client, Map<String, Object> data) {
        if (!isContentBucket(bucketId, client)) return;
        if (data == null) throw violation("data", "missing data object");

        Set<String> unexpected = new LinkedHashSet<>(data.keySet());
        unexpected.removeAll(ALLOWED_FIELDS);
        if (!unexpected.isEmpty()) {
            String reportedFields = KNOWN_FORBIDDEN_FIELDS.containsAll(unexpected)
                    ? String.join(",", unexpected)
                    : "unknown";
            throw violation(reportedFields, "forbidden content event field");
        }

        Object schemaVersion = data.get("schema_version");
        if (!(schemaVersion instanceof Number number) || number.doubleValue() != 2.0) {
            throw violation("schema_version", "content event schema_version must be 2");
        }
        requireBoundedSingleLine(data, "app", 260);
        requireBoundedSingleLine(data, "title", 1024);
        String source = requireString(data, "title_source", false);
        if (!TITLE_SOURCES.contains(source)) {
            throw violation("title_source", "unsupported title source");
        }

        String contextTitle = optionalString(data, "context_title");
        if (contextTitle != null) {
            if (contextTitle.contains("\n") || contextTitle.contains("\r")
                    || contextTitle.codePointCount(0, contextTitle.length()) > 200
                    || PURE_URL.matcher(contextTitle.strip()).matches()
                    || GENERIC_CONTEXT_TITLES.contains(contextTitle.toLowerCase(Locale.ROOT))
                    || sentenceTerminatorCount(contextTitle) >= 2) {
                throw violation("context_title", "context title must be single-line and at most 200 code points");
            }
            String kind = requireString(data, "context_kind", false);
            if (!CONTEXT_KINDS.contains(kind)) {
                throw violation("context_kind", "unsupported context kind");
            }
            String confidence = optionalString(data, "title_confidence");
            if (confidence != null && !CONFIDENCES.contains(confidence)) {
                throw violation("title_confidence", "unsupported title confidence");
            }
        } else if (data.containsKey("context_kind") || data.containsKey("title_confidence")) {
            throw violation("context_title", "context metadata requires context_title");
        }

        requireNonNegativeNumber(data, "uia_chars");
        requireNonNegativeNumber(data, "ocr_chars");
    }

    private static String requireString(Map<String, Object> data, String key, boolean allowEmpty) {
        if (!data.containsKey(key) || !(data.get(key) instanceof String value)) {
            throw violation(key, "required string field");
        }
        if (!allowEmpty && value.isBlank()) {
            throw violation(key, "field must not be blank");
        }
        return value;
    }

    private static void requireBoundedSingleLine(
            Map<String, Object> data, String key, int maxCodePoints) {
        String value = requireString(data, key, true);
        if (value.contains("\n") || value.contains("\r")
                || value.codePointCount(0, value.length()) > maxCodePoints) {
            throw violation(key, "field must be single-line and within its size limit");
        }
    }

    private static String optionalString(Map<String, Object> data, String key) {
        if (!data.containsKey(key)) return null;
        if (!(data.get(key) instanceof String value) || value.isBlank()) {
            throw violation(key, "optional field must be a non-blank string when present");
        }
        return value;
    }

    private static void requireNonNegativeNumber(Map<String, Object> data, String key) {
        if (!data.containsKey(key)) return;
        Object value = data.get(key);
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw violation(key, "field must be a non-negative number");
        }
    }

    private static long sentenceTerminatorCount(String value) {
        return value.codePoints()
                .filter(cp -> cp == '。' || cp == '！' || cp == '？' || cp == '!' || cp == '?')
                .count();
    }

    private static ContentEventPolicyViolationException violation(String field, String message) {
        return new ContentEventPolicyViolationException(field, message);
    }
}
