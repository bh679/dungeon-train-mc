package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalCarriageSelection.Rate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The opening stretch of track holds no portal: nothing before Diff-Level
 * {@link PortalCarriageSelection#MIN_PORTAL_LEVEL}, and the draw starts counting from there rather
 * than running from the origin and throwing its early results away.
 *
 * <p>{@link PortalCarriageLotteryTest} covers the shape of the draw itself, ungated. This covers
 * where the draw is allowed to begin, and the property the re-indexing is for: the wait past the
 * gate is the ordinary between-portals wait, not a fresh one-in-{@code every} gamble on top of the
 * ride the player already made to get there.</p>
 */
final class PortalMinLevelGateTest {

    private static final int GROUP = 3;
    private static final long SEED = 0x5DEADBEEFL;

    /** The shipped progression shape: twenty carriages a level, delayed one level. */
    private static final int CARRIAGES_PER_TIER = 20;
    private static final int DELAY = 1;

    /** Where the shipped settings put the gate: sixty carriages out, group ordinal twenty. */
    private static final int SHIPPED_GATE = 20;

    @Test
    @DisplayName("the gate lands on the first group at the minimum difficulty level")
    void gateArithmetic() {
        // (MIN_PORTAL_LEVEL + delay) * carriagesPerTier = 60 carriages, which is group 20 at size 3.
        assertEquals(SHIPPED_GATE, PortalCarriageSelection.firstEligibleGroup(
            PortalCarriageSelection.MIN_PORTAL_LEVEL, GROUP, CARRIAGES_PER_TIER, DELAY));

        // Rounded up, so the gate group's anchor is never short of the boundary: 60 carriages at a
        // group size of seven is group 9 (anchor 63), not group 8 (anchor 56, still level 1).
        assertEquals(9, PortalCarriageSelection.firstEligibleGroup(2, 7, 20, 1));

        // The delay shifts the whole progression, so it shifts the gate with it: (2 + 3) * 20 = 100
        // carriages, which is group 34 at size 3 (anchor 102 — group 33 anchors at 99, still short).
        assertEquals(34, PortalCarriageSelection.firstEligibleGroup(2, 3, 20, 3));
        assertEquals(100, PortalCarriageSelection.firstEligibleGroup(2, 3, 100, 1));

        // No level asked for, no gate — and the degenerate inputs floor rather than throw.
        assertEquals(0, PortalCarriageSelection.firstEligibleGroup(0, GROUP, CARRIAGES_PER_TIER, DELAY));
        assertEquals(0, PortalCarriageSelection.firstEligibleGroup(-3, GROUP, CARRIAGES_PER_TIER, DELAY));
        // Degenerate inputs floor rather than divide by zero or run the gate backwards: a group size
        // of zero counts as one, and a negative delay as none — so this is (2 + 0) * 20 = 40 groups.
        assertEquals(40, PortalCarriageSelection.firstEligibleGroup(
            PortalCarriageSelection.MIN_PORTAL_LEVEL, 0, CARRIAGES_PER_TIER, -5));
        assertEquals(1, PortalCarriageSelection.firstEligibleGroup(1, GROUP, 0, 0));
    }

    /**
     * The whole point: a new player cannot walk into a portal in their opening carriages.
     *
     * <p>Walked group by group rather than carriage by carriage, because the gate is a property of
     * the group — a group whose anchor has reached the level is eligible whole, including the two
     * slots after the anchor. Its entry corridor is the anchor either way, so the nearest corridor to
     * the origin sits exactly {@code gate * groupSize} carriages out in both directions.</p>
     */
    @Test
    @DisplayName("no carriage before the gate is any part of a portal, in either direction")
    void nothingBeforeTheGate() {
        for (int every : new int[] {1, 5, 15, 40}) {
            Rate rate = Rate.lottery(every);
            for (int group = -(SHIPPED_GATE - 1); group < SHIPPED_GATE; group++) {
                for (int slot = 0; slot < GROUP; slot++) {
                    int carriage = group * GROUP + slot;
                    assertFalse(PortalCarriageSelection.isPortalPart(carriage, GROUP, rate, SEED, SHIPPED_GATE),
                        "carriage " + carriage + " is before the gate but drew a portal at every=" + every);
                    assertFalse(PortalCarriageSelection.isPortalGroup(carriage, GROUP, rate, SEED, SHIPPED_GATE),
                        "carriage " + carriage + " is before the gate but is in a portal group at every=" + every);
                }
            }
        }
    }

    /** The nearest corridor to the origin is the gate group's entry, the same distance out either way. */
    @Test
    @DisplayName("the nearest corridor sits at the gate boundary in both directions")
    void nearestCorridorSitsAtTheBoundary() {
        int boundary = SHIPPED_GATE * GROUP;
        assertEquals(60, boundary, "the shipped gate should be sixty carriages out");
        assertTrue(PortalCarriageSelection.isPortalCarriage(
            boundary, GROUP, Rate.lottery(1), SEED, SHIPPED_GATE), "entry corridor ahead of the origin");
        assertTrue(PortalCarriageSelection.isPortalCarriage(
            -boundary, GROUP, Rate.lottery(1), SEED, SHIPPED_GATE), "entry corridor behind the origin");
        assertFalse(PortalCarriageSelection.isPortalGroup(
            boundary - 1, GROUP, Rate.lottery(1), SEED, SHIPPED_GATE), "the carriage just short of it");
        assertFalse(PortalCarriageSelection.isPortalGroup(
            -boundary + GROUP, GROUP, Rate.lottery(1), SEED, SHIPPED_GATE), "the group just short of it, behind");
    }

    /**
     * A rate of one-in-one is the sharpest reading of "the gate opens exactly here": every group from
     * the gate onwards holds a portal, and not one before it.
     */
    @Test
    @DisplayName("the gate group itself is eligible")
    void gateGroupIsTheFirstEligibleOne() {
        for (int group = 0; group < SHIPPED_GATE + 20; group++) {
            boolean expected = group >= SHIPPED_GATE;
            assertEquals(expected,
                PortalCarriageSelection.isPortalPart(group * GROUP, GROUP, Rate.lottery(1), SEED, SHIPPED_GATE),
                "group " + group);
            assertEquals(expected,
                PortalCarriageSelection.isPortalPart(-group * GROUP, GROUP, Rate.lottery(1), SEED, SHIPPED_GATE),
                "group -" + group);
        }
    }

    /**
     * The gate is the draw's origin, not a filter laid over a draw that ran from the world origin.
     *
     * <p>Two different gates therefore produce the <em>same</em> sequence of verdicts measured from
     * their own gate. A filtered draw could not: it would keep the world-origin ordinals, so moving
     * the gate would slide a fixed pattern under it and the two sequences would disagree almost
     * everywhere.</p>
     */
    @Test
    @DisplayName("the draw is re-indexed to the gate rather than filtered behind it")
    void drawIsReindexedToTheGate() {
        int other = 137;
        int disagreements = 0;
        for (int offset = 0; offset < 1_000; offset++) {
            boolean shipped = PortalCarriageSelection.isPortalPart(
                (SHIPPED_GATE + offset) * GROUP, GROUP, Rate.lottery(15), SEED, SHIPPED_GATE);
            boolean moved = PortalCarriageSelection.isPortalPart(
                (other + offset) * GROUP, GROUP, Rate.lottery(15), SEED, other);
            assertEquals(shipped, moved, "offset " + offset + " past the gate");
            if (shipped) disagreements++;
        }
        assertTrue(disagreements > 0, "no portals at all past the gate — the comparison proves nothing");
    }

    /**
     * What the re-indexing buys. A filtered draw burns its first twenty results against dead track, so
     * the player who has ridden sixty carriages to earn portals then waits a fresh one-in-fifteen for
     * the first one. Starting the count at the gate makes that wait the ordinary between-portals wait.
     */
    @Test
    @DisplayName("the first portal arrives on the ordinary cadence measured from the gate")
    void firstPortalComesPromptlyAfterTheGate() {
        int every = 15;
        int seeds = 400;
        long totalWait = 0;
        int worstWait = 0;

        for (int s = 0; s < seeds; s++) {
            long seed = SEED + s * 0x9E3779B97F4A7C15L;
            int wait = -1;
            for (int offset = 0; offset < every * 20; offset++) {
                if (PortalCarriageSelection.isPortalPart(
                        (SHIPPED_GATE + offset) * GROUP, GROUP, Rate.lottery(every), seed, SHIPPED_GATE)) {
                    wait = offset;
                    break;
                }
            }
            assertTrue(wait >= 0, "seed " + seed + " produced no portal within " + (every * 20) + " groups");
            totalWait += wait;
            worstWait = Math.max(worstWait, wait);
        }

        double meanWait = (double) totalWait / seeds;
        // A draw that started at the gate waits about `every` groups on average. A draw that ran from
        // the origin and was filtered would too — but a draw that started at the gate and was ALSO
        // suppressed by the dead track behind it would sit well above this.
        assertTrue(meanWait < every * 1.5,
            "mean wait past the gate was " + meanWait + " groups, expected about " + every);
    }

    /** Portals five apart is a property of the whole track, including the first ones past the gate. */
    @Test
    @DisplayName("the minimum gap still holds either side of the gate")
    void minimumGapHoldsPastTheGate() {
        int previous = Integer.MIN_VALUE;
        for (int group = SHIPPED_GATE; group < SHIPPED_GATE + 5_000; group++) {
            if (!PortalCarriageSelection.isPortalPart(
                group * GROUP, GROUP, Rate.lottery(15), SEED, SHIPPED_GATE)) continue;
            if (previous != Integer.MIN_VALUE) {
                assertTrue(group - previous >= PortalCarriageSelection.MIN_GROUP_GAP,
                    "groups " + previous + " and " + group + " are too close");
            }
            previous = group;
        }
        assertTrue(previous != Integer.MIN_VALUE, "no portals past the gate to check the gap between");
    }

    /** The track behind the origin is gated and drawn like the track ahead of it, not mirrored onto it. */
    @Test
    @DisplayName("the track behind the origin is gated the same and draws at the same rate")
    void behindTheOriginMatchesAheadOfIt() {
        int every = 20;
        int groups = 20_000;
        int behind = 0;
        int ahead = 0;
        for (int offset = 0; offset < groups; offset++) {
            if (PortalCarriageSelection.isPortalPart(
                -(SHIPPED_GATE + offset) * GROUP, GROUP, Rate.lottery(every), SEED, SHIPPED_GATE)) behind++;
            if (PortalCarriageSelection.isPortalPart(
                (SHIPPED_GATE + offset) * GROUP, GROUP, Rate.lottery(every), SEED, SHIPPED_GATE)) ahead++;
        }
        double expected = (double) groups / every;
        assertTrue(behind > expected * 0.75 && behind < expected * 1.25,
            "behind the origin: " + behind + " of " + groups + ", expected about " + expected);
        assertTrue(ahead > expected * 0.75 && ahead < expected * 1.25,
            "ahead of the origin: " + ahead + " of " + groups + ", expected about " + expected);
    }

    /**
     * The dev-creative cadence exists so a tester always has a portal a short ride away. A gate that
     * applied to it would put the nearest one sixty carriages off and take that away.
     */
    @Test
    @DisplayName("the dev-creative cadence is not gated")
    void periodicRateLiftsTheGate() {
        Rate rate = Rate.periodic(PortalCarriageSelection.DEV_CREATIVE_EVERY);
        for (int group = -40; group <= 40; group++) {
            assertEquals(Math.floorMod(group, PortalCarriageSelection.DEV_CREATIVE_EVERY) == 0,
                PortalCarriageSelection.isPortalPart(group * GROUP, GROUP, rate, SEED, SHIPPED_GATE),
                "group " + group + " under the dev-creative cadence");
        }
    }

    /** Off is off, gate or no gate. */
    @Test
    @DisplayName("the gate does not turn portals back on where the rate is off")
    void offStaysOff() {
        for (int group = 0; group <= SHIPPED_GATE + 40; group++) {
            assertFalse(PortalCarriageSelection.isPortalPart(
                group * GROUP, GROUP, Rate.OFF, SEED, SHIPPED_GATE), "group " + group);
        }
    }

    /**
     * The property the whole portal system leans on, unchanged by the gate: a carriage's verdict is
     * the same on every reload and for every reader, so a corridor cannot turn into an ordinary
     * carriage under someone standing in it when the rolling window re-stamps it.
     */
    @Test
    @DisplayName("the gate does not make a verdict drift")
    void verdictIsStableWithTheGate() {
        for (int i = -5_000; i <= 5_000; i++) {
            boolean first = PortalCarriageSelection.isPortalPart(
                i, GROUP, Rate.lottery(20), SEED, SHIPPED_GATE);
            for (int repeat = 0; repeat < 3; repeat++) {
                assertEquals(first, PortalCarriageSelection.isPortalPart(
                        i, GROUP, Rate.lottery(20), SEED, SHIPPED_GATE),
                    "verdict drifted at index " + i);
            }
        }
    }

    /**
     * A gate of zero is the ungated draw exactly — the overload the existing lottery tests exercise,
     * so a change to the gated path cannot quietly move the ungated one out from under them.
     */
    @Test
    @DisplayName("a gate of zero is the ungated draw")
    void zeroGateIsUngated() {
        for (int every : new int[] {1, 5, 15, 20}) {
            for (int i = -2_000; i <= 2_000; i++) {
                assertEquals(
                    PortalCarriageSelection.isPortalPart(i, GROUP, Rate.lottery(every), SEED),
                    PortalCarriageSelection.isPortalPart(i, GROUP, Rate.lottery(every), SEED, 0),
                    "index " + i + " at every=" + every);
            }
        }
    }
}
