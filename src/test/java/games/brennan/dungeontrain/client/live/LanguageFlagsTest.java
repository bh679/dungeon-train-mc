package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFlagsTest {

    @Test
    @DisplayName("a language tag to its flag; the region when it picks one; none for the rest")
    void codeOf() {
        assertEquals("gb", LanguageFlags.codeOf("en"));
        assertEquals("gb", LanguageFlags.codeOf("en_us"));
        assertEquals("es", LanguageFlags.codeOf("es"));
        assertEquals("mx", LanguageFlags.codeOf("es-MX"));
        assertEquals("cn", LanguageFlags.codeOf("zh-CN"));
        assertEquals("tw", LanguageFlags.codeOf("zh_tw"));
        assertEquals("tw", LanguageFlags.codeOf("zh-Hant"));
        assertEquals("pt", LanguageFlags.codeOf("pt"));
        assertEquals("br", LanguageFlags.codeOf("pt_br"));
        assertEquals("ua", LanguageFlags.codeOf("uk"));
        assertEquals("ph", LanguageFlags.codeOf("fil"));
        assertNull(LanguageFlags.codeOf("xx"));
        assertNull(LanguageFlags.codeOf(null));
    }

    @Test
    @DisplayName("the flag is one glyph of the font, with the original message on hover")
    void flag() {
        MutableComponent c = LanguageFlags.flag("es", "hola amigos");
        assertNotNull(c);
        assertEquals(String.valueOf((char) (LanguageFlags.FIRST_GLYPH + LanguageFlags.CODES.indexOf("es"))), c.getString());
        assertEquals(LanguageFlags.FONT, c.getStyle().getFont());
        HoverEvent hover = c.getStyle().getHoverEvent();
        assertNotNull(hover);
        assertEquals("hola amigos", hover.getValue(HoverEvent.Action.SHOW_TEXT).getString());
        assertNull(LanguageFlags.flag("xx", "hola"));
        assertNull(LanguageFlags.flag(null, "hola"));
    }

    @Test
    @DisplayName("a line's reach: sorted, one per flag, not its own language or the reader's, three then the rest")
    void reachOf() {
        Map<String, String> tr = Map.of("ja_jp", "a", "de_de", "b", "en_us", "c", "en_gb", "d", "fr_fr", "e", "es_es", "f", "ko_kr", "g", "xx_yy", "h");
        LanguageFlags.Reach reach = LanguageFlags.reachOf(tr, "es", "fr_fr");
        assertEquals(List.of("de_de", "en_gb", "ja_jp"), reach.flagged());
        assertEquals(List.of("ko_kr", "xx_yy"), reach.rest());
    }

    @Test
    @DisplayName("nothing reached, no flags: no translations, or only the reader's and the line's own language")
    void reachNone() {
        assertNull(LanguageFlags.reach(null, "en", "en_us"));
        assertNull(LanguageFlags.reach(Map.of(), "en", "en_us"));
        assertNull(LanguageFlags.reach(Map.of("en_gb", "hi", "es_es", "hola"), "es", "en_us"));
    }

    @Test
    @DisplayName("each flag shows its translation on hover, and +N names the rest")
    void reachShows() {
        MutableComponent c = LanguageFlags.reach(Map.of("de_de", "hallo", "es_es", "hola", "fr_fr", "salut", "ja_jp", "konnichiwa"), "en", "en_us");
        assertNotNull(c);
        List<Component> parts = c.getSiblings();
        assertEquals("hallo", parts.get(0).getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString());
        Component more = parts.get(parts.size() - 1);
        assertEquals("+1", more.getString());
        assertEquals(LiveChatTranslations.langName("ja_jp"), more.getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString());
    }

    @Test
    @DisplayName("the font make-flags.py wrote gives each code its glyph, in CODES' order, and each has a texture")
    void fontMatchesCodes() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/dungeontrain/font/language_flags.json")) {
            assertNotNull(in, "run scripts/language-flags/make-flags.py");
            JsonArray providers = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("providers");
            assertEquals(LanguageFlags.CODES.size(), providers.size());
            for (int i = 0; i < providers.size(); i++) {
                JsonObject p = providers.get(i).getAsJsonObject();
                String code = LanguageFlags.CODES.get(i);
                assertEquals("dungeontrain:font/language_flags/" + code + ".png", p.get("file").getAsString());
                assertEquals(String.valueOf((char) (LanguageFlags.FIRST_GLYPH + i)), p.getAsJsonArray("chars").get(0).getAsString());
                assertTrue(getClass().getResource("/assets/dungeontrain/textures/font/language_flags/" + code + ".png") != null, code);
            }
        }
    }
}
