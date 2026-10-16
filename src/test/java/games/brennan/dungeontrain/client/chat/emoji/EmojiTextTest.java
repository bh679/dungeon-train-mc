package games.brennan.dungeontrain.client.chat.emoji;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiTextTest {

    /** Custom emoji source stub: {@code 111111} is loaded at slot U+100000; everything else is requested. */
    private static final class Stub implements EmojiText.CustomSource {
        final Set<String> requested = new HashSet<>();

        @Override
        public OptionalInt slot(String id) {
            return "111111".equals(id) ? OptionalInt.of(0x100000) : OptionalInt.empty();
        }

        @Override
        public void request(String id) {
            requested.add(id);
        }
    }

    private record Piece(Style style, String text) {}

    private static List<Piece> pieces(Component c) {
        List<Piece> out = new ArrayList<>();
        c.visit((style, text) -> {
            if (!text.isEmpty()) out.add(new Piece(style, text));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static EmojiText.Result convert(Component c, Stub stub) {
        return EmojiText.convert(c, EmojiIndex.get(), stub);
    }

    @Test
    @DisplayName("text without emoji comes back as the same instance")
    void untouched() {
        Component c = Component.literal("hello, café");
        assertSame(c, convert(c, new Stub()).component());
    }

    @Test
    @DisplayName("a unicode emoji moves into the emoji font, drawn white; the text around it keeps its style")
    void unicode() {
        Style click = Style.EMPTY.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/x"));
        List<Piece> p = pieces(convert(Component.literal("hi 😀!").withStyle(click), new Stub()).component());
        assertEquals(3, p.size());
        assertEquals("hi ", p.get(0).text());
        assertEquals(ChatFormatting.AQUA.getColor(), p.get(0).style().getColor().getValue());
        assertEquals(EmojiText.EMOJI_FONT, p.get(1).style().getFont());
        assertEquals(0xFFFFFF, p.get(1).style().getColor().getValue());
        assertEquals(click.getClickEvent(), p.get(1).style().getClickEvent(), "the click survives on the emoji");
        assertEquals("!", p.get(2).text());
    }

    @Test
    @DisplayName("a loaded custom emoji draws its slot; an unloaded one shows :name: and is fetched")
    void custom() {
        Stub stub = new Stub();
        EmojiText.Result r = convert(Component.literal("a <:train:111111> b <a:party:222222>"), stub);
        List<Piece> p = pieces(r.component());
        assertTrue(p.stream().anyMatch(x -> EmojiText.DISCORD_EMOJI_FONT.equals(x.style().getFont())
            && x.text().codePointAt(0) == 0x100000));
        assertTrue(p.stream().anyMatch(x -> x.text().equals(":party:")));
        assertEquals(Set.of("222222"), r.pendingIds());
        assertEquals(Set.of("222222"), stub.requested);
    }

    @Test
    @DisplayName("a raw Discord mention reads @dev")
    void mention() {
        assertEquals("ping @dev now", convert(Component.literal("ping <@342110421114945537> now"), new Stub())
            .component().getString());
    }

    @Test
    @DisplayName(":shortcode: expands before sending; unknown names and lone colons are left alone")
    void shortcodes() {
        assertEquals("nice 🔥 run 👍", EmojiText.expandShortcodes("nice :fire: run :+1:"));
        assertEquals("time 12:30:45 :nope_not_real:", EmojiText.expandShortcodes("time 12:30:45 :nope_not_real:"));
    }
}
