package com.selfanalyst.wiki;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Redaction and exclusion applied before a title can become model evidence. */
public final class WikiPrivacyPolicy {
    private static final Pattern PRIVATE_IP = Pattern.compile(
            "\\b(?:10(?:\\.\\d{1,3}){3}|192\\.168(?:\\.\\d{1,3}){2}|172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2})\\b");
    private static final Pattern MEETING = Pattern.compile("会议号\\s*[:：]\\s*[\\d ]{6,}");
    private static final Pattern AUTH = Pattern.compile(
            "登录验证|验证你的身份|邮箱验证|身份验证器|添加密码|auth\\.openai\\.com", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_BROWSING = Pattern.compile(
            "无痕|InPrivate|Incognito|隐私浏览|Private Browsing", Pattern.CASE_INSENSITIVE);

    private final Set<String> apps;
    private final Set<String> sites;

    private WikiPrivacyPolicy(Set<String> apps, Set<String> sites) {
        this.apps = apps;
        this.sites = sites;
    }

    public static WikiPrivacyPolicy none() { return new WikiPrivacyPolicy(Set.of(), Set.of()); }

    public static WikiPrivacyPolicy of(String appsCsv, String sitesCsv) {
        return new WikiPrivacyPolicy(split(appsCsv), split(sitesCsv));
    }

    public String redact(String title) {
        if (title == null || title.isBlank()) return title;
        if (AUTH.matcher(title).find()) return "账号验证页面";
        String redacted = PRIVATE_IP.matcher(title).replaceAll("[内网地址]");
        return MEETING.matcher(redacted).replaceAll("会议号：[已隐藏]");
    }

    public boolean excluded(String app, String title) {
        if (title != null && PRIVATE_BROWSING.matcher(title).find()) return true;
        if (app != null && apps.contains(app.toLowerCase(Locale.ROOT))) return true;
        if (title == null || sites.isEmpty()) return false;
        String lower = title.toLowerCase(Locale.ROOT);
        return sites.stream().anyMatch(lower::contains);
    }

    private static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split("[,;]")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
