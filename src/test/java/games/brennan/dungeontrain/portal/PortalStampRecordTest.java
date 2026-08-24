package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalCarriageSelection.Rate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a carriage's portal verdict has to be recorded when its blocks are stamped rather than
 * re-derived afterwards — see {@link PortalStampRecord}.
 *
 * <p>{@link PortalCarriageLotteryTest} covers the draw itself, and its first property is that the
 * verdict never changes for a given world. That holds only with the {@link Rate} held still. The
 * rate is not held still: {@code PortalCarriageSelection.rateFor} returns the seeded lottery or an
 * exact creative cadence depending on the live game modes on the level. These tests pin the size of
 * that gap, because it is what a swap plane was being built on top of.</p>
 */
final class PortalStampRecordTest {

    private static final int GROUP = 3;
    private static final long SEED = 0x5DEADBEEFL;
    /**
     * The two rates a real level actually flips between: the world's stored lottery rate, and the
     * exact cadence a level takes while everyone on it is in creative.
     *
     * <p>Not the same number on both sides on purpose — that is the shipped pairing
     * ({@code DEFAULT_CARRIAGE_EVERY} against {@code CREATIVE_EVERY}), and creative's is separate so
     * it can be dense without moving what a survival run meets. Note that feeding the <i>same</i>
     * number to both would prove nothing at 5: {@code Rate.lottery(5)} is denser than
     * {@link PortalCarriageSelection#MIN_GROUP_GAP} can carry, so the draw degenerates to the same
     * fixed period the creative cadence counts off, and the two agree exactly.</p>
     */
    private static final Rate LOTTERY =
        Rate.lottery(PortalCarriageSelection.DEFAULT_CARRIAGE_EVERY);
    private static final Rate CREATIVE = Rate.periodic(PortalCarriageSelection.CREATIVE_EVERY);

    /** Groups sampled — a few hundred is far more than a player rides past in a session. */
    private static final int SAMPLE_GROUPS = 500;

    /**
     * The bug, stated as a property: the two rates a level can be drawing at disagree about real
     * carriage indices, and in both directions. Every one of these is a carriage whose blocks say
     * one thing and whose live verdict says the other, for as long as the mismatch stands.
     */
    @Test
    @DisplayName("the creative cadence and the survival lottery claim different groups")
    void ratesDisagree() {
        Rate lottery = LOTTERY;
        Rate periodic = CREATIVE;

        int lotteryOnly = 0;
        int periodicOnly = 0;
        for (int group = 0; group < SAMPLE_GROUPS; group++) {
            int anchor = group * GROUP;
            boolean drawn = PortalCarriageSelection.isPortalGroup(anchor, GROUP, lottery, SEED);
            boolean counted = PortalCarriageSelection.isPortalGroup(anchor, GROUP, periodic, SEED);
            if (drawn && !counted) lotteryOnly++;
            if (counted && !drawn) periodicOnly++;
        }

        // Both directions matter. A group in the second set is one the tick loop would claim after a
        // switch to creative though it was stamped ordinary — the report this fix came from. A group
        // in the first is a standing corridor the tick loop would abandon on the way back.
        assertTrue(lotteryOnly > 0,
            "no group the lottery claims and the creative cadence does not — the rates would have "
                + "to agree for re-deriving the verdict at tick time to be safe");
        assertTrue(periodicOnly > 0,
            "no group the creative cadence claims and the lottery does not");
    }

    /**
     * And the disagreement is not a rounding error at the edges: a good fraction of the groups a
     * creative session claims were stamped as ordinary carriages.
     */
    @Test
    @DisplayName("the disagreement covers many groups, not a handful")
    void disagreementIsWidespread() {
        Rate lottery = LOTTERY;
        Rate periodic = CREATIVE;

        int claimedByCreative = 0;
        int alsoStampedByLottery = 0;
        for (int group = 0; group < SAMPLE_GROUPS; group++) {
            int anchor = group * GROUP;
            if (!PortalCarriageSelection.isPortalGroup(anchor, GROUP, periodic, SEED)) continue;
            claimedByCreative++;
            if (PortalCarriageSelection.isPortalGroup(anchor, GROUP, lottery, SEED)) {
                alsoStampedByLottery++;
            }
        }

        assertTrue(claimedByCreative > 10, "sample too small to say anything");
        // Nowhere near all of them: the creative cadence is an exact period and the lottery is a
        // seeded hash with a gap rule, so overlap is coincidence rather than agreement.
        assertTrue(alsoStampedByLottery * 2 < claimedByCreative,
            "the two rates agreed on most groups (" + alsoStampedByLottery + " of "
                + claimedByCreative + "), which the hashed draw should not allow");
    }

    /**
     * The half of the verdict that is <b>not</b> recorded, and does not need to be: where a carriage
     * sits inside its group is fixed arithmetic. The record says whether the group is a portal; this
     * says which two of its three carriages are the corridors a swap plane may cover.
     */
    @Test
    @DisplayName("the corridor slots are the two ends of the group, never the cart")
    void corridorSlots() {
        for (int group = -20; group <= 20; group++) {
            int anchor = group * GROUP;
            assertTrue(PortalStampRecord.isCorridorSlot(anchor, GROUP), "entry at " + anchor);
            assertFalse(PortalStampRecord.isCorridorSlot(anchor + 1, GROUP), "cart at " + anchor);
            assertTrue(PortalStampRecord.isCorridorSlot(anchor + 2, GROUP), "exit at " + anchor);
        }
    }

    /** The slot split agrees with the one the selection draws, so the two cannot drift apart. */
    @Test
    @DisplayName("corridor slots match what the selection calls a portal carriage")
    void corridorSlotsMatchSelection() {
        Rate every = Rate.periodic(1);
        for (int carriageIndex = -60; carriageIndex <= 60; carriageIndex++) {
            assertEquals(
                PortalCarriageSelection.isPortalCarriage(carriageIndex, GROUP, every, SEED),
                PortalStampRecord.isCorridorSlot(carriageIndex, GROUP),
                "carriage " + carriageIndex);
        }
    }
}
