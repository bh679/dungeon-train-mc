package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.client.version.compare.FullSemver;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The once-per-game outdated chat line: which lang keys, plural form, colours and link it carries. */
class OutdatedVersionNoticeTest {

    private static final String URL = "https://brennan.games/dungeontrain/update/?v=0.1137.0&from=modrinth";

    private static FullSemver v(String s) {
        return FullSemver.parse(s).orElseThrow();
    }

    private static Component msg(String locale, int behind) {
        return OutdatedVersionNotice.message(locale, behind, v("0.1137.0"), v("0.1160.0"), URL);
    }

    @Test
    void prefixIsGoldDungeonTrainTag() {
        Component m = msg("en_us", 23);
        TranslatableContents prefix = (TranslatableContents) m.getContents();
        assertEquals("chat.dungeontrain.bug_response.prefix", prefix.getKey());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), m.getStyle().getColor());
    }

    @Test
    void lineUsesPluralFormAndYellowCount() {
        Component line = msg("en_us", 23).getSiblings().get(1);
        TranslatableContents tc = (TranslatableContents) line.getContents();
        assertEquals("gui.dungeontrain.bug_response.outdated.other", tc.getKey());
        Component count = (Component) tc.getArgs()[0];
        assertEquals("23", count.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), count.getStyle().getColor());
        assertEquals("0.1137.0", tc.getArgs()[1]);
        assertEquals("0.1160.0", tc.getArgs()[2]);
    }

    @Test
    void singularAndSlavicForms() {
        assertEquals("gui.dungeontrain.bug_response.outdated.one",
                ((TranslatableContents) msg("en_us", 1).getSiblings().get(1).getContents()).getKey());
        assertEquals("gui.dungeontrain.bug_response.outdated.few",
                ((TranslatableContents) msg("ru_ru", 3).getSiblings().get(1).getContents()).getKey());
    }

    @Test
    void linkOpensUpdatePage() {
        List<Component> parts = msg("en_us", 23).getSiblings();
        Component link = parts.get(parts.size() - 1);
        ClickEvent click = link.getStyle().getClickEvent();
        assertEquals(ClickEvent.Action.OPEN_URL, click.getAction());
        assertEquals(URL, click.getValue());
        assertTrue(link.getStyle().isUnderlined());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), link.getStyle().getColor());
        TranslatableContents label = (TranslatableContents) link.getSiblings().get(0).getContents();
        assertEquals("gui.dungeontrain.death.update_button", label.getKey());
        assertEquals("0.1160.0", label.getArgs()[0]);
    }

    @Test
    void gameKeysSeparateWorldsAndServers() {
        assertNotEquals(OutdatedVersionNotice.singleplayerKey("New World", 1L),
                OutdatedVersionNotice.singleplayerKey("New World", 2L));
        assertNotEquals(OutdatedVersionNotice.singleplayerKey("x", 1L), OutdatedVersionNotice.multiplayerKey("x"));
        assertEquals("mp:play.example.net", OutdatedVersionNotice.multiplayerKey("play.example.net"));
    }
}
