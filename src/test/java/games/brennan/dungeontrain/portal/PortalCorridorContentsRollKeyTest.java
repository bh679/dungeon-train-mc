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
 * <p>A corridor exists several times over — as a carriage on the train, as a static twin
 * underground, and as any number of exit copies across an endless room's tiling — stamped by
 * independent calls that must produce identical blocks or the crossing tears open. That was once
 * guaranteed by hardcoding the key to {@code (0L, 0)}, which bought agreement at the price of every
 * corridor in every world holding the same loot, pinned to difficulty tier 0 forever.</p>
 *
 * <p>The key the crossing actually needs is a <i>pure</i> one, not a constant one:
 * {@code (trainSeed, corridorIndex)}, where {@code trainSeed} is the persisted per-world generation
 * seed and {@code corridorIndex} is {@link PortalCarriageRole#corridorIndexOf} — the corridor's own
 * real carriage index, derived from the pair's anchor and the corridor's role. Every site that stamps
 * a corridor knows its role, so all of them land on the same answer with no state passing between
 * them. A player is only ever mapped between a carriage and the twin of the <b>same</b> role, so a
 * portal's two ends are free to furnish differently.</p>
 *
 * <p>Tested against {@link CarriageVariantBlocks#pickIndexFromWeights} — the picker both halves of
 * the contents pass ({@code applyVariantBlocks} and {@code applyContentPools}) are driven by, and
 * which they are handed the <em>same</em> {@code (seed, carriageIndex)} pair. The corridor's shell
 * variants roll in that same frame too. Pinning the key on one seam pins it for all of them. No Forge/MC bootstrap needed, same as
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

    /** Anchor of the n-th portal group, at the default group size of three. */
    private static int pairKeyOf(int groupOrdinal) {
        return PortalCarriageRole.entryIndexOf(groupOrdinal * 3, 3);
    }

    /** The roll key one end of the n-th portal resolves under. */
    private static List<Integer> corridorPicks(long trainSeed, int groupOrdinal, PortalCarriageRole role) {
        return picksFor(trainSeed, PortalCarriageRole.corridorIndexOf(pairKeyOf(groupOrdinal), role));
    }

    @Test
    @DisplayName("twin agreement: the same (trainSeed, corridorIndex) reproduces the corridor exactly")
    void sameCorridorIndexReproducesTheCorridor() {
        // The carriage, its twin, and every exit copy resolve this key independently. If it ever
        // stopped reproducing, the crossing would show a seam.
        long trainSeed = 0x5EEDCAFEL;

        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            List<Integer> carriage = corridorPicks(trainSeed, 4, role);
            for (int restamp = 0; restamp < 50; restamp++) {
                assertEquals(carriage, corridorPicks(trainSeed, 4, role),
                    "the twin must resolve identically to the carriage, or the crossing tears open");
            }
        }
    }

    @Test
    @DisplayName("cross-portal variety: different portals in one world furnish differently")
    void differentPortalsDiverge() {
        long trainSeed = 0x5EEDCAFEL;
        Set<List<Integer>> distinct = new HashSet<>();
        for (int group = 0; group < 12; group++) {
            distinct.add(corridorPicks(trainSeed, group, PortalCarriageRole.ENTRY));
        }
        assertTrue(distinct.size() > 1,
            "every portal on the train resolved the same contents — the reported bug");
    }

    @Test
    @DisplayName("cross-world variety: the same portal ordinal differs between train seeds")
    void differentTrainSeedsDiverge() {
        assertNotEquals(corridorPicks(0x5EEDCAFEL, 4, PortalCarriageRole.ENTRY),
            corridorPicks(0x0DDBA11L, 4, PortalCarriageRole.ENTRY),
            "two worlds resolved the same corridor contents");
    }

    @Test
    @DisplayName("a portal's entry and exit corridors are separate carriages and furnish differently")
    void entryAndExitDiverge() {
        // The property the per-corridor key buys, and the one the pair-anchor key did not: the two
        // ends of one portal are distinct carriages, so they get distinct furnishings. Nothing ever
        // maps a player from one role's corridor into the other's, so there is no seam to protect.
        long trainSeed = 0x5EEDCAFEL;
        int pairKey = pairKeyOf(4);

        int entryIndex = PortalCarriageRole.corridorIndexOf(pairKey, PortalCarriageRole.ENTRY);
        int exitIndex = PortalCarriageRole.corridorIndexOf(pairKey, PortalCarriageRole.EXIT);
        assertNotEquals(entryIndex, exitIndex,
            "the two ends of a portal must be separate carriage indices");
        assertEquals(pairKey + PortalCarriageSelection.SLOT_ENTRY, entryIndex);
        assertEquals(pairKey + PortalCarriageSelection.SLOT_EXIT, exitIndex);

        assertNotEquals(corridorPicks(trainSeed, 4, PortalCarriageRole.ENTRY),
            corridorPicks(trainSeed, 4, PortalCarriageRole.EXIT),
            "the two ends of one portal resolved identically");
    }
}
