package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveChatTranslationsTest {

    private static final ChatLine LINE = new ChatLine(7, 1000L, "Alice", "aa", "the creeper got me", Source.GAME);

    @Test
    @DisplayName("the reader's language is Minecraft's locale code, normalised; anything else is nothing")
    void readerLang() {
        assertEquals("zh_cn", LiveChatTranslations.readerLang("zh_cn"));
        assertEquals("pt_br", LiveChatTranslations.readerLang(" PT-BR "));
        assertNull(LiveChatTranslations.readerLang("english"));
        assertNull(LiveChatTranslations.readerLang(""));
        assertNull(LiveChatTranslations.readerLang(null));
    }

    @Test
    @DisplayName("pick: the exact locale first, then the same language, else nothing")
    void pick() {
        Map<String, String> tr = Map.of("zh_cn", "简体", "pt_br", "oi", "de_de", " ");
        assertEquals("简体", LiveChatTranslations.pick(tr, "zh_cn"));
        assertEquals("简体", LiveChatTranslations.pick(tr, "zh_tw"), "same language, other script, better than none");
        assertEquals("oi", LiveChatTranslations.pick(tr, "pt_pt"));
        assertNull(LiveChatTranslations.pick(tr, "de_de"), "a blank is no translation");
        assertNull(LiveChatTranslations.pick(tr, "fr_fr"));
        assertNull(LiveChatTranslations.pick(null, "fr_fr"));
        assertNull(LiveChatTranslations.pick(tr, null));
    }

    @Test
    @DisplayName("under: the translation alone right under its line, named when another line came between")
    void underLine() {
        MutableComponent adjacent = LiveChatTranslations.underLine(LINE, "苦力怕§c炸了我", true);
        assertEquals("chat.dungeontrain.live_chat.translation", key(adjacent));
        assertEquals("苦力怕炸了我", adjacent.getSiblings().isEmpty() ? arg(adjacent, 0) : "", "cleaned");
        MutableComponent named = LiveChatTranslations.underLine(LINE, "x", false);
        assertEquals("chat.dungeontrain.live_chat.translation.named", key(named));
        assertEquals("Alice", arg(named, 0));
    }

    @Test
    @DisplayName("replace: the same line with the translation as its text and the original on hover")
    void replacedLine() {
        MutableComponent c = LiveChatTranslations.replacedLine(LINE, "苦力怕炸了我");
        assertEquals("chat.dungeontrain.live_chat.prefix  Alice: 苦力怕炸了我", c.getString());
        var text = c.getSiblings().get(c.getSiblings().size() - 1);
        HoverEvent hover = text.getStyle().getHoverEvent();
        assertNotNull(hover);
        assertEquals("chat.dungeontrain.live_chat.translation.original", key((MutableComponent) hover.getValue(HoverEvent.Action.SHOW_TEXT)));
    }

    @Test
    @DisplayName("a language's own name for the notice; the code when the JDK has none")
    void langName() {
        assertEquals("Deutsch", LiveChatTranslations.langName("de_de"));
        assertEquals("中文", LiveChatTranslations.langName("zh_cn"));
        assertFalse(LiveChatTranslations.langName("xx_yy").isBlank());
    }

    private static String key(MutableComponent c) {
        return ((net.minecraft.network.chat.contents.TranslatableContents) c.getContents()).getKey();
    }

    private static String arg(MutableComponent c, int i) {
        return ((net.minecraft.network.chat.Component) ((net.minecraft.network.chat.contents.TranslatableContents) c.getContents()).getArgs()[i]).getString();
    }
}
