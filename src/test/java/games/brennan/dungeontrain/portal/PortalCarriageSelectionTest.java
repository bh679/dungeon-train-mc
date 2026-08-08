package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalCarriageSelection} — the rarity gate that thins the portal-carriage
 * lattice down to roughly one pair per {@code oneInGroups} carriage groups.
 */
final class PortalCarriageSelectionTest {

    private static final long SEED = 0x5EEDCAFEL;
    private static final int EVERY = PortalCarriageSelection.DEFAULT_CARRIAGE_EVERY;
    private static final int GROUP_SIZE = 3;
    private static final int ONE_IN = PortalCarriageSelection.DEFAULT_ONE_IN_GROUPS;

    private static boolean isPortal(int index) {
        return PortalCarriageSelection.isPortalCarriage(SEED, index, EVERY, ONE_IN, GROUP_SIZE);
    }

    /** The ENTRY carriage indices accepted over {@code [-half, half)}. */
    private static List<Integer> acceptedEntries(int half) {
        List<Integer> entries = new ArrayList<>();
        for (int i = -half; i < half; i++) {
            if (isPortal(i) && PortalCarriageRole.roleFor(i, EVERY) == PortalCarriageRole.ENTRY) {
                entries.add(i);
            }
        }
        return entries;
    }

    @Test
    @DisplayName("off means no carriage is a portal, whatever the rarity says")
    void offWins() {
        for (int i = -20; i <= 20; i++) {
            assertFalse(PortalCarriageSelection.isPortalCarriage(
                SEED, i, PortalCarriageSelection.CARRIAGE_EVERY_OFF, 1, GROUP_SIZE));
        }
    }

    @Test
    @DisplayName("portal carriages still only land on the entry/exit lattice")
    void staysOnLattice() {
        for (int i = -200; i <= 200; i++) {
            if (Math.floorMod(i, EVERY) != 0) {
                assertFalse(isPortal(i), "index " + i + " is off the lattice but was selected");
            }
        }
    }

    /**
     * The invariant the whole design rests on: the gate is applied to the pair, not the carriage, so
     * an ENTRY and the EXIT it hands off to are accepted or rejected together. A half-accepted pair
     * would be a corridor that opens into a room with no way back onto the train.
     */
    @Test
    @DisplayName("both carriages of a pair are accepted or rejected together")
    void pairsAreNeverSplit() {
        for (int i = -400; i <= 400; i += EVERY) {
            int partner = PortalCarriageRole.partnerIndex(i, EVERY);
            assertEquals(isPortal(i), isPortal(partner),
                "index " + i + " and partner " + partner + " disagreed");
        }
    }

    /**
     * A carriage's blocks are re-stamped whenever the rolling window brings it round again, so the
     * verdict has to be a pure function — not a draw that could come out differently the second time.
     */
    @Test
    @DisplayName("the same carriage gets the same verdict every time it is asked")
    void verdictIsStable() {
        for (int i = -100; i <= 100; i++) {
            boolean first = isPortal(i);
            for (int repeat = 0; repeat < 5; repeat++) {
                assertEquals(first, isPortal(i), "index " + i + " changed its mind");
            }
        }
    }

    @Test
    @DisplayName("different world seeds place portals differently")
    void seedChangesTheLayout() {
        boolean differs = false;
        for (int i = -400; i <= 400; i += EVERY) {
            if (PortalCarriageSelection.isPortalCarriage(SEED, i, EVERY, ONE_IN, GROUP_SIZE)
                != PortalCarriageSelection.isPortalCarriage(SEED + 1, i, EVERY, ONE_IN, GROUP_SIZE)) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "two different seeds produced an identical portal layout");
    }

    @Test
    @DisplayName("roughly one portal pair per oneInGroups carriage groups")
    void frequencyMatchesTheSetting() {
        int half = 100_000;
        int carriages = half * 2;
        int pairs = acceptedEntries(half).size();

        double expected = (double) carriages / (ONE_IN * GROUP_SIZE);
        // ±15%: the hash is not a perfect uniform generator and the sample, while large, is finite.
        assertTrue(pairs > expected * 0.85 && pairs < expected * 1.15,
            "expected about " + Math.round(expected) + " pairs over " + carriages
                + " carriages, got " + pairs);
    }

    /**
     * The point of the change was to stop portals arriving on a metronome. A hash that degenerated to
     * "every nth candidate" would still pass the frequency test above, so check the gaps actually vary.
     */
    @Test
    @DisplayName("accepted pairs are irregularly spaced, not on a fixed interval")
    void spacingIsIrregular() {
        List<Integer> entries = acceptedEntries(20_000);
        assertTrue(entries.size() > 20, "not enough pairs to judge spacing: " + entries.size());

        long distinctGaps = new java.util.HashSet<>(gapsBetween(entries)).size();
        assertTrue(distinctGaps > 3,
            "portal pairs fell on a near-fixed interval (" + distinctGaps + " distinct gaps)");
    }

    private static List<Integer> gapsBetween(List<Integer> entries) {
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < entries.size(); i++) {
            gaps.add(entries.get(i) - entries.get(i - 1));
        }
        return gaps;
    }

    /**
     * Carriage indices go negative when the train extends backwards. {@code floorDiv}/{@code floorMod}
     * keep the lattice and the pairing symmetric about the origin; {@code /} and {@code %} would not.
     */
    @Test
    @DisplayName("the backwards half of the train gets portals at the same rate")
    void negativeIndicesBehave() {
        int half = 60_000;
        int behind = 0;
        int ahead = 0;
        for (int i = -half; i < half; i += EVERY) {
            if (!isPortal(i) || PortalCarriageRole.roleFor(i, EVERY) != PortalCarriageRole.ENTRY) continue;
            if (i < 0) behind++; else ahead++;
        }
        assertTrue(behind > 0 && ahead > 0, "one side of the origin had no portals at all");
        assertTrue(Math.abs(behind - ahead) < Math.max(behind, ahead) * 0.35,
            "portal density differed either side of the origin: " + behind + " behind, " + ahead + " ahead");
    }

    /**
     * {@code /dungeontrain portal rarity 1} is the dev escape hatch — it should hand back exactly the
     * un-thinned lattice the system had before rarity existed.
     */
    @Test
    @DisplayName("rarity 1 restores the old every-nth-carriage layout")
    void rarityOneAcceptsEveryCandidate() {
        for (int i = -200; i <= 200; i++) {
            boolean onLattice = Math.floorMod(i, EVERY) == 0;
            assertEquals(onLattice,
                PortalCarriageSelection.isPortalCarriage(SEED, i, EVERY, 1, GROUP_SIZE),
                "index " + i);
        }
    }

    @Test
    @DisplayName("a bigger group size means more carriages between portals, not more portals")
    void rarityCountsGroupsNotCarriages() {
        int half = 60_000;
        int small = 0;
        int large = 0;
        for (int i = -half; i < half; i += EVERY) {
            if (PortalCarriageRole.roleFor(i, EVERY) != PortalCarriageRole.ENTRY) continue;
            if (PortalCarriageSelection.isPortalCarriage(SEED, i, EVERY, ONE_IN, 2)) small++;
            if (PortalCarriageSelection.isPortalCarriage(SEED, i, EVERY, ONE_IN, 6)) large++;
        }
        // Groups of 6 are three times as long as groups of 2, so one pair per 20 groups is a third
        // as many pairs over the same stretch of track.
        assertTrue(large < small,
            "larger groups should yield fewer pairs per carriage: " + small + " vs " + large);
    }
}
