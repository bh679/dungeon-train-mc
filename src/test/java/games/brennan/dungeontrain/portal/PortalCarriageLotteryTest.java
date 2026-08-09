package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalCarriageSelection.Rate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The draw that decides which carriage groups hold a portal: one group in {@code every}, hashed from
 * the world seed and the group's ordinal rather than counted off on a fixed period.
 *
 * <p>{@link PortalCarriageRoleTest} covers what a chosen group is made of. This covers which groups
 * get chosen, and the two properties the rest of the portal system leans on: the answer never
 * changes for a given world, and it is not a pattern a player can read off the train.</p>
 */
final class PortalCarriageLotteryTest {

    private static final int GROUP = 3;
    private static final long SEED = 0x5DEADBEEFL;

    /** Groups sampled for the rate checks — enough that a 1-in-20 rate has ~1000 hits to count. */
    private static final int SAMPLE_GROUPS = 20_000;

    /**
     * The property everything else depends on. A carriage's blocks are re-stamped whenever the
     * rolling window brings it round again, and the pair tick re-derives roles every tick, so a
     * verdict that drifted would turn a corridor into an ordinary carriage under a player standing
     * in it.
     */
    @Test
    @DisplayName("the same carriage in the same world always gets the same verdict")
    void verdictIsStable() {
        for (int i = -5_000; i <= 5_000; i++) {
            boolean first = PortalCarriageSelection.isPortalPart(i, GROUP, Rate.lottery(20), SEED);
            for (int repeat = 0; repeat < 3; repeat++) {
                assertEquals(first, PortalCarriageSelection.isPortalPart(i, GROUP, Rate.lottery(20), SEED),
                    "verdict drifted at index " + i);
            }
        }
    }

    /** A seedless hash would put portals at identical group ordinals in every world ever generated. */
    @Test
    @DisplayName("two worlds pick different groups")
    void seedChangesTheDraw() {
        Set<Integer> a = chosenAnchors(SEED, 20, 2_000);
        Set<Integer> b = chosenAnchors(SEED + 1, 20, 2_000);

        assertFalse(a.isEmpty(), "seed A chose nothing");
        assertFalse(b.isEmpty(), "seed B chose nothing");
        assertFalse(a.equals(b), "both worlds chose exactly the same groups");
    }

    @Test
    @DisplayName("roughly one group in every wins a portal")
    void rateMatchesEvery() {
        for (int every : new int[] {2, 5, 20, 64}) {
            int hits = chosenAnchors(SEED, every, SAMPLE_GROUPS).size();
            double expected = (double) SAMPLE_GROUPS / every;
            // Generous: this guards against a hash that clumps or skews, not against ordinary
            // sampling noise, and it must not turn into a flaky test on a seed change.
            assertTrue(hits > expected * 0.75 && hits < expected * 1.25,
                "every=" + every + " chose " + hits + " of " + SAMPLE_GROUPS
                    + " groups, expected about " + expected);
        }
    }

    /**
     * The point of the change. On the old rule the gap between portals was always the same number,
     * so a player who had seen two knew where the third was.
     */
    @Test
    @DisplayName("the gaps between portals vary rather than repeating a fixed period")
    void gapsAreNotAFixedPeriod() {
        Set<Integer> gaps = new HashSet<>();
        int previous = Integer.MIN_VALUE;
        for (int anchor = 0; anchor < SAMPLE_GROUPS * GROUP; anchor += GROUP) {
            if (!PortalCarriageSelection.isPortalPart(anchor, GROUP, Rate.lottery(20), SEED)) continue;
            if (previous != Integer.MIN_VALUE) gaps.add((anchor - previous) / GROUP);
            previous = anchor;
        }
        assertTrue(gaps.size() > 10, "only " + gaps.size() + " distinct gap lengths — that reads as a cadence");
    }

