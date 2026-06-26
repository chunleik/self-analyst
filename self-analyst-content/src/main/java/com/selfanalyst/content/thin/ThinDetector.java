package com.selfanalyst.content.thin;

import com.selfanalyst.content.uia.UiaNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ThinDetector implements SPEC-THN rules to decide whether a window
 * is "thin" (rendered as a canvas, or containing very little usable
 * UIA text) and therefore needs OCR fallback.
 * <p>
 * SPEC-THN-002: known canvas app title patterns.
 * SPEC-THN-003: content-density heuristic.
 * SPEC-THN-004: chrome / non-content role classification.
 */
public class ThinDetector {

    public ThinDetector() {
        Set<String> set = new HashSet<>(DEFAULT_EXCLUDED);
        String prop = System.getProperty("ocr.excluded.apps", "");
        if (!prop.isBlank()) {
            for (String token : prop.split(",")) {
                String t = token.trim().toLowerCase();
                if (!t.isEmpty()) set.add(t);
            }
        }
        this.ocrExcludedApps = Set.copyOf(set);
    }

    /** Built-in apps whose UIA tree is rich enough that OCR adds no value. */
    private static final Set<String> DEFAULT_EXCLUDED = Set.of(
        "idea64",              // IntelliJ IDEA
        "idea",
        "navicat",             // Navicat 数据库工具
        "explorer",            // Windows 文件资源管理器
        "notepad",             // Windows 记事本
        "code",                // VS Code
        "chrome",              // Google Chrome
        "msedge",              // Microsoft Edge
        "firefox",             // Mozilla Firefox
        "opera",               // Opera
        "brave",               // Brave
        "vivaldi",             // Vivaldi
        "1password",           // 1Password
        "keepass",             // KeePass / KeePassXC
        "bitwarden",           // Bitwarden
        "dashlane",            // Dashlane
        "enpass",              // Enpass
        "roboform",            // RoboForm
        "credentialuibroker",  // Windows 凭据输入对话框
        "consent"              // UAC 权限提升对话框
    );

    /**
     * Window title substrings that indicate a password/credential dialog.
     * Matched case-insensitively; if any pattern matches, the window is
     * skipped entirely (no UIA walk, no OCR, no heartbeat).
     */
    private static final Set<String> TITLE_EXCLUDED_PATTERNS = Set.of(
        "密码",       // 任意含"密码"的窗口标题（输入密码、修改密码等）
        "password",   // English credential dialogs
        "凭据",        // 凭据管理器 / 输入凭据
        "credential"  // Windows Credential dialogs in English
    );

    /**
     * Effective exclusion set: DEFAULT_EXCLUDED plus any entries from the
     * ocr.excluded.apps system property (comma-separated, case-insensitive).
     */
    private final Set<String> ocrExcludedApps;

    /**
     * SPEC-THN-002: Canvas app title substrings (case-insensitive match).
     */
    private static final Set<String> CANVAS_PATTERNS = Set.of(
        "google docs", "google sheets", "google slides",
        "google drawings", "figma", "excalidraw",
        "miro", "canva", "tldraw"
    );

    /**
     * SPEC-THN-004: ControlType IDs considered "chrome" (UI decoration).
     */
    private static final Set<Integer> CHROME_ROLES = Set.of(
        50000,  // Button
        50011,  // MenuItem
        50010,  // MenuBar
        50009,  // Menu
        50021,  // ToolBar
        50018,  // Tab / TabGroup
        50002,  // CheckBox
        50013,  // RadioButton
        50003,  // ComboBox
        50014,  // ScrollBar
        50015   // Slider
    );

    /**
     * Determine whether the given window is "thin".
     *
     * @param tree  The UIA node tree (may be null or empty).
     * @param app   The application executable name (unused currently).
     * @param title The window title (may be null).
     * @return true if the window should use OCR fallback.
     */
    /**
     * Determine whether the given window is "thin".
     * Uses tree-based character counting for the 100-char threshold.
     * Prefer {@link #isThin(List, String, String, int)} when the
     * already-extracted text length is available.
     */
    /** Returns true if this app is in the exclusion list and should be skipped entirely. */
    public boolean isExcluded(String app) {
        if (app == null) return false;
        String lower = app.toLowerCase();
        for (String excluded : ocrExcludedApps) {
            if (lower.contains(excluded)) return true;
        }
        return false;
    }

