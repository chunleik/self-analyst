package com.selfanalyst.wiki;

import java.util.List;
import java.util.Set;

/** Versioned, exact title rules. A rule change must bump {@link #VERSION}. */
public final class WikiNoisePolicy {
    public static final String VERSION = "wiki-noise-v1";

    private static final Set<String> SHELL_APPS = Set.of(
            "LockApp.exe", "SearchHost.exe", "ShellHost.exe", "ShellExperienceHost.exe",
            "StartMenuExperienceHost.exe", "SelfAnalyst.exe");
    private static final Set<String> BROWSERS = Set.of("chrome.exe", "msedge.exe", "firefox.exe");
    private static final Set<String> BROWSER_PAGES = Set.of(
            "新标签页", "New Tab", "无标题", "Untitled",
            "新的无痕式标签页", "New Incognito Tab", "InPrivate", "翻译此页？", "Translate this page?");
    private static final List<String> BROWSER_SUFFIXES = List.of(
            " - Google Chrome", " - Microsoft Edge", " - Mozilla Firefox");
    private static final Set<String> EXPLORER_TITLES = Set.of("Program Manager", "系统托盘溢出窗口",
            "System tray overflow window.", "System tray overflow window");

    private WikiNoisePolicy() {}

    public static boolean excluded(String app, String title) {
        if (SHELL_APPS.contains(app)) return true;
        if ("explorer.exe".equals(app) && EXPLORER_TITLES.contains(title)) return true;
        return BROWSERS.contains(app) && BROWSER_PAGES.contains(pageTitle(title));
    }

    private static String pageTitle(String title) {
        String page = title == null ? "" : title;
        for (String suffix : BROWSER_SUFFIXES) {
            if (page.endsWith(suffix)) return page.substring(0, page.length() - suffix.length());
        }
        return page;
    }
}
