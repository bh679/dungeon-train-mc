package games.brennan.dungeontrain.narrative;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the read-tracking fix for the non-dimension half of the
 * {@code all_starting_books} set (Inter-Reality Passenger). Delivered starting
 * books now carry a recoverable {@link StartingBookTag.StartingBookIdentity},
 * and reading one (close-and-burn flow) marks its {@code (basename, variant)}
 * in the world-scoped {@link NarrativeProgressData} seen-store that
 * {@code AchievementEvents.allStartingBooksSeen} reads for lifecycle folders.
 *
 * <p>Pure — no server / Minecraft bootstrap. The tag round-trip drives the
 * package-private {@code CompoundTag} codec ({@link StartingBookTag#writeIdentity}
 * / {@link StartingBookTag#readIdentity}) directly so it needs no ItemStack, and
 * the store assertions round-trip a {@link NarrativeProgressData} via
 * {@code load}/{@code save}, mirroring {@link NarrativeProgressDataTest} and
 * {@link StartingBookCycleTest}.</p>
 */
final class StartingBookReadSeenTest {

    private static final String BOOK = "brennan_intro";
    private static final int VARIANT = 2;

    // ---------------- StartingBookTag identity codec ----------------

    @Test
    @DisplayName("writeIdentity → readIdentity round-trips basename + variant and sets the marker")
    void identityRoundTrip() {
        CompoundTag tag = new CompoundTag();
        StartingBookTag.writeIdentity(tag, BOOK, VARIANT);

        assertTrue(tag.getBoolean(StartingBookTag.NBT_KEY),
            "identity stamp must also set the boolean marker so the close/burn flow still recognises the stack");
        Optional<StartingBookTag.StartingBookIdentity> id = StartingBookTag.readIdentity(tag);
        assertTrue(id.isPresent());
        assertEquals(BOOK, id.get().basename());
        assertEquals(VARIANT, id.get().variantIndex());
    }

    @Test
    @DisplayName("readIdentity is empty when the starting-book marker is absent")
    void noMarkerNoIdentity() {
        assertTrue(StartingBookTag.readIdentity(new CompoundTag()).isEmpty(),
            "a tag with no marker is not a starting book");
    }

    @Test
    @DisplayName("readIdentity is empty for a legacy boolean-only stamp (back-compat no-op)")
    void legacyBooleanOnlyHasNoIdentity() {
        CompoundTag legacy = new CompoundTag();
        legacy.putBoolean(StartingBookTag.NBT_KEY, true); // welcome book stamped before identity tracking
        assertTrue(StartingBookTag.readIdentity(legacy).isEmpty(),
            "pre-identity books carry only the marker → read-mark is a graceful no-op, not a crash");
    }

    // ---------------- World-scoped seen-store (what the read handler writes) ----------------

    @Test
    @DisplayName("Empty store: a lifecycle starting book is not yet seen")
    void emptyStoreNotSeen() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        assertFalse(data.hasSeenStartingBookVariant(BOOK, VARIANT));
        assertTrue(data.startingBookSeenSnapshot().isEmpty());
    }

    @Test
    @DisplayName("markStartingBookVariantSeen records the variant, is idempotent, and shows in the snapshot")
    void markRecordsAndIsIdempotent() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        assertTrue(data.markStartingBookVariantSeen(BOOK, VARIANT), "first mark reports a state change");
        assertFalse(data.markStartingBookVariantSeen(BOOK, VARIANT), "re-marking the same (book, variant) is a no-op");
        assertTrue(data.hasSeenStartingBookVariant(BOOK, VARIANT));

        Map<String, NarrativeProgress> snapshot = data.startingBookSeenSnapshot();
        assertTrue(snapshot.containsKey(BOOK),
            "snapshot is keyed by plain basename — the lifecycle/world-scoped form the achievement reads");
        assertTrue(snapshot.get(BOOK).readLetters().contains(VARIANT));
    }

    @Test
    @DisplayName("Seen-marks are scoped per book: marking one book does not mark another")
    void markScopedPerBook() {
        NarrativeProgressData data = NarrativeProgressData.load(new CompoundTag());
        data.markStartingBookVariantSeen(BOOK, VARIANT);
        assertFalse(data.hasSeenStartingBookVariant("journey", VARIANT),
            "a different book must not inherit the seen variant");
        assertFalse(data.hasSeenStartingBookVariant(BOOK, VARIANT + 1),
            "a different variant of the same book is still unseen");
    }

    @Test
    @DisplayName("save → load round-trip preserves the read starting-book variants")
    void roundTripPreservesSeen() {
        NarrativeProgressData original = NarrativeProgressData.load(new CompoundTag());
        original.markStartingBookVariantSeen(BOOK, 0);
        original.markStartingBookVariantSeen(BOOK, VARIANT);
        original.markStartingBookVariantSeen("new_world", 0);

        CompoundTag serialized = original.save(new CompoundTag(), null);
        NarrativeProgressData reloaded = NarrativeProgressData.load(serialized);

        assertTrue(reloaded.hasSeenStartingBookVariant(BOOK, 0));
        assertTrue(reloaded.hasSeenStartingBookVariant(BOOK, VARIANT));
        assertTrue(reloaded.hasSeenStartingBookVariant("new_world", 0));
        assertFalse(reloaded.hasSeenStartingBookVariant(BOOK, VARIANT + 1));
    }
}
