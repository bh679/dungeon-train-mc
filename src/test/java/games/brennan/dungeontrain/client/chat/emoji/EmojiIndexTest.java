package games.brennan.dungeontrain.client.chat.emoji;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The shipped Twemoji index: longest-match lookup, variation selectors, text-default symbols, shortcodes. */
class EmojiIndexTest {

    private final EmojiIndex index = EmojiIndex.get();

    @Test
    @DisplayName("the bundled index loads with thousands of emoji")
    void loads() {
        assertFalse(index.isEmpty());
    }

    @Test
    @DisplayName("a plain emoji maps to a private-use glyph and replaces its two UTF-16 chars")
    void single() {
        EmojiIndex.Match m = index.match("😀 hi", 0, false);
        assertNotNull(m);
        assertEquals(2, m.length());
        assertEquals(0xF, m.glyph() >> 16, "plane-15 private use");
    }

    @Test
    @DisplayName("skin tones, ZWJ families and flags are one glyph each")
    void sequences() {
        String thumbsMedium = "👍🏽";
        EmojiIndex.Match tone = index.match(thumbsMedium, 0, false);
        assertNotNull(tone);
        assertEquals(thumbsMedium.length(), tone.length());
        assertNotEquals(index.match("👍", 0, false).glyph(), tone.glyph());

        String family = "👨‍👩‍👧";
        EmojiIndex.Match fam = index.match(family, 0, false);
        assertNotNull(fam);
        assertEquals(family.length(), fam.length());

        String flag = "🇦🇺";
        EmojiIndex.Match au = index.match(flag, 0, false);
        assertNotNull(au);
        assertEquals(flag.length(), au.length());
    }

    @Test
    @DisplayName("a text-default symbol is only an emoji with U+FE0F (or when forced)")
    void textDefault() {
        assertNull(index.match("❤ love", 0, false), "bare heart stays a text glyph");
        EmojiIndex.Match withSelector = index.match("❤️ love", 0, false);
        assertNotNull(withSelector);
        assertEquals(2, withSelector.length(), "the selector is swallowed with it");
        assertNotNull(index.match("❤", 0, true), "reaction names are always emoji");
        assertNull(index.match("→", 0, false));
    }

    @Test
    @DisplayName("an emoji-default symbol matches without its selector")
    void emojiDefault() {
        assertNotNull(index.match("✅", 0, false));
    }

    @Test
    @DisplayName("Discord-style shortcodes resolve, case-insensitively; unknown names don't")
    void shortcodes() {
        assertEquals("🔥", index.shortcode("fire"));
        assertEquals("👍", index.shortcode("+1"));
        assertEquals("👍", index.shortcode("THUMBSUP"));
        assertNull(index.shortcode("definitely_not_an_emoji"));
    }
}
