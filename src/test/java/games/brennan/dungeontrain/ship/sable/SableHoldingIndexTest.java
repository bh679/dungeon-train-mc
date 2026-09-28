package games.brennan.dungeontrain.ship.sable;

import static games.brennan.dungeontrain.ship.sable.SableHoldingIndex.MAX_RECOVERY_ATTEMPTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SableHoldingIndex}. No Minecraft bootstrap: {@link ChunkPos} is a plain
 * coordinate pair with no registry dependencies.
 */
final class SableHoldingIndexTest {

    private static final UUID A = new UUID(0L, 1L);
    private static final UUID B = new UUID(0L, 2L);
    private static final ChunkPos P1 = new ChunkPos(4, 9);
    private static final ChunkPos P2 = new ChunkPos(5, 9);

    @BeforeEach
    void reset() {
        SableHoldingIndex.setEnabled(true);
        SableHoldingIndex.clear();
    }

    @Test
    @DisplayName("a filed sub-level is claimed until it is loaded back out")
    void fileThenLoad() {
        assertFalse(SableHoldingIndex.contains(A));
        SableHoldingIndex.filed(A, P1);
        assertTrue(SableHoldingIndex.contains(A));
        assertEquals(P1, SableHoldingIndex.chunkOf(A));

        SableHoldingIndex.loaded(A);
        assertFalse(SableHoldingIndex.contains(A));
        assertNull(SableHoldingIndex.chunkOf(A));
    }

    @Test
    @DisplayName("re-filing moves the claim: the newest holding chunk wins")
    void refileWins() {
        SableHoldingIndex.filed(A, P1);
        SableHoldingIndex.filed(A, P2);
        assertEquals(P2, SableHoldingIndex.chunkOf(A));
    }

    @Test
    @DisplayName("the claim survives the first failures and is retracted on the last one")
    void failureBudget() {
        SableHoldingIndex.filed(A, P1);
        for (int i = 1; i < MAX_RECOVERY_ATTEMPTS; i++) {
            SableHoldingIndex.recordFailure(A);
            assertTrue(SableHoldingIndex.contains(A), "still claimed after " + i + " failure(s)");
            assertEquals(i, SableHoldingIndex.failures(A));
        }
        SableHoldingIndex.recordFailure(A);
        assertFalse(SableHoldingIndex.contains(A), "claim retracted once the budget is spent");
    }

    @Test
    @DisplayName("a failure against an unclaimed sub-level creates nothing")
    void failureNeverCreatesAnEntry() {
        // Regression guard for the plain-put rule: recordFailure must not computeIfAbsent, both
        // because a failure says nothing about disk and because the map is written re-entrantly.
        SableHoldingIndex.recordFailure(A);
        assertFalse(SableHoldingIndex.contains(A));
        assertEquals(0, SableHoldingIndex.failures(A));
    }

    @Test
    @DisplayName("a successful load resets the failure budget for the next time it is culled")
    void budgetResetsOnLoad() {
        SableHoldingIndex.filed(A, P1);
        SableHoldingIndex.recordFailure(A);
        assertEquals(1, SableHoldingIndex.failures(A));

        SableHoldingIndex.loaded(A);
        SableHoldingIndex.filed(A, P1);
        assertEquals(0, SableHoldingIndex.failures(A));

        for (int i = 1; i < MAX_RECOVERY_ATTEMPTS; i++) SableHoldingIndex.recordFailure(A);
        assertTrue(SableHoldingIndex.contains(A), "a fresh budget, not a continuation of the old one");
    }

    @Test
    @DisplayName("forget drops one claim; clear drops all of them")
    void forgetAndClear() {
        SableHoldingIndex.filed(A, P1);
        SableHoldingIndex.filed(B, P2);
        SableHoldingIndex.forget(A);
        assertFalse(SableHoldingIndex.contains(A));
        assertTrue(SableHoldingIndex.contains(B));

        SableHoldingIndex.clear();
        assertFalse(SableHoldingIndex.contains(B));
        assertEquals(0, SableHoldingIndex.size());
    }

    @Test
    @DisplayName("giving up retracts the claim so the anchor can be reaped")
    void giveUpRetracts() {
        SableHoldingIndex.filed(A, P1);
        SableHoldingIndex.giveUp(A, "test");
        assertFalse(SableHoldingIndex.contains(A));
        SableHoldingIndex.giveUp(A, "again"); // idempotent
        assertFalse(SableHoldingIndex.contains(A));
    }

    @Test
    @DisplayName("a disabled index claims nothing, so it can never park a spawn lane")
    void disabledClaimsNothing() {
        SableHoldingIndex.filed(A, P1);
        SableHoldingIndex.disable();
        assertFalse(SableHoldingIndex.isEnabled());
        assertFalse(SableHoldingIndex.contains(A), "disabling drops what it was holding");

        SableHoldingIndex.filed(B, P2);
        assertFalse(SableHoldingIndex.contains(B), "and stops accepting new claims");
        SableHoldingIndex.recordFailure(B); // must not throw
    }

    @Test
    @DisplayName("null ids and positions are ignored rather than thrown on")
    void nullsAreIgnored() {
        SableHoldingIndex.filed(null, P1);
        SableHoldingIndex.filed(A, null);
        SableHoldingIndex.loaded(null);
        SableHoldingIndex.forget(null);
        SableHoldingIndex.recordFailure(null);
        SableHoldingIndex.giveUp(null, "test");
        assertFalse(SableHoldingIndex.contains(null));
        assertNull(SableHoldingIndex.chunkOf(null));
        assertEquals(0, SableHoldingIndex.size());
    }

    @Test
    @DisplayName("filing while the index is being read does not throw")
    void reentrantWriteDuringRead() {
        // The real shape: reloadFromHolding probes the holding store, which loads a chunk from
        // disk, which re-files every sibling in it through the mixin — a write from inside a read.
        // Plain put tolerates that; a compute on the same key would not.
        SableHoldingIndex.filed(A, P1);
        for (int i = 0; i < 64; i++) {
            if (SableHoldingIndex.contains(A)) {
                SableHoldingIndex.filed(A, P2);
                SableHoldingIndex.filed(new UUID(1L, i), P1);
            }
        }
        assertEquals(P2, SableHoldingIndex.chunkOf(A));
        assertEquals(65, SableHoldingIndex.size());
    }
}
