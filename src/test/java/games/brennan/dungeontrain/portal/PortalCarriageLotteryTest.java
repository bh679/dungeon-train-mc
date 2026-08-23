package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalCarriageSelection.Rate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
     * The rate the command promises is the rate the train delivers. With nothing thinning the draw
     * there is no gap between the two any more — the threshold is one division — so any rate the
     * command accepts is checkable the same way, dense ones included.
     */
    @Test
    @DisplayName("roughly one group in every wins a portal")
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
     * so a player who had seen two knew where the third was. Nothing constrains the spacing now, so
     * this is the only thing keeping the draw from reading as a cadence.
     */
    @Test
    @DisplayName("the gaps between portals vary rather than repeating a fixed period")
    void gapsAreNotAFixedPeriod() {
        Set<Integer> gaps = groupGaps(SEED, 15);
        assertTrue(gaps.size() > 10, "only " + gaps.size() + " distinct gap lengths — that reads as a cadence");
    }

    /**
     * There is <b>no</b> minimum spacing, and that is a deliberate property rather than an accident:
     * the old five-group floor capped achievable density near one group in twelve, so any denser
     * rate quietly became something else. Re-introducing a gap rule has to fail here.
     */
    @Test
    @DisplayName("portals may land back to back — there is no minimum spacing")
    void portalsMayLandAdjacent() {
        boolean sawAdjacent = false;
        for (int group = 0; group < SAMPLE_GROUPS && !sawAdjacent; group++) {
            sawAdjacent = PortalCarriageSelection.isPortalPart(group * GROUP, GROUP, Rate.lottery(5), SEED)
                && PortalCarriageSelection.isPortalPart((group + 1) * GROUP, GROUP, Rate.lottery(5), SEED);
        }
        assertTrue(sawAdjacent,
            "no two portals landed in consecutive groups over " + SAMPLE_GROUPS
                + " groups at 1-in-5 — something is enforcing spacing again");
    }

    /**
     * The shipped default is dense enough that the old gap rule could not have delivered it, so this
     * pins the thing that change was for: the number in the command is the number on the train.
     */
    @Test
    @DisplayName("the default rate is realised at the rate it claims")
    void defaultRateIsRealisedAsAsked() {
        for (long seed : new long[] {SEED, 0L, -1L, 12345L}) {
            int hits = 0;
            for (int group = 0; group < SAMPLE_GROUPS; group++) {
                if (PortalCarriageSelection.isPortalPart(
                        group * GROUP, GROUP,
                        Rate.lottery(PortalCarriageSelection.DEFAULT_CARRIAGE_EVERY), seed)) hits++;
            }
            double expected = (double) SAMPLE_GROUPS / PortalCarriageSelection.DEFAULT_CARRIAGE_EVERY;
            assertTrue(hits > expected * 0.85 && hits < expected * 1.15,
                "seed " + seed + ": " + hits + " of " + SAMPLE_GROUPS + ", expected about " + expected);
        }
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
     * The dev-creative escape hatch: a periodic rate lands on a fixed beat, so a tester in creative
     * always has a portal a short ride away, and the seed cannot move it.
     *
     * <p>The expectation is derived from {@code DEV_CREATIVE_EVERY} rather than written out, so
     * retuning the testing cadence does not fail a test that is about the rule, not the number.
     * {@link Math#floorMod} because the sweep runs behind the origin as well as ahead.</p>
     */
    @Test
    @DisplayName("a periodic rate lands on every nth group in every world alike")
    void periodicRateIsAFixedCadence() {
        for (long seed : new long[] {SEED, 0L, -1L, 12345L}) {
            for (int group = -40; group <= 40; group++) {
                int anchor = group * GROUP;
                assertEquals(Math.floorMod(group, PortalCarriageSelection.DEV_CREATIVE_EVERY) == 0,
                    PortalCarriageSelection.isPortalPart(
                        anchor, GROUP, Rate.periodic(PortalCarriageSelection.DEV_CREATIVE_EVERY), seed),
                    "group " + group + " at seed " + seed);
            }
        }
    }

    /**
     * The dev build's dense cadence stands in for a rate nobody has chosen, and steps aside for one
     * that has been. An unconditional override made {@code portal carriage 7} look broken in the dev
     * client — the world stored 7, the command said 7, and the train kept stamping every 2 — so this
     * pins the precedence rather than the number.
     */
    @Test
    @DisplayName("an explicitly set rate beats the dev testing cadence")
    void setByHandBeatsTheDevCadence() {
        assertEquals(7, PortalCarriageSelection.creativeEvery(7, true, true),
            "a rate set by hand must survive on a dev build");
        assertEquals(PortalCarriageSelection.DEV_CREATIVE_EVERY,
            PortalCarriageSelection.creativeEvery(7, false, true),
            "an untouched dev world takes the testing cadence");
        assertEquals(7, PortalCarriageSelection.creativeEvery(7, false, false),
            "a release build always takes the world's rate");
        assertEquals(7, PortalCarriageSelection.creativeEvery(7, true, false));
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