    @Test
    @DisplayName("every=1 gives every group a portal and off gives none")
    void boundariesHold() {
        for (int i = -100; i <= 100; i++) {
            assertTrue(PortalCarriageSelection.isPortalPart(i, GROUP, Rate.lottery(1), SEED),
                "index " + i + " missed a portal at every=1");
            assertFalse(PortalCarriageSelection.isPortalPart(
                i, GROUP, Rate.OFF, SEED),
                "index " + i + " got a portal while off");
        }
    }

    /**
     * Carriage indices go negative behind the origin, and the group ordinal with them. The draw has
     * to treat that half of the track like the other one rather than mirroring it or skewing its rate.
     */
    @Test
    @DisplayName("the track behind the origin draws like the track ahead of it")
    void negativeIndicesDrawTheSame() {
        int behind = 0;
        int ahead = 0;
        for (int group = 1; group <= SAMPLE_GROUPS; group++) {
            if (PortalCarriageSelection.isPortalPart(-group * GROUP, GROUP, Rate.lottery(20), SEED)) behind++;
            if (PortalCarriageSelection.isPortalPart(group * GROUP, GROUP, Rate.lottery(20), SEED)) ahead++;
        }
        double expected = (double) SAMPLE_GROUPS / 20;
        assertTrue(behind > expected * 0.75 && behind < expected * 1.25,
            "behind the origin: " + behind + " of " + SAMPLE_GROUPS + ", expected about " + expected);
        assertTrue(ahead > expected * 0.75 && ahead < expected * 1.25,
            "ahead of the origin: " + ahead + " of " + SAMPLE_GROUPS + ", expected about " + expected);
    }

    /**
     * The dev-creative escape hatch: a periodic rate is the old cadence exactly, so a tester in
     * creative always has a portal a couple of groups away, and the seed cannot move it.
     */
    @Test
    @DisplayName("a periodic rate lands on every nth group in every world alike")
    void periodicRateIsTheOldCadence() {
        for (long seed : new long[] {SEED, 0L, -1L, 12345L}) {
            for (int group = -40; group <= 40; group++) {
                int anchor = group * GROUP;
                assertEquals(group % 2 == 0,
                    PortalCarriageSelection.isPortalPart(
                        anchor, GROUP, Rate.periodic(PortalCarriageSelection.DEV_CREATIVE_EVERY), seed),
                    "group " + group + " at seed " + seed);
            }
        }
    }

    /** Same rate, different rule — otherwise the periodic flag would not be doing anything. */
    @Test
    @DisplayName("the lottery does not agree with the cadence at the same rate")
    void lotteryDiffersFromPeriodicAtTheSameRate() {
        int disagreements = 0;
        for (int group = 0; group < 200; group++) {
            int anchor = group * GROUP;
            boolean periodic = PortalCarriageSelection.isPortalPart(anchor, GROUP, Rate.periodic(2), SEED);
            boolean lottery = PortalCarriageSelection.isPortalPart(anchor, GROUP, Rate.lottery(2), SEED);
            if (periodic != lottery) disagreements++;
        }
        assertTrue(disagreements > 20, "only " + disagreements + " of 200 groups differed — the "
            + "lottery is tracking the cadence");
    }

    @Test
    @DisplayName("off is off under either rule")
    void offIsOffEitherWay() {
        for (int i = -100; i <= 100; i++) {
            assertFalse(PortalCarriageSelection.isPortalPart(i, GROUP, Rate.OFF, SEED));
            assertFalse(PortalCarriageSelection.isPortalPart(
                i, GROUP, new Rate(PortalCarriageSelection.CARRIAGE_EVERY_OFF, true), SEED));
        }
    }

    /** Anchors of the groups that won a portal, over {@code groups} groups from the origin forwards. */
    private static Set<Integer> chosenAnchors(long seed, int every, int groups) {
        Set<Integer> chosen = new HashSet<>();
        for (int group = 0; group < groups; group++) {
            int anchor = group * GROUP;
            if (PortalCarriageSelection.isPortalPart(anchor, GROUP, Rate.lottery(every), seed)) chosen.add(anchor);
        }
        return chosen;
    }
}
