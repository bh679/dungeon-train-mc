package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roll key {@code PortalCarriageBuilder.stampCorridorContents} hands the contents pass.
 *
 * <p>A corridor exists twice — as a carriage on the train and as a static twin underground — stamped
 * by two independent calls that must produce identical blocks or the crossing tears open. That was
 * once guaranteed by hardcoding the key to {@code (0L, 0)}, which bought twin agreement at the price
 * of every corridor in every world holding the same loot. The key the crossing actually needs is a
 * <i>pure</i> one, not a constant one: {@code (worldSeed, pairKey)}, where {@code pairKey} is a pure
 * function of the carriage index ({@link PortalCarriageRole#entryIndexOf}) so both stamp sites derive
 * it identically without either knowing about the other.</p>
 *
 * <p>Tested against {@link CarriageVariantBlocks#pickIndexFromWeights} — the picker both halves of
 * the contents pass ({@code applyVariantBlocks} and {@code applyContentPools}) are driven by, and
 * which they are handed the <em>same</em> {@code (seed, carriageIndex)} pair. Pinning the key on one
 * seam pins it for both. No Forge/MC bootstrap needed, same as
 * {@code CarriageVariantBlocksLockGroupTest}.</p>
 *
 * <p><b>What this does NOT cover.</b> {@code stampCorridorContents} is private and needs a
 * {@code ServerLevel}, so nothing here asserts that the call site actually passes
 * {@code (level.getSeed(), pairKey)} — swap the arguments back for constants and these tests still
 * pass. They pin the <i>properties the key must have</i>, so a future change that reaches for a
 * different key has the requirements written down; that the call site holds them is verified
 * in-game.</p>
 */
final class PortalCorridorContentsRollKeyTest {

    /** A spread of interior cells, standing in for a corridor's authored sidecar. */
    private static final BlockPos[] CELLS = {
        new BlockPos(1, 1, 1), new BlockPos(3, 1, 2), new BlockPos(5, 2, 1),
        new BlockPos(7, 1, 3), new BlockPos(9, 2, 2), new BlockPos(4, 3, 1),
        new BlockPos(6, 1, 2), new BlockPos(8, 2, 3),
    };

    private static final int[] WEIGHTS = { 3, 1, 4, 1, 2 };

    /** What the whole corridor resolves to under one key — the thing a twin has to reproduce. */
    private static List<Integer> picksFor(long worldSeed, int carriageIndex) {
        List<Integer> picks = new ArrayList<>(CELLS.length);
        for (BlockPos cell : CELLS) {
            picks.add(CarriageVariantBlocks.pickIndexFromWeights(cell, worldSeed, carriageIndex, WEIGHTS));
        }
        return picks;
    }

    /** Entry-corridor index of the n-th portal group, at the default group size of three. */
    private static int pairKeyOf(int groupOrdinal) {
        return PortalCarriageRole.entryIndexOf(groupOrdinal * 3, 3);
    }

    @Test
    @DisplayName("twin agreement: the same (worldSeed, pairKey) reproduces the corridor exactly")
    void samePairKeyReproducesTheCorridor() {
        long worldSeed = 0x5EEDCAFEL;
        int pairKey = pairKeyOf(4);

        List<Integer> carriage = picksFor(worldSeed, pairKey);
        for (int restamp = 0; restamp < 50; restamp++) {
            assertEquals(carriage, picksFor(worldSeed, pairKey),
                "the twin must resolve identically to the carriage, or the crossing tears open");
        }
    }

    @Test
    @DisplayName("cross-portal variety: different portals in one world furnish differently")
    void differentPairKeysDiverge() {
        long worldSeed = 0x5EEDCAFEL;
        Set<List<Integer>> distinct = new HashSet<>();
        for (int group = 0; group < 12; group++) {
            distinct.add(picksFor(worldSeed, pairKeyOf(group)));
        }
        assertTrue(distinct.size() > 1,
            "every portal on the train resolved the same contents — the reported bug");
    }

    @Test
    @DisplayName("cross-world variety: the same portal ordinal differs between world seeds")
    void differentWorldSeedsDiverge() {
        int pairKey = pairKeyOf(4);
        assertNotEquals(picksFor(0x5EEDCAFEL, pairKey), picksFor(0x0DDBA11L, pairKey),
            "two worlds resolved the same corridor contents");
    }

    @Test
    @DisplayName("a pair's entry and exit corridors share the key, so they furnish identically")
    void bothCorridorsOfOnePairShareTheKey() {
        // The property the key rests on: entryIndexOf answers the GROUP ANCHOR, so every slot in a
        // portal group — entry corridor, exit corridor, the cart between — resolves to one key
        // without any of them knowing another's index. A corridor is mirror-symmetric between the
        // two roles, so the two ends of one portal are meant to agree; it is the next portal along
        // that must differ.
        long worldSeed = 0x5EEDCAFEL;
        int anchor = 4 * 3;

        int entryKey = PortalCarriageRole.entryIndexOf(anchor + PortalCarriageSelection.SLOT_ENTRY, 3);
        int exitKey = PortalCarriageRole.entryIndexOf(anchor + PortalCarriageSelection.SLOT_EXIT, 3);
        assertEquals(entryKey, exitKey, "the two corridors of one portal must derive the same key");
        assertEquals(picksFor(worldSeed, entryKey), picksFor(worldSeed, exitKey));

        assertNotEquals(picksFor(worldSeed, entryKey), picksFor(worldSeed, pairKeyOf(5)),
            "the next portal along resolved the same contents");
    }
}
