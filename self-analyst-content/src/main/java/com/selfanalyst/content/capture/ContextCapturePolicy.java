package com.selfanalyst.content.capture;

import com.selfanalyst.content.uia.UiaNode;

import java.util.List;
import java.util.Set;

/** Privacy exclusions and UIA document-title lookup for context-title capture. */
public final class ContextCapturePolicy {

    private static final int MIN_DOCUMENT_BODY_CHARS = 200;

    private static final Set<String> EXCLUDED_APPS = Set.of(
            "idea64", "idea", "navicat", "explorer", "notepad", "code",
            "chrome", "msedge", "firefox", "opera", "brave", "vivaldi",
            "1password", "keepass", "bitwarden", "dashlane", "enpass", "roboform",
            "credentialuibroker", "consent");
    private static final Set<String> EXCLUDED_TITLE_PATTERNS = Set.of(
            "密码", "password", "凭据", "credential");

    public boolean isExcluded(String app, String title) {
        return isExcludedApp(app) || isExcludedTitle(title);
    }

    boolean isExcludedApp(String app) {
        if (app == null) return false;
        String lower = app.toLowerCase();
        return EXCLUDED_APPS.stream().anyMatch(lower::contains);
    }

    boolean isExcludedTitle(String title) {
        if (title == null || title.isBlank()) return false;
        String lower = title.toLowerCase();
        return EXCLUDED_TITLE_PATTERNS.stream().anyMatch(lower::contains);
    }

    public static String extractVerifiedDocumentTitle(UiaNode root) {
        if (root == null) return null;
        if (root.controlType() == 50030
                && root.name() != null
                && !root.name().isBlank()
                && descendantTextChars(root) >= MIN_DOCUMENT_BODY_CHARS) {
            return root.name().strip();
        }
        List<UiaNode> children = root.children();
        if (children != null) {
            for (UiaNode child : children) {
                String title = extractVerifiedDocumentTitle(child);
                if (title != null) return title;
            }
        }
        return null;
    }

    private static int descendantTextChars(UiaNode node) {
        int total = 0;
        List<UiaNode> children = node.children();
        if (children == null) return 0;
        for (UiaNode child : children) {
            total += textChars(child.name());
            total += textChars(child.value());
            total += descendantTextChars(child);
            if (total >= MIN_DOCUMENT_BODY_CHARS) return total;
        }
        return total;
    }

    private static int textChars(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
