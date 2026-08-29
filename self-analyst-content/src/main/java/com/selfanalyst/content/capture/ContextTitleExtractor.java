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
            "微信", "weixin", "wechat", "更多", "聊天记录", "聊天記錄");
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
        if (!supports(app) || uiaText == null || uiaText.isBlank()) return null;

        List<String> lines = uiaText.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        for (int i = 1; i < lines.size(); i++) {
            if (!CHAT_HISTORY_ANCHORS.contains(lines.get(i))) continue;
            if (!hasHeaderCompanion(lines, i + 1)) continue;

            String candidate = lines.get(i - 1);
            if (isValidTitle(candidate)) return candidate;
        }
        return null;
    }

    public static boolean supports(String app) {
        if (app == null || app.isBlank()) return false;
        return WEIXIN_PROCESSES.contains(app.strip().toLowerCase(Locale.ROOT));
    }

    private static boolean hasHeaderCompanion(List<String> lines, int fromIndex) {
        int toIndex = Math.min(lines.size(), fromIndex + COMPANION_LOOKAHEAD);
        for (int i = fromIndex; i < toIndex; i++) {
            if (HEADER_COMPANIONS.contains(lines.get(i))) return true;
        }
        return false;
    }

    private static boolean isValidTitle(String candidate) {
        return !candidate.isBlank()
                && !GENERIC_LABELS.contains(candidate.toLowerCase(Locale.ROOT))
                && !CHAT_HISTORY_ANCHORS.contains(candidate)
                && !HEADER_COMPANIONS.contains(candidate)
                && candidate.codePointCount(0, candidate.length()) <= MAX_TITLE_CODE_POINTS;
    }
}