    /** Returns true if the window title matches a password/credential pattern and should be skipped entirely. */
    public boolean isTitleExcluded(String title) {
        if (title == null || title.isBlank()) return false;
        String lower = title.toLowerCase();
        for (String pattern : TITLE_EXCLUDED_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    public boolean isThin(List<UiaNode> tree, String app, String title) {
        int totalChars = 0;
        if (tree != null) {
            for (UiaNode node : tree) totalChars += countTotalChars(node);
        }
        return isThin(tree, app, title, totalChars);
    }

    /**
     * Determine whether the given window is "thin".
     *
     * @param tree           The UIA node tree (may be null or empty).
     * @param app            The application executable name.
     * @param title          The window title (may be null).
     * @param extractedChars Length of the already-extracted UIA text.
     *                       This is a more accurate signal than counting
     *                       tree characters, because extractText filters
     *                       out chrome nodes that countTotalChars includes.
     * @return true if the window should use OCR fallback.
     */
    public boolean isThin(List<UiaNode> tree, String app, String title, int extractedChars) {
        // Step 1: Canvas pattern match (SPEC-THN-002) — takes precedence over the
        // excluded-apps short-circuit below: a browser (an "excluded" app) showing
        // Google Docs / Figma renders to a canvas with an empty UIA tree, so OCR is
        // still required. Canvas → thin regardless of app or char count.
        if (title != null && !title.isEmpty()) {
            String lower = title.toLowerCase();
            for (String pattern : CANVAS_PATTERNS) {
                if (lower.contains(pattern)) {
                    return true;
                }
            }
        }

        // Step 0: Excluded apps — UIA is sufficient, skip OCR entirely
        if (app != null) {
            String lower = app.toLowerCase();
            for (String excluded : ocrExcludedApps) {
                if (lower.contains(excluded)) return false;
            }
        }

        // Step 2: Use actual extracted text length for the 100-char threshold.
        // Tree-based counting inflates totals with chrome nodes that
        // extractText discards, causing false negatives (e.g. WXWork).
        // SPEC-THN-003: < 100 extracted chars → thin.
        if (extractedChars < 100) {
            return true;
        }

        // Step 3: Count content characters (non-chrome roles) from tree
        int totalChars = 0;
        int contentChars = 0;
        if (tree != null) {
            for (UiaNode node : tree) {
                totalChars += countTotalChars(node);
                contentChars += countContentChars(node);
            }
        }

        // SPEC-THN-003: content density < 30 % → thin
        if (totalChars > 0 && (double) contentChars / totalChars < 0.3) {
            return true;
        }

        return false;
    }

    /**
     * Searches the UIA tree for a Document node (ControlType=50030) and returns
     * its Name as the internal page/document title.
     * <p>
     * Electron and Tauri apps render via Chromium/WebView2. When the app enables
     * accessibility the WebView exposes a Document element whose Name == the current
     * page title — even though the rest of the UIA tree is too sparse to pass the
     * thin threshold. This method exploits that single node to capture context
     * (what the user is viewing) without any screenshot or OCR.
     *
     * @param tree UIA node tree roots (as returned by UiaTreeWalker)
     * @return first non-blank Document.Name found, or {@code null} if none
     */
    public static String extractDocumentTitle(List<UiaNode> tree) {
        if (tree == null) return null;
        for (UiaNode root : tree) {
            String t = findDocumentName(root);
            if (t != null) return t;
        }
        return null;
    }

    private static String findDocumentName(UiaNode node) {
        if (node == null) return null;
        if (node.controlType() == 50030) {
            String name = node.name();
            if (name != null && !name.isBlank()) return name.trim();
        }
        if (node.children() != null) {
            for (UiaNode child : node.children()) {
                String found = findDocumentName(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Recursively count all characters in name + value of a UiaNode subtree.
     */
    private int countTotalChars(UiaNode node) {
        if (node == null) return 0;
        int count = 0;
        if (node.name() != null) count += node.name().length();
        if (node.value() != null) count += node.value().length();
        if (node.children() != null) {
            for (UiaNode child : node.children()) {
                count += countTotalChars(child);
            }
        }
        return count;
    }

    /**
     * Recursively count content characters (from non-chrome-role nodes).
     */
    private int countContentChars(UiaNode node) {
        if (node == null) return 0;
        int count = 0;
        if (!CHROME_ROLES.contains(node.controlType())) {
            if (node.name() != null) count += node.name().length();
            if (node.value() != null) count += node.value().length();
        }
        if (node.children() != null) {
            for (UiaNode child : node.children()) {
                count += countContentChars(child);
            }
        }
        return count;
    }
}
