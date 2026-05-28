package games.brennan.dungeontrain.narrative;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
