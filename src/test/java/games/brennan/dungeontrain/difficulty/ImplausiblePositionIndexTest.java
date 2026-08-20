package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that stops a number which is not a position from becoming a difficulty tier.
 *
 * <p>Three separate bugs have now been the same shape — a world-X, an {@code Objects.hash} and a
 * {@code System.nanoTime} salt each reaching {@code DifficultyProgression.positionTier}, which read
 * it as a distance travelled and returned a tier in the tens of millions. {@code ItemStatLevelScaling}
 * then added a matching flat bonus to the item's primary AIS stat: the report this guard came from
 * was a diamond chestplate with <b>+4,433,488.29 armour</b> out of a Train Dimension room chest,
 * which back-solves to tier 44,334,882 and so to a position index around 886,697,640.</p>
 *
 * <p>Exercises {@link DifficultyProgression#isPlausiblePosition}, the pure predicate, rather than the
 * config-reading {@code tierForTravelled} wrapper it gates — no NeoForge bootstrap required, matching
 * {@link TunnelLootDiffIndexTest} and {@link PortalRoomLootDiffIndexTest}.</p>
 *
 * <p>Note those two files still assert the <i>unguarded</i> millions-deep tiers, and must: they
 * document {@code rawTier}'s arithmetic, which is unchanged. The guard sits one level up, at the
 * policy boundary.</p>
 */
final class ImplausiblePositionIndexTest {

    /** Default carriagesPerTier (matches {@code DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER}). */
    private static final int CPT = 20;

    /** What {@code PortalStructure.variantIndexFor} computes for a non-Dynamic room. */
    private static int variantIndex(String roomName, int pairKey) {
        return Objects.hash(roomName.hashCode(), pairKey);
    }

    @Test
    @DisplayName("the reported chestplate's frame is rejected as a position")
    void reportedFrame_isImplausible() {
        // +4,433,488.29 armour / 0.10 per tier = tier 44,334,882; x20 carriages per tier.
        int reportedIndex = 44_334_882 * CPT;
        assertFalse(DifficultyProgression.isPlausiblePosition(reportedIndex),
            "the frame behind the reported item must be rejected");
        // Left unguarded this is the tier that produced the four-million-armour chestplate.
        assertTrue(DifficultyProgression.rawTier(reportedIndex, CPT) > 40_000_000,
            "sanity: this frame really does inflate the raw tier");
    }

    @Test
    @DisplayName("hash and nanoTime frames are rejected as positions")
    void hashAndSaltFrames_areImplausible() {
        for (String roomName : new String[] {"library", "vault", "hall"}) {
            for (int pairKey : new int[] {0, 20, 140, 1000}) {
                assertFalse(DifficultyProgression.isPlausiblePosition(variantIndex(roomName, pairKey)),
                    "a variantIndex hash is not a position: " + roomName + "/" + pairKey);
            }
        }
        // The shape ContainerContentsLinkPropagator and DebugCommand drew their salt in. Sampled at
        // realistic nanoTime magnitudes: the low 31 bits of a wall-clock-scale reading land far past
        // the bound. (The guard is a bound, not a parity check — a salt whose low bits happen to be
        // small IS a plausible position and is deliberately let through. It rolls tame loot, which is
        // why the frames themselves are fixed at both call sites rather than left to this net.)
        for (long nanos : new long[] {1234567890123L, 987654321987654L, 1699999999999999L}) {
            int salt = (int) (nanos & 0x7FFFFFFFL);
            assertFalse(DifficultyProgression.isPlausiblePosition(salt),
                "a nanoTime salt is not a position: " + salt);
        }
    }

    @Test
    @DisplayName("real positions are untouched, however deep the run")
    void realPositions_arePlausible() {
        // Near spawn, a normal portal, a very long run, and a backward-half position.
        for (int index : new int[] {0, 1, 140, 20_000, 1_000_000, -140, -20_000}) {
            assertTrue(DifficultyProgression.isPlausiblePosition(index),
                "a real carriage index must never be rejected: " + index);
        }
    }

    @Test
    @DisplayName("the reporter survives being called — including from a stack it cannot attribute")
    void reporter_doesNotThrow() {
        // This runs only on the failure branch, so a fault in it would surface at the one moment it
        // is least affordable: a diagnostic that raises turns a loot bug into a crash. Exercises the
        // StackWalker walk, the caller-frame de-duplication and the log call's argument arity.
        assertDoesNotThrow(() -> DifficultyProgression.reportImplausiblePosition(886_697_640));
        // Second call for the same site takes the de-duplicated path instead.
        assertDoesNotThrow(() -> DifficultyProgression.reportImplausiblePosition(886_697_640));
        assertDoesNotThrow(() -> DifficultyProgression.reportImplausiblePosition(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("the culprit named is the mod's own frame, not the scaffolding around it")
    void attribution_prefersModFrames() {
        // The real call sites: what the log should point at.
        assertTrue(DifficultyProgression.isAttributableFrame(
            "games.brennan.dungeontrain.editor.ContainerContentsRoller"));
        assertTrue(DifficultyProgression.isAttributableFrame(
            "games.brennan.dungeontrain.portal.PortalCarriageBuilder"));

        // The frames between it and here, which would be useless to name.
        assertFalse(DifficultyProgression.isAttributableFrame(
            "org.junit.jupiter.api.AssertDoesNotThrow"));
        assertFalse(DifficultyProgression.isAttributableFrame(
            "java.util.stream.ReferencePipeline"));

        // This package is doing the asking, so it is never the answer.
        assertFalse(DifficultyProgression.isAttributableFrame(
            "games.brennan.dungeontrain.difficulty.DifficultyProgression"));
        assertTrue(DifficultyProgression.isDifficultyFrame(
            "games.brennan.dungeontrain.difficulty.DifficultyApplier"));

        // Which is also why the ERROR line this suite emits names a JUnit frame: the test class
        // itself lives in the difficulty package, so it is filtered out before the preference runs.
        // In play the nearest frame outside this package IS the mod code that passed the bad index.
    }

    @Test
    @DisplayName("the bound itself is plausible; one past it is not")
    void boundary_isInclusive() {
        int max = DifficultyProgression.MAX_PLAUSIBLE_POSITION_INDEX;
        assertTrue(DifficultyProgression.isPlausiblePosition(max));
        assertTrue(DifficultyProgression.isPlausiblePosition(-max));
        assertFalse(DifficultyProgression.isPlausiblePosition(max + 1));
        assertFalse(DifficultyProgression.isPlausiblePosition(-max - 1));
    }

    @Test
    @DisplayName("a garbage index cannot slip through by overflowing the offset shift")
    void hugeIndex_doesNotOverflowPastTheGuard() {
        // shiftedPosition is max(0, abs(index) + offset). At the int ceiling that sum overflows to a
        // negative and floors to 0 — a plausible-looking tier 0 that would have skipped the tripwire
        // had positionTier tested the shifted value instead of the raw one.
        assertTrue(DifficultyProgression.shiftedPosition(Integer.MAX_VALUE - 1, 1000) == 0,
            "sanity: the shift really does overflow and floor here");
        assertFalse(DifficultyProgression.isPlausiblePosition(Integer.MAX_VALUE - 1),
            "so the raw index must be what the guard tests");
    }

    @Test
    @DisplayName("Integer.MIN_VALUE is rejected rather than passing on a negative abs")
    void integerMinValue_isImplausible() {
        // Math.abs(Integer.MIN_VALUE) is itself negative, so an abs-based bound check would
        // wave this through. The predicate compares against both ends instead.
        assertFalse(DifficultyProgression.isPlausiblePosition(Integer.MIN_VALUE));
    }
}
