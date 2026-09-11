package com.selfanalyst.i18n;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test void missingLanguageUsesEnglishAndUnknownKeyRemainsVisible() {
        var french = new Lang("fr", "Français", "fr-FR", "fr");
        assertEquals("New chat", Messages.text(french, "chat.new"));
        assertEquals("unknown.key", Messages.text(french, "unknown.key"));
    }
    @Test void parametersAreNotReinterpreted() {
        assertEquals("{other} $1 \\ <script>", Messages.render("{value}",
                Map.of("value", "{other} $1 \\ <script>", "other", "changed")));
    }
}
