package com.selfanalyst.content.capture;

import com.selfanalyst.content.uia.UiaNode;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Limited browser UIA projection: host name and private-browsing markers only. */
public final class BrowserChromeProbe {
    private static final Set<String> BROWSERS = Set.of(
            "chrome.exe", "msedge.exe", "firefox.exe", "opera.exe", "brave.exe", "vivaldi.exe",
            "chrome", "msedge", "firefox", "opera", "brave", "vivaldi");
    private static final int EDIT = 50004;
    private static final Pattern PRIVATE = Pattern.compile(
            "无痕|隐身|InPrivate|Incognito|隐私浏览|Private Browsing|Private", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_LIKE = Pattern.compile(
            "(?i)^(?:[a-z][a-z0-9+.-]*://)?([^\\s/?#:]+)(?::\\d+)?(?:[/?#].*)?$");

    private BrowserChromeProbe() {}

    public record Projection(String urlHost, boolean privateBrowsing) {}

    public static boolean supports(String app) {
        if (app == null || app.isBlank()) return false;
        String lower = app.strip().toLowerCase(Locale.ROOT);
        return BROWSERS.stream().anyMatch(lower::contains);
    }

    public static Projection probe(UiaNode root, String uiaText) {
        boolean privateBrowsing = PRIVATE.matcher(uiaText == null ? "" : uiaText).find()
                || containsPrivate(root);
        String host = firstHost(root);
        if (host == null && uiaText != null) {
            for (String line : uiaText.lines().map(String::strip).filter(s -> !s.isEmpty()).toList()) {
                host = hostOf(line);
                if (host != null) break;
            }
        }
        if (host == null && !privateBrowsing) return null;
        return new Projection(host, privateBrowsing);
    }

    private static boolean containsPrivate(UiaNode node) {
        if (node == null) return false;
        if (PRIVATE.matcher(safe(node.name())).find() || PRIVATE.matcher(safe(node.value())).find()) return true;
        for (UiaNode child : node.children()) if (containsPrivate(child)) return true;
        return false;
    }

    private static String firstHost(UiaNode node) {
        if (node == null) return null;
        if (node.controlType() == EDIT || looksLikeAddress(node)) {
            String host = hostOf(node.value());
            if (host == null) host = hostOf(node.name());
            if (host != null) return host;
        }
        for (UiaNode child : node.children()) {
            String host = firstHost(child);
            if (host != null) return host;
        }
        return null;
    }

    private static boolean looksLikeAddress(UiaNode node) {
        String name = safe(node.name()).toLowerCase(Locale.ROOT);
        return name.contains("address") || name.contains("url") || name.contains("omnibox")
                || name.contains("地址") || name.contains("搜索与网址");
    }

    static String hostOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.strip();
        try {
            URI uri = URI.create(text.contains("://") ? text : "https://" + text);
            String host = uri.getHost();
            if (host != null && !host.isBlank() && host.indexOf('.') >= 0) {
                return host.toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to regex
        }
        Matcher matcher = HOST_LIKE.matcher(text);
        if (!matcher.matches()) return null;
        String host = matcher.group(1).toLowerCase(Locale.ROOT);
        if (host.contains("@")) host = host.substring(host.lastIndexOf('@') + 1);
        return host.indexOf('.') >= 0 && !host.contains(" ") ? host : null;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
