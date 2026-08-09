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

    /**
     * The rate the command promises is the one that survives the gap rule, not the raw draw — the
     * threshold is solved backwards from it for exactly this reason. Rates denser than the gap can
     * carry (below about 1 in 12) are excluded here; {@link #clampedRatesLandOnTheGap} covers those.
     */
    @Test
    @DisplayName("roughly one group in every wins a portal, after the gap rule has taken its cut")
    void rateMatchesEvery() {
        for (int every : new int[] {15, 20, 30, 64}) {
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
     * so a player who had seen two knew where the third was. The minimum gap puts a floor under the
     * spacing without restoring that.
     */
    @Test
    @DisplayName("the gaps between portals vary rather than repeating a fixed period")
    void gapsAreNotAFixedPeriod() {
        Set<Integer> gaps = groupGaps(SEED, 15);
        assertTrue(gaps.size() > 10, "only " + gaps.size() + " distinct gap lengths — that reads as a cadence");
    }

    /**
     * Walking out of an exit corridor and straight into the next entry hands the player the
     * machinery. The rule that prevents it is local — a group looks at the four draws behind it and
     * nothing further — so this checks the guarantee actually holds over a long run rather than
     * trusting the argument.
     */
    @Test
    @DisplayName("no two portals ever land closer than the minimum gap")
    void portalsAreNeverCloserThanTheMinimumGap() {
        for (int every : new int[] {15, 20, 30}) {
            for (long seed : new long[] {SEED, 0L, -1L}) {
                int smallest = groupGaps(seed, every).stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
                assertTrue(smallest >= PortalCarriageSelection.MIN_GROUP_GAP,
                    "every=" + every + " seed=" + seed + " put two portals " + smallest + " groups apart");
            }
        }
    }

    /** The origin is where the group ordinal changes sign — the gap must not open up across it. */
    @Test
    @DisplayName("the minimum gap holds across the origin too")
    void minimumGapHoldsThroughNegativeIndices() {
        int previous = Integer.MIN_VALUE;
        for (int group = -2_000; group <= 2_000; group++) {
            if (!PortalCarriageSelection.isPortalPart(group * GROUP, GROUP, Rate.lottery(15), SEED)) continue;
            if (previous != Integer.MIN_VALUE) {
                assertTrue(group - previous >= PortalCarriageSelection.MIN_GROUP_GAP,
                    "groups " + previous + " and " + group + " are too close");
            }
            previous = group;
        }
    }

    /**
     * A rate denser than the gap can carry has one honest answer: as dense as the gap permits. The
     * alternative is quietly delivering something else and looking broken.
     */
    @Test
    @DisplayName("a rate denser than the gap allows lands on exactly every nth group")
    void clampedRatesLandOnTheGap() {
        assertTrue(PortalCarriageSelection.isGapClamped(5), "1-in-5 should be clamped by the gap");
        for (int group = 0; group < 500; group++) {
            assertEquals(group % PortalCarriageSelection.MIN_GROUP_GAP == 0,
                PortalCarriageSelection.isPortalPart(group * GROUP, GROUP, Rate.lottery(5), SEED),
                "group " + group);
        }
        assertFalse(PortalCarriageSelection.isGapClamped(15), "1-in-15 is reachable and must not clamp");
    }

    /** Distinct gap lengths, in groups, between consecutive portals over the sample. */
    private static Set<Integer> groupGaps(long seed, int every) {
        Set<Integer> gaps = new HashSet<>();
        int previous = Integer.MIN_VALUE;
        for (int group = 0; group < SAMPLE_GROUPS; group++) {
            if (!PortalCarriageSelection.isPortalPart(group * GROUP, GROUP, Rate.lottery(every), seed)) continue;
            if (previous != Integer.MIN_VALUE) gaps.add(group - previous);
            previous = group;
        }
        return gaps;
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

    /**
     * The group is wider than the portal, and the difference matters to anything that must stay out of
     * a portal group altogether rather than merely off its corridors.
     *
     * <p>PlayerMob spawning is that caller: a mob marches the length of the train, so one placed in an
     * ordinary slot of a portal group walks itself into a corridor under its own steam. It therefore
     * gates on {@link PortalCarriageSelection#isPortalGroup}, and simplifying that to
     * {@code isPortalPart} — which looks equivalent at the shipped group size of three, where the
     * portal fills the group — would quietly reopen the hole for anyone running a larger one. This
     * pins the two predicates apart so that edit fails here.</p>
     */
    @Test
    @DisplayName("a portal group is wider than the portal when the group size is")
    void portalGroupCoversTheSlotsBeyondThePortal() {
        int wideGroup = 5;                       // > PORTAL_GROUP_SPAN, so slots 3 and 4 are ordinary
        Rate rate = Rate.periodic(2);            // deterministic: group 0 wins, group 1 does not

        for (int slot = 0; slot < wideGroup; slot++) {
            assertTrue(PortalCarriageSelection.isPortalGroup(slot, wideGroup, rate, SEED),
                "slot " + slot + " of a winning group should be in a portal group");
        }

        for (int slot = 0; slot < PortalCarriageSelection.PORTAL_GROUP_SPAN; slot++) {
            assertTrue(PortalCarriageSelection.isPortalPart(slot, wideGroup, rate, SEED),
                "slot " + slot + " is entry/middle/exit and should be a portal part");
        }
        for (int slot = PortalCarriageSelection.PORTAL_GROUP_SPAN; slot < wideGroup; slot++) {
            assertFalse(PortalCarriageSelection.isPortalPart(slot, wideGroup, rate, SEED),
                "slot " + slot + " is an ordinary carriage and should not be a portal part");
        }

        // A group that lost the draw is neither, so the distinction above is about the portal's width
        // and not about isPortalGroup simply answering true everywhere.
        for (int slot = 0; slot < wideGroup; slot++) {
            int index = wideGroup + slot;
            assertFalse(PortalCarriageSelection.isPortalGroup(index, wideGroup, rate, SEED),
                "index " + index + " is in a group that lost the draw");
            assertFalse(PortalCarriageSelection.isPortalPart(index, wideGroup, rate, SEED),
                "index " + index + " is in a group that lost the draw");
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
