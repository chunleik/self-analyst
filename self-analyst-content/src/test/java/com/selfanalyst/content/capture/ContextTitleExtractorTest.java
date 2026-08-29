package com.selfanalyst.content.capture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextTitleExtractorTest {

    @Test
    void extractsCurrentWeixinConversationTitleFromUiaText() {
        String uiaText = """
                微信
                MMUIRenderSubWindowHW
                微信
                通讯录
                收藏
                发现
                手机
                手机
                更多
                更多
                徐工3期小分队(3)
                聊天记录
                从手机导入聊天记录
                语音通话
                聊天信息
                """;

        assertEquals("徐工3期小分队(3)",
                ContextTitleExtractor.extract("Weixin.exe", uiaText));
    }

    @Test
    void supportsLegacyWeChatProcessName() {
        String uiaText = "微信\n文件传输助手\n聊天记录\n聊天信息";

        assertEquals("文件传输助手",
                ContextTitleExtractor.extract("WeChat.exe", uiaText));
    }

    @Test
    void ignoresSameTextForOtherApplications() {
        String uiaText = "微信\n徐工3期小分队(3)\n聊天记录\n聊天信息";

        assertNull(ContextTitleExtractor.extract("notes.exe", uiaText));
    }

    @Test
    void doesNotGuessWhenWeixinHeaderAnchorIsMissing() {
        String uiaText = "微信\n徐工3期小分队(3)\n星期四 20:02\n准备走了";

        assertNull(ContextTitleExtractor.extract("Weixin.exe", uiaText));
    }

    @Test
    void ignoresGenericWindowLabelBeforeHeaderAnchor() {
        String uiaText = "微信\n更多\n聊天记录\n聊天信息";

        assertNull(ContextTitleExtractor.extract("Weixin.exe", uiaText));
    }

    @Test
    void ignoresKnownHeaderControlsBeforeHeaderAnchor() {
        for (String label : List.of(
                "聊天记录", "从手机导入聊天记录", "语音通话", "视频通话", "聊天信息")) {
            String uiaText = "微信\n" + label + "\n聊天记录\n语音通话";

            assertNull(ContextTitleExtractor.extract("Weixin.exe", uiaText), label);
        }
    }

    @Test
    void requiresHeaderCompanionWithinThreeNonEmptyLines() {
        String noCompanion = "微信\n项目群\n聊天记录\n正文";
        String companionTooLate = "微信\n项目群\n聊天记录\n一\n二\n三\n聊天信息";

        assertNull(ContextTitleExtractor.extract("Weixin.exe", noCompanion));
        assertNull(ContextTitleExtractor.extract("Weixin.exe", companionTooLate));
    }

    @Test
    void enforcesTwoHundredCodePointTitleLimit() {
        String accepted = "群".repeat(200);
        String rejected = accepted + "群";

        assertEquals(accepted, ContextTitleExtractor.extract(
                "Weixin.exe", "微信\n" + accepted + "\n聊天记录\n聊天信息"));
        assertNull(ContextTitleExtractor.extract(
                "Weixin.exe", "微信\n" + rejected + "\n聊天记录\n聊天信息"));
    }
}
