package games.brennan.dungeontrain.client.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
    @DisplayName("the flag is one glyph of the font, naming its language on hover in the reader's language")
    void flag() {
        MutableComponent c = LanguageFlags.flag("es", "en_us");
        assertNotNull(c);
        assertEquals(String.valueOf((char) (LanguageFlags.FIRST_GLYPH + LanguageFlags.CODES.indexOf("es"))), c.getString());
        assertEquals(LanguageFlags.FONT, c.getStyle().getFont());
        HoverEvent hover = c.getStyle().getHoverEvent();
        assertNotNull(hover);
        assertEquals("Spanish", hover.getValue(HoverEvent.Action.SHOW_TEXT).getString());
        assertEquals("Spanisch", LanguageFlags.nameOf("es", "de_de"));
        assertNull(LanguageFlags.flag("xx", "en_us"));
        assertNull(LanguageFlags.flag(null, "en_us"));
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
