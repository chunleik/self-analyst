package com.selfanalyst.wiki;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiPrivacyPolicyTest {
    private final WikiPrivacyPolicy policy = WikiPrivacyPolicy.of("Weixin.exe; QQ.exe", "bilibili, 知乎");

    @Test
    void meetingNumbersAreHiddenInCommonFormats() {
        for (String title : new String[] {"会议号：361 881 114", "会议号 361881114", "会议 ID: 361-881-114",
                "Zoom Meeting ID 842 557 1930", "meeting id#842557193"}) {
            String redacted = policy.redact(title);
            assertTrue(redacted.contains("会议号：[已隐藏]"), title + " -> " + redacted);
            assertFalse(redacted.matches(".*\\d{3}.*"), title + " -> " + redacted);
        }
        assertEquals("订单 12345 已同步", policy.redact("订单 12345 已同步"));
    }

    @Test
    void titleIsReplacedWholeButTextKeepsItsWording() {
        assertEquals("账号验证页面", policy.redact("OpenAI 邮箱验证 - Google Chrome"));
        assertEquals("查看账号验证页面后访问[内网地址]",
                policy.redactText("查看邮箱验证后访问192.168.1.20"));
    }

    @Test
    void excludesPrivateBrowsingConfiguredAppsAndSiteKeywords() {
        assertTrue(policy.excludedTitle("新标签页 - 隐身 - Google Chrome"));
        assertTrue(policy.excludedTitle("邮件 - InPrivate - Microsoft Edge"));
        assertTrue(policy.excludedTitle("Bilibili 视频 - Google Chrome"));
        assertTrue(policy.excludedTitle("知乎 - 有问题上知乎"));
        assertTrue(policy.excludedApp("weixin.exe"));
        assertTrue(policy.excludedApp("qq.exe"));
        assertFalse(policy.excludedApp("Editor"));
        assertFalse(policy.excludedTitle("订单模块设计"));
    }
}
