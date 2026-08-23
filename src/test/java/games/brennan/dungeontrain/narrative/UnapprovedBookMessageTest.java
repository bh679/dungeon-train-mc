package games.brennan.dungeontrain.narrative;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three sets of "where your book stands" lines. */
class UnapprovedBookMessageTest {

    @Test
    @DisplayName("A released book says nothing about itself")
    void approvedProducesNoLine() {
        assertNull(UnapprovedBookMessage.random(BookModerationState.APPROVED, RandomSource.create(1)));
        assertNull(UnapprovedBookMessage.random(null, RandomSource.create(1)));
    }

    @Test
    @DisplayName("Every withheld state draws from its own set of LINE_COUNT keys, and reaches all of them")
    void everyStateCoversItsWholeSet() {
        for (BookModerationState state : new BookModerationState[] {
                BookModerationState.READING, BookModerationState.UNDECIDED, BookModerationState.DISLIKED}) {
            Set<String> seen = new HashSet<>();
            RandomSource rng = RandomSource.create(20260823L);
            // Enough draws that every line is overwhelmingly likely to come up; a set that is short
            // (or a key built with an off-by-one) shows up here as a missing or unexpected key.
            for (int i = 0; i < 4000; i++) {
                Component line = UnapprovedBookMessage.random(state, rng);
                TranslatableContents c = assertInstanceOf(TranslatableContents.class, line.getContents());
                seen.add(c.getKey());
            }
            assertEquals(UnapprovedBookMessage.LINE_COUNT, seen.size(),
                state + " must draw from exactly LINE_COUNT distinct lines");
            for (int n = 1; n <= UnapprovedBookMessage.LINE_COUNT; n++) {
                String key = "chat.dungeontrain.unapproved_book." + state.messageKey() + "." + n;
                assertTrue(seen.contains(key), "missing " + key);
            }
        }
    }

    @Test
    @DisplayName("The three sets are distinct — a rejection never reads as 'still reading'")
    void setsDoNotOverlap() {
        assertEquals("reading", BookModerationState.READING.messageKey());
        assertEquals("undecided", BookModerationState.UNDECIDED.messageKey());
        assertEquals("disliked", BookModerationState.DISLIKED.messageKey());
    }
}
