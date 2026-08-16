package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the portal-room (Train Dimension) loot difficulty frame.
 *
 * <p>{@code PortalCarriageBuilder.applyRoomVariants} keys its deterministic rolls on the room's
 * {@code variantIndex} — {@code PortalStructure.variantIndexFor}, an {@code Objects.hash} of the
 * room name and the pair key, carrying pair-and-copy identity. Before the fix that hash was passed
 * as the carriage index too, so {@code DifficultyProgression.positionTier} read it as a position and
 * every room chest rolled its AIS stats at a tier tens of millions deep. The difficulty frame is now
 * {@code pairKey} — the entry corridor's real carriage index — while the hash stays the seed.
 *
 * <p>Exercises the pure tier helper ({@code rawTier}) rather than the config-reading
 * {@code positionTier} wrapper, matching {@link TunnelLootDiffIndexTest} and
 * {@code DifficultyProgressionTest} — no NeoForge bootstrap required. The call site itself needs a
 * live {@code ServerLevel}, so these tests assert the frame it now passes. Lives in the
 * {@code difficulty} package (not {@code portal}) because {@code rawTier} is package-private.
 */
final class PortalRoomLootDiffIndexTest {

    /** Default carriagesPerTier (matches {@code DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER}). */
    private static final int CPT = 20;

    /** What {@code PortalStructure.variantIndexFor} computes for a non-Dynamic room. */
    private static int variantIndex(String roomName, int pairKey) {
        return Objects.hash(roomName.hashCode(), pairKey);
    }

    @Test
    @DisplayName("a room's loot tier is the tier of the portal carriage that leads into it")
    void diffIndex_matchesEntryCarriageFrame() {
        int pairKey = 140;

        // The frame passed as diffIndex IS the entry carriage's index, so the room's loot scales
        // exactly like a chest in the carriage the player walked in from — 140 carriages out at the
        // default 20 per tier is tier 7, whatever room name or copy the tiling stamped.
        assertEquals(7, DifficultyProgression.rawTier(pairKey, CPT));
        assertEquals(7, DifficultyProgression.effectiveTier(pairKey, CPT, /*delay*/ 0));
    }

    @Test
    @DisplayName("regression: the variantIndex hash inflated the tier into the millions")
    void variantIndexHash_inflatesTier() {
        for (String roomName : new String[] {"library", "vault", "hall"}) {
            for (int pairKey : new int[] {0, 20, 140, 1000}) {
                int buggy = DifficultyProgression.rawTier(variantIndex(roomName, pairKey), CPT);
                int fixed = DifficultyProgression.rawTier(pairKey, CPT);

                assertNotEquals(buggy, fixed,
                    "the fix must actually change the tier for " + roomName + "/" + pairKey);
                assertTrue(buggy > fixed,
                    "the hash frame must be the inflated one for " + roomName + "/" + pairKey);
                assertTrue(buggy > 1_000_000,
                    "a hash-derived tier lands in the millions for " + roomName + "/" + pairKey
                        + " (was " + buggy + ")");
            }
        }
    }

    @Test
    @DisplayName("a portal near spawn rolls tier 0 rather than a hash-derived tier")
    void nearSpawn_rollsTierZero() {
        int pairKey = 0;
        assertEquals(0, DifficultyProgression.rawTier(pairKey, CPT));
        assertTrue(DifficultyProgression.rawTier(variantIndex("library", pairKey), CPT) > 0,
            "the old frame gave the very first portal a non-zero tier");
    }

    @Test
    @DisplayName("a portal behind the origin scales like its forward twin")
    void backwardPair_scalesSymmetrically() {
        // rawTier takes abs, so a portal on the backward half of the train matches its mirror.
        assertEquals(
            DifficultyProgression.rawTier(140, CPT),
            DifficultyProgression.rawTier(-140, CPT));
    }
}
