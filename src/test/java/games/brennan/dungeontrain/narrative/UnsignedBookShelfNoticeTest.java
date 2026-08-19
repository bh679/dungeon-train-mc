package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.component.WritableBookContent;
import org.junit.jupiter.api.Test;

/**
 * Covers the two halves of the unsigned-book shelf line: which books earn one
 * ({@link UnsignedBookShelfNotice#hasWriting}) and what the line itself is ({@link UnsignedBookMessage}).
 *
 * <p>No NeoForge bootstrap: {@link WritableBookContent} and {@link Filterable} are plain records, and the line
 * is inspected as the translation key + style the server chose rather than as rendered English (with no
 * language loaded in the test JVM, rendering would just echo the key).</p>
 */
class UnsignedBookShelfNoticeTest {

    private static final String KEY = "chat.dungeontrain.unsigned_book.";

    private static WritableBookContent pages(String... texts) {
        return new WritableBookContent(List.of(texts).stream().map(Filterable::passThrough).toList());
    }

    @Test
    void untouchedBook_hasNoWriting() {
        // A book & quill straight off the crafting grid: no pages at all.
        assertFalse(UnsignedBookShelfNotice.hasWriting(WritableBookContent.EMPTY));
        // Opened, maybe paged through, but nothing typed — vanilla keeps the empty/whitespace page.
        assertFalse(UnsignedBookShelfNotice.hasWriting(pages("")));
        assertFalse(UnsignedBookShelfNotice.hasWriting(pages("   \n  ")));
        assertFalse(UnsignedBookShelfNotice.hasWriting(pages("", "", "")));
    }

    @Test
    void anyNonBlankPage_countsAsWriting() {
        assertTrue(UnsignedBookShelfNotice.hasWriting(pages("hello")));
        assertTrue(UnsignedBookShelfNotice.hasWriting(pages("hello", "")));
        // Writing further in still counts — a player who left the first page empty wrote a book all the same.
        assertTrue(UnsignedBookShelfNotice.hasWriting(pages("", "  ", "a note")));
    }

    @Test
    void message_isAlwaysOneOfTheFiveGrayLines() {
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            Component line = UnsignedBookMessage.random(RandomSource.create(seed));
            String key = ((TranslatableContents) line.getContents()).getKey();
            assertTrue(key.matches("\\Q" + KEY + "\\E[1-" + UnsignedBookMessage.LINE_COUNT + "]"),
                "a numbered variant, got: " + key);
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), line.getStyle().getColor(),
                "the muted drifting-carriage gray");
            seen.add(key);
        }
        assertEquals(UnsignedBookMessage.LINE_COUNT, seen.size(), "every line should surface across 200 seeds");
    }

    /**
     * en_us is the one locale nothing else validates — {@code check-provenance.py} aligns translations
     * <em>against</em> it — so a missing English key here would ship as a raw {@code chat.dungeontrain.…}
     * string for every English player. Same guard as {@link AdvancementLangKeysTest}.
     */
    @Test
    void everyLineKey_existsInEnUs() throws IOException {
        String json = Files.readString(RepoPaths.langFile("en_us"), StandardCharsets.UTF_8);
        JsonObject lang = JsonParser.parseString(json).getAsJsonObject();
        for (int n = 1; n <= UnsignedBookMessage.LINE_COUNT; n++) {
            String key = KEY + n;
            assertTrue(lang.has(key), "en_us.json is missing " + key);
            assertFalse(lang.get(key).getAsString().isBlank(), key + " is blank in en_us.json");
        }
    }
}
