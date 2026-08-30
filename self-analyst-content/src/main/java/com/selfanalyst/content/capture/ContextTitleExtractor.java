package com.selfanalyst.content.capture;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Extracts app-specific semantic context from flattened accessibility text. */
public final class ContextTitleExtractor {

    private static final Set<String> WEIXIN_PROCESSES = Set.of(
            "weixin.exe", "wechat.exe");
    private static final Set<String> CHAT_HISTORY_ANCHORS = Set.of(
            "聊天记录", "聊天記錄");
    private static final Set<String> HEADER_COMPANIONS = Set.of(
            "从手机导入聊天记录", "從手機匯入聊天記錄",
            "语音通话", "語音通話", "视频通话", "視訊通話",
            "聊天信息", "聊天資訊");
    private static final Set<String> GENERIC_LABELS = Set.of(
            "微信", "weixin", "wechat", "更多", "聊天记录", "聊天記錄",
            "搜索", "返回", "前进", "刷新", "菜单", "设置", "确定", "取消",
            "发送", "分享", "收藏", "阅读原文", "点赞", "在看",
            "search", "back", "forward", "refresh", "menu", "settings", "ok",
            "cancel", "send", "share", "favorite", "favourites");
    private static final java.util.regex.Pattern PURE_URL = java.util.regex.Pattern.compile(
            "(?i)^(?:[a-z][a-z0-9+.-]*:|www\\.)\\S+$");
    private static final int MAX_TITLE_CODE_POINTS = 200;
    private static final int COMPANION_LOOKAHEAD = 3;

    private ContextTitleExtractor() {}

    /**
     * Extract the active Weixin/WeChat conversation title from UIA text.
     *
     * <p>The current desktop client exposes the active conversation heading
     * immediately before the chat-history control. Requiring another known
     * header control shortly after that anchor prevents matching message-body
     * text that happens to contain the same words.</p>
     */
    static String extract(String app, String uiaText) {
        ContextTitleCandidate candidate = extractCandidate(app, uiaText);
        return candidate != null ? candidate.value() : null;
    }

    public static ContextTitleCandidate extractCandidate(String app, String uiaText) {
        if (!supports(app) || uiaText == null || uiaText.isBlank()) return null;

        List<String> lines = uiaText.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        for (int i = 1; i < lines.size(); i++) {
            if (!CHAT_HISTORY_ANCHORS.contains(lines.get(i))) continue;
            if (!hasHeaderCompanion(lines, i + 1)) continue;

            String candidate = lines.get(i - 1);
            if (isValidTitleCandidate(candidate)) {
                return new ContextTitleCandidate(
                        candidate, "chat", "uia_context", "high");
            }
        }
        return null;
    }

    /** Build a document/article title candidate without retaining the source tree or text. */
    public static ContextTitleCandidate fromDocumentTitle(String app, String documentTitle) {
        if (!isValidTitleCandidate(documentTitle)) return null;
        return new ContextTitleCandidate(
                documentTitle,
                supports(app) ? "article" : "document",
                "uia_document",
                "high");
    }

    public static boolean supports(String app) {
        if (app == null || app.isBlank()) return false;
        return WEIXIN_PROCESSES.contains(app.strip().toLowerCase(Locale.ROOT));
    }

    public static boolean isChatSurface(String uiaText) {
        return uiaText != null && uiaText.lines()
                .map(String::strip)
                .anyMatch(CHAT_HISTORY_ANCHORS::contains);
    }

    private static boolean hasHeaderCompanion(List<String> lines, int fromIndex) {
        int toIndex = Math.min(lines.size(), fromIndex + COMPANION_LOOKAHEAD);
        for (int i = fromIndex; i < toIndex; i++) {
            if (HEADER_COMPANIONS.contains(lines.get(i))) return true;
        }
        return false;
    }

    public static boolean isValidTitleCandidate(String candidate) {
        if (candidate == null || candidate.isBlank()
                || candidate.contains("\n") || candidate.contains("\r")) return false;
        long sentenceTerminators = candidate.codePoints()
                .filter(cp -> cp == '。' || cp == '！' || cp == '？' || cp == '!' || cp == '?')
                .count();
        return !PURE_URL.matcher(candidate.strip()).matches()
                && !GENERIC_LABELS.contains(candidate.toLowerCase(Locale.ROOT))
                && !CHAT_HISTORY_ANCHORS.contains(candidate)
                && !HEADER_COMPANIONS.contains(candidate)
                && sentenceTerminators < 2
                && candidate.codePointCount(0, candidate.length()) <= MAX_TITLE_CODE_POINTS;
    }
}
