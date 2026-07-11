package games.brennan.dungeontrain.narrative;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the per-letter variant tracking that drives the
 * {@code read_all_story_variants} ("Every Reality, Every Word") achievement.
 *
 * <p>Tests bypass the live {@code ServerLevel#getDataStorage()} path by
 * round-tripping a {@link CompoundTag} directly through
 * {@link NarrativeProgressData#load(CompoundTag)} and
 * {@link NarrativeProgressData#save(CompoundTag, net.minecraft.core.HolderLookup.Provider)}.
 * The {@code HolderLookup.Provider} argument is unused for this store, so
 * passing {@code null} is safe.</p>
 */
final class NarrativeProgressDataTest {

    private static final String STORY = "augustus_park";
    private static final int LETTER = 3;
    private static final int VARIANT = 1;

    @Test
    @DisplayName("Empty store: hasSeenStoryVariant returns false")
    void emptyHasNotSeen() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        assertFalse(data.hasSeenStoryVariant(STORY, LETTER, VARIANT));
    }

    @Test
    @DisplayName("markStoryVariantSeen: first call returns true, second call returns false (idempotent)")
    void markIsIdempotent() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        assertTrue(data.markStoryVariantSeen(STORY, LETTER, VARIANT),
            "first mark should report state change");
        assertFalse(data.markStoryVariantSeen(STORY, LETTER, VARIANT),
            "re-marking the same (story, letter, variant) should be a no-op");
        assertTrue(data.hasSeenStoryVariant(STORY, LETTER, VARIANT));
    }

    @Test
    @DisplayName("Variants are scoped per-letter: marking letter 3 variant 1 doesn't mark letter 4")
    void variantScopedPerLetter() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        data.markStoryVariantSeen(STORY, LETTER, VARIANT);
        assertTrue(data.hasSeenStoryVariant(STORY, LETTER, VARIANT));
        assertFalse(data.hasSeenStoryVariant(STORY, LETTER + 1, VARIANT),
            "different letter must not inherit the same variant set");
        assertFalse(data.hasSeenStoryVariant(STORY, LETTER, VARIANT + 1),
            "different variant of same letter is still unseen");
    }

    @Test
    @DisplayName("save → load round-trip preserves the variant-seen set")
    void roundTripPreservesVariants() {
        NarrativeProgressData original = NarrativeProgressData.load(new CompoundTag());
        original.markStoryVariantSeen(STORY, LETTER, 0);
        original.markStoryVariantSeen(STORY, LETTER, VARIANT);
        original.markStoryVariantSeen("pip_aaro_the_waiting_child", 1, 0);

        CompoundTag serialized = original.save(new CompoundTag(), null);
        NarrativeProgressData reloaded = NarrativeProgressData.load(serialized);

        assertTrue(reloaded.hasSeenStoryVariant(STORY, LETTER, 0));
        assertTrue(reloaded.hasSeenStoryVariant(STORY, LETTER, VARIANT));
        assertTrue(reloaded.hasSeenStoryVariant("pip_aaro_the_waiting_child", 1, 0));
        assertFalse(reloaded.hasSeenStoryVariant(STORY, LETTER, VARIANT + 1));
    }

    @Test
    @DisplayName("markSharedBookEverRead: first call true, repeat false; count is distinct ids")
    void sharedBookEverReadIsMonotonic() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        assertEquals(0, data.distinctSharedBooksEverRead());
        assertTrue(data.markSharedBookEverRead(101), "first read of an id reports a state change");
        assertFalse(data.markSharedBookEverRead(101), "re-reading the same community book id is a no-op");
        assertTrue(data.markSharedBookEverRead(202));
        assertEquals(2, data.distinctSharedBooksEverRead(), "two distinct ids read");
    }

    @Test
    @DisplayName("save → load round-trip preserves the community-book ever-read id set")
    void roundTripPreservesSharedBooks() {
        NarrativeProgressData original = NarrativeProgressData.load(new CompoundTag());
        original.markSharedBookEverRead(7);
        original.markSharedBookEverRead(42);
        original.markSharedBookEverRead(7); // duplicate — still 2 distinct

        CompoundTag serialized = original.save(new CompoundTag(), null);
        NarrativeProgressData reloaded = NarrativeProgressData.load(serialized);

        assertEquals(2, reloaded.distinctSharedBooksEverRead());
        assertFalse(reloaded.markSharedBookEverRead(7), "id 7 survived the round-trip");
        assertFalse(reloaded.markSharedBookEverRead(42), "id 42 survived the round-trip");
        assertTrue(reloaded.markSharedBookEverRead(99), "a fresh id is still newly recorded");
    }

    @Test
    @DisplayName("Back-compat: loading a tag with no shared_books_ever_read key decodes as empty")
    void backCompatMissingSharedBooksTag() {
        NarrativeProgressData oldStyle = NarrativeProgressData.load(new CompoundTag());
        oldStyle.markRead(STORY, LETTER);
        CompoundTag legacy = oldStyle.save(new CompoundTag(), null);
        legacy.remove("shared_books_ever_read");

        NarrativeProgressData reloaded = NarrativeProgressData.load(legacy);
        assertEquals(0, reloaded.distinctSharedBooksEverRead(),
            "missing shared_books_ever_read tag must decode as empty, not NPE");
    }

    // ---------------- Letter-series (player-written lectern letters) ----------------

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    @DisplayName("nextLetter: within one life the series id is stable and the index climbs 1,2,3")
    void nextLetterClimbsWithinLife() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        LetterSeries l1 = data.nextLetter(PLAYER, 5L);
        LetterSeries l2 = data.nextLetter(PLAYER, 5L);
        LetterSeries l3 = data.nextLetter(PLAYER, 5L);
        assertEquals(1, l1.letterIndex());
        assertEquals(2, l2.letterIndex());
        assertEquals(3, l3.letterIndex());
        assertEquals(l1.seriesId(), l2.seriesId(), "same life keeps one series id");
        assertEquals(l1.seriesId(), l3.seriesId());
        assertFalse(l1.seriesId().isBlank(), "a series id is minted");
    }

    @Test
    @DisplayName("nextLetter: a new life (death count changed) restarts at 1 with a fresh series id")
    void nextLetterResetsOnNewLife() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        LetterSeries life0 = data.nextLetter(PLAYER, 0L);
        data.nextLetter(PLAYER, 0L); // index 2 in life 0
        LetterSeries life1 = data.nextLetter(PLAYER, 1L); // died once — new life
        assertEquals(1, life1.letterIndex(), "new life restarts numbering");
        assertNotEquals(life0.seriesId(), life1.seriesId(), "new life is a new series");
    }

    @Test
    @DisplayName("peekNextIndex: reports the next index without advancing the cursor")
    void peekDoesNotAdvance() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        assertEquals(1, data.peekNextIndex(PLAYER, 3L), "first letter would be index 1");
        data.nextLetter(PLAYER, 3L); // index 1 signed
        assertEquals(2, data.peekNextIndex(PLAYER, 3L), "next would be 2");
        assertEquals(2, data.peekNextIndex(PLAYER, 3L), "peek is idempotent (no advance)");
    }

    @Test
    @DisplayName("save → load round-trip continues the same letter series after reload")
    void roundTripPreservesLetterSeries() {
        NarrativeProgressData original = NarrativeProgressData.load(new CompoundTag());
        LetterSeries l1 = original.nextLetter(PLAYER, 9L);
        original.nextLetter(PLAYER, 9L); // index 2

        CompoundTag serialized = original.save(new CompoundTag(), null);
        NarrativeProgressData reloaded = NarrativeProgressData.load(serialized);

        LetterSeries l3 = reloaded.nextLetter(PLAYER, 9L);
        assertEquals(3, l3.letterIndex(), "series index survives the round-trip");
        assertEquals(l1.seriesId(), l3.seriesId(), "series id survives the round-trip");
    }

    @Test
    @DisplayName("Back-compat: loading a tag with no story_variants key behaves like empty (no NPE, returns false)")
    void backCompatMissingTag() {
        // Build a tag that has the older keys but is missing story_variants.
        // Simulates a save file written before the per-variant tracking landed.
        NarrativeProgressData oldStyle = NarrativeProgressData.load(new CompoundTag());
        oldStyle.markRead(STORY, LETTER);
        CompoundTag legacy = oldStyle.save(new CompoundTag(), null);
        legacy.remove("story_variants");

        NarrativeProgressData reloaded = NarrativeProgressData.load(legacy);
        assertFalse(reloaded.hasSeenStoryVariant(STORY, LETTER, VARIANT),
            "missing story_variants tag must decode as empty, not NPE");
        assertTrue(reloaded.progressFor(STORY).readLetters().contains(LETTER),
            "letter-level progress survives the round-trip independently of variant tracking");
    }
}
