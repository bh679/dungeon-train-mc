package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the lock-group picker ({@link CarriageVariantBlocks#pickIndexFromLockGroup}) —
 * the promise that every cell sharing a {@code lockId} renders the same block.
 *
 * <p>Sister-test to {@link CarriageVariantBlocksWeightedTest}; same reason for operating on raw
 * {@code int[]} weights rather than real {@code BlockState} lists (no Forge/MC bootstrap).</p>
 *
 * <p>The group's contract has one unstated precondition, and it is what a portal corridor bug rode
 * in on: the pick is a shared <i>draw</i>, not a shared <i>result</i>. Each cell re-derives its own
 * bucket from its own {@code weights[]}, so cells in one group whose authored state lists differ
 * will disagree despite the lock. {@link #diverging_weightsBreakTheGroup} pins that so the
 * behaviour is a known property rather than a surprise.</p>
 */
final class CarriageVariantBlocksLockGroupTest {

    @Test
    @DisplayName("lock group: locked cells agree across positions where unlocked ones would not")
    void lockGroup_ignoresPositionWhereUnlockedDoesNot() {
        // The locked picker takes no BlockPos at all, so "position is irrelevant" is true by
        // construction and asserting it alone proves nothing. What is worth pinning is the
        // DIFFERENCE: over the same spread of cells the unlocked picker genuinely disagrees with
        // itself, which is the behaviour a lock exists to defeat.
        int[] weights = { 3, 1, 4, 1 };
        BlockPos[] spread = {
            new BlockPos(0, 1, 1), new BlockPos(12, 5, 5),
            new BlockPos(6, 0, 3), new BlockPos(3, 4, 0),
        };

        int locked = CarriageVariantBlocks.pickIndexFromLockGroup(2, 0xC0FFEEL, 5, weights);
        Set<Integer> unlocked = new HashSet<>();
        for (BlockPos pos : spread) {
            unlocked.add(CarriageVariantBlocks.pickIndexFromWeights(pos, 0xC0FFEEL, 5, weights));
            assertEquals(locked,
                CarriageVariantBlocks.pickIndexFromLockGroup(2, 0xC0FFEEL, 5, weights),
                "locked pick must not vary, broke alongside " + pos);
        }
        assertTrue(unlocked.size() > 1,
            "these positions do not diverge unlocked, so they cannot demonstrate what a lock fixes");
    }

    @Test
    @DisplayName("lock group: same inputs always yield the same index")
    void lockGroup_isDeterministic() {
        int[] weights = { 1, 2, 3 };
        int first = CarriageVariantBlocks.pickIndexFromLockGroup(1, 99L, 3, weights);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriageVariantBlocks.pickIndexFromLockGroup(1, 99L, 3, weights),
                "call " + i);
        }
    }

    @Test
    @DisplayName("lock group: always in range for lists of every size")
    void lockGroup_staysInRange() {
        for (int size = 1; size <= 8; size++) {
            int[] weights = new int[size];
            for (int i = 0; i < size; i++) weights[i] = i + 1;
            for (int idx = -32; idx <= 32; idx++) {
                int picked = CarriageVariantBlocks.pickIndexFromLockGroup(4, 7L, idx, weights);
                assertTrue(picked >= 0 && picked < size,
                    "out of range: " + picked + " for size " + size + " at index " + idx);
            }
        }
    }

    @Test
    @DisplayName("lock group: different lock ids are free to disagree")
    void lockGroup_variesByLockId() {
        int[] weights = { 1, 1, 1, 1, 1 };
        Set<Integer> seen = new HashSet<>();
        for (int lockId = 1; lockId <= 32; lockId++) {
            seen.add(CarriageVariantBlocks.pickIndexFromLockGroup(lockId, 0L, 0, weights));
        }
        assertTrue(seen.size() > 1, "every lock id drew the same bucket — the id is not in the mix");
    }

    @Test
    @DisplayName("lock group: the carriage index is part of the key")
    void lockGroup_variesByCarriageIndex() {
        // Why a portal corridor cannot roll against its own carriage index: a crossing is two
        // carriages at different indices, so the same lock would resolve differently in each half.
        // PortalCarriageBuilder.applyCorridorVariants passes the pair's key for this reason.
        int[] weights = { 1, 1, 1, 1, 1 };
        Set<Integer> seen = new HashSet<>();
        for (int idx = 0; idx < 64; idx++) {
            seen.add(CarriageVariantBlocks.pickIndexFromLockGroup(1, 0L, idx, weights));
        }
        assertTrue(seen.size() > 1, "carriage index is not in the mix");
    }

    @Test
    @DisplayName("lock group: a shared draw is not a shared result when weights diverge")
    void diverging_weightsBreakTheGroup() {
        // Two cells, one lock, different authored lists. The draw is shared; the bucket it lands in
        // is not, because each cell walks its own totals. Documented so nobody reads the lock as a
        // guarantee that survives divergent authoring.
        int[] five = { 1, 20, 1, 2, 1 };
        int[] three = { 1, 10, 1 };
        boolean anyDisagreement = false;
        for (int idx = 0; idx < 64; idx++) {
            int a = CarriageVariantBlocks.pickIndexFromLockGroup(4, 0L, idx, five);
            int b = CarriageVariantBlocks.pickIndexFromLockGroup(4, 0L, idx, three);
            if (a != b) { anyDisagreement = true; break; }
        }
        assertTrue(anyDisagreement,
            "divergent weights are expected to break the group — if this now holds, the picker "
                + "gained a canonical-weights contract and this test should assert that instead");
    }

    @Test
    @DisplayName("lock group: rejects the arguments it cannot honour")
    void lockGroup_rejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariantBlocks.pickIndexFromLockGroup(1, 0L, 0, new int[0]));
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariantBlocks.pickIndexFromLockGroup(0, 0L, 0, new int[] { 1 }));
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariantBlocks.pickIndexFromLockGroup(-3, 0L, 0, new int[] { 1 }));
    }

    @Test
    @DisplayName("lock group: weights below one are clamped, not treated as zero")
    void lockGroup_clampsWeights() {
        // Mirrors the defensive clamp in the weighted picker: a 0 or negative weight still gets a
        // bucket, so a malformed sidecar cannot make a candidate unreachable AND shift every index
        // after it.
        int[] clamped = { 0, -5, 1 };
        Set<Integer> reached = new HashSet<>();
        for (int idx = 0; idx < 256; idx++) {
            int picked = CarriageVariantBlocks.pickIndexFromLockGroup(1, 0L, idx, clamped);
            assertTrue(picked >= 0 && picked < 3, "out of range: " + picked);
            reached.add(picked);
        }
        assertEquals(Set.of(0, 1, 2), reached,
            "a clamped weight must still be reachable — if it were treated as zero, its candidate "
                + "would be unreachable and every later index would shift");
    }
}
