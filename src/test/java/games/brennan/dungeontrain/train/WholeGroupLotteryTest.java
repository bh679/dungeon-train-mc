package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.template.SeededDraw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of {@link WholeGroupSelection} — the one-in-N lottery over group ordinals.
 *
 * <p>Pinned the way {@code PortalCarriageLotteryTest} pins the portal draw: a verdict is a function
 * of {@code (seed, ordinal, every)} and nothing else, the rate comes out near {@code 1/N} over a long
 * stretch of track, {@code every == 1} is every group and {@code OFF} is none, and the track behind
 * the origin draws the same way as the track ahead.</p>
 */
final class WholeGroupLotteryTest {

    private static final int GROUP = 3;
    private static final long SEED = 0x5EEDL;

    @Test
    @DisplayName("off draws nothing; every=1 draws every group")
    void offAndAlways() {
        for (int anchor = -300; anchor <= 300; anchor += GROUP) {
            assertFalse(WholeGroupSelection.isWholeGroup(anchor, GROUP, WholeGroupSettings.OFF, SEED));
            assertTrue(WholeGroupSelection.isWholeGroup(anchor, GROUP, 1, SEED));
        }
    }

    @Test
    @DisplayName("stable: the same (seed, anchor) answers the same twice")
    void stable() {
        for (int anchor = -3000; anchor <= 3000; anchor += GROUP) {
            assertEquals(WholeGroupSelection.isWholeGroup(anchor, GROUP, 12, SEED),
                WholeGroupSelection.isWholeGroup(anchor, GROUP, 12, SEED));
        }
    }

    @Test
    @DisplayName("every slot of a group agrees with its anchor")
    void slotsAgreeWithAnchor() {
        for (int anchor = -300; anchor <= 300; anchor += GROUP) {
            boolean expected = WholeGroupSelection.isWholeGroup(anchor, GROUP, 5, SEED);
            for (int slot = 0; slot < GROUP; slot++) {
                assertEquals(expected, WholeGroupSelection.isWholeGroup(anchor + slot, GROUP, 5, SEED));
            }
        }
    }

    @Test
    @DisplayName("the rate lands near 1/N over twenty thousand groups")
    void rateIsAboutOneInN() {
        for (int every : new int[] {3, 12, 40}) {
            int hits = 0;
            int groups = 20_000;
            for (int g = 0; g < groups; g++) {
                if (WholeGroupSelection.isWholeGroup(g * GROUP, GROUP, every, SEED)) hits++;
            }
            double rate = hits / (double) groups;
            double target = 1.0 / every;
            assertTrue(Math.abs(rate - target) < target * 0.15,
                "every=" + every + " drew " + rate + ", expected about " + target);
        }
    }

    @Test
    @DisplayName("a different seed draws a different track")
    void seedMatters() {
        int differ = 0;
        for (int g = 0; g < 2_000; g++) {
            if (WholeGroupSelection.isWholeGroup(g * GROUP, GROUP, 4, SEED)
                != WholeGroupSelection.isWholeGroup(g * GROUP, GROUP, 4, SEED + 1)) differ++;
        }
        assertTrue(differ > 200, "only " + differ + " of 2000 verdicts moved with the seed");
    }

    @Test
    @DisplayName("salted: the group draw does not line up with the unsalted draw over the same ordinals")
    void decorrelatedFromUnsaltedDraw() {
        int same = 0;
        for (int g = 0; g < 2_000; g++) {
            boolean whole = WholeGroupSelection.isWholeGroup(g * GROUP, GROUP, 4, SEED);
            boolean raw = SeededDraw.hit(SEED, g, 4);
            if (whole == raw) same++;
        }
        assertNotEquals(2_000, same, "the whole-group lottery must not be the bare hash of the ordinal");
    }
}
