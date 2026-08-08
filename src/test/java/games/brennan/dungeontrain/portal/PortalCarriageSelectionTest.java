package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalCarriageSelection} — which carriage groups are portal groups, and
 * which carriages within them carry a corridor.
 */
final class PortalCarriageSelectionTest {

    private static final long SEED = 0x5EEDCAFEL;
    private static final int GROUP_SIZE = 3;
    private static final int ONE_IN = PortalCarriageSelection.DEFAULT_ONE_IN_GROUPS;

    private static boolean isPortal(int index) {
        return PortalCarriageSelection.isPortalCarriage(SEED, index, true, ONE_IN, GROUP_SIZE);
    }

    /** The ENTRY carriage indices accepted over {@code [-half, half)}. */
    private static List<Integer> acceptedEntries(int half) {
        List<Integer> entries = new ArrayList<>();
        for (int i = -half; i < half; i++) {
            if (isPortal(i) && PortalCarriageRole.roleFor(i, GROUP_SIZE) == PortalCarriageRole.ENTRY) {
                entries.add(i);
            }
        }
        return entries;
    }

    @Test
    @DisplayName("disabled means no carriage is a portal, whatever the rarity says")
    void disabledWins() {
        for (int i = -20; i <= 20; i++) {
            assertFalse(PortalCarriageSelection.isPortalCarriage(SEED, i, false, 1, GROUP_SIZE));
        }
    }

    /**
     * At group size 1 the first and last carriage of a group are the same one, so an entry and its
     * exit would collide. There is nowhere for the pair to go, so no portals are placed.
     */
    @Test
    @DisplayName("a group too small to hold a pair gets no portals at all")
    void groupSizeOneIsDeclined() {
        for (int i = -50; i <= 50; i++) {
            assertFalse(PortalCarriageSelection.isPortalCarriage(SEED, i, true, 1, 1),
                "index " + i + " was made a portal in a group of 1");
        }
    }

    @Test
    @DisplayName("at group size 2 the pair is the whole group: entry then exit, nothing between")
    void groupSizeTwoIsAdjacent() {
        for (int i = -50; i <= 50; i++) {
            // rarity 1 selects every group, so every carriage of every group is one role or the other.
            assertTrue(PortalCarriageSelection.isPortalCarriage(SEED, i, true, 1, 2),
                "index " + i + " was not a portal at groupSize 2, rarity 1");
        }
    }

    @Test
    @DisplayName("only a group's first and last carriage carry a corridor")
    void middleCarriagesAreOrdinaryTrain() {
        for (int i = -300; i <= 300; i++) {
            int slot = Math.floorMod(i, GROUP_SIZE);
            if (slot != 0 && slot != GROUP_SIZE - 1) {
                assertFalse(isPortal(i), "middle-of-group index " + i + " was made a portal");
            }
        }
    }

    /**
     * The invariant the whole design rests on: a group is one Sable sub-level, and a pair's room is
     * anchored to its entry carriage. An exit in a neighbouring group would map into a room that
     * drifts away from it.
     */
    @Test
    @DisplayName("a portal never spans a group boundary")
    void portalsStayInsideOneGroup() {
        for (int i = -600; i <= 600; i++) {
            if (!isPortal(i)) continue;
            int partner = PortalCarriageRole.partnerIndex(i, GROUP_SIZE);
            assertEquals(
                PortalCarriageRole.entryIndexOf(i, GROUP_SIZE),
                PortalCarriageRole.entryIndexOf(partner, GROUP_SIZE),
                "portal carriage " + i + " and its partner " + partner + " are in different groups");
        }
    }

    @Test
    @DisplayName("both carriages of a pair are accepted or rejected together")
    void pairsAreNeverSplit() {
        for (int i = -600; i <= 600; i++) {
            if (Math.floorMod(i, GROUP_SIZE) != 0) continue;   // walk the entries
            int exit = PortalCarriageRole.partnerIndex(i, GROUP_SIZE);
            assertEquals(isPortal(i), isPortal(exit),
                "entry " + i + " and exit " + exit + " disagreed");
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
        for (int i = -600; i <= 600; i += GROUP_SIZE) {
            if (PortalCarriageSelection.isPortalCarriage(SEED, i, true, ONE_IN, GROUP_SIZE)
                != PortalCarriageSelection.isPortalCarriage(SEED + 1, i, true, ONE_IN, GROUP_SIZE)) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "two different seeds produced an identical portal layout");
    }

    @Test
    @DisplayName("roughly one carriage group in oneInGroups is a portal group")
    void frequencyMatchesTheSetting() {
        int half = 150_000;
        int groups = (half * 2) / GROUP_SIZE;
        int portalGroups = acceptedEntries(half).size();

        double expected = (double) groups / ONE_IN;
        // ±15%: the hash is not a perfect uniform generator and the sample, while large, is finite.
        assertTrue(portalGroups > expected * 0.85 && portalGroups < expected * 1.15,
            "expected about " + Math.round(expected) + " portal groups out of " + groups
                + ", got " + portalGroups);
    }

    /**
     * The point of the rarity change was to stop portals arriving on a metronome. A hash that
     * degenerated to "every nth group" would still pass the frequency test above, so check the gaps
     * actually vary.
     */
    @Test
    @DisplayName("portal groups are irregularly spaced, not on a fixed interval")
    void spacingIsIrregular() {
        List<Integer> entries = acceptedEntries(30_000);
        assertTrue(entries.size() > 20, "not enough portal groups to judge spacing: " + entries.size());

        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < entries.size(); i++) {
            gaps.add(entries.get(i) - entries.get(i - 1));
        }
        assertTrue(new HashSet<>(gaps).size() > 3,
            "portal groups fell on a near-fixed interval (" + new HashSet<>(gaps).size() + " distinct gaps)");
    }

    @Test
    @DisplayName("the backwards half of the train gets portals at the same rate")
    void negativeIndicesBehave() {
        int half = 90_000;
        int behind = 0;
        int ahead = 0;
        for (int i : acceptedEntries(half)) {
            if (i < 0) behind++; else ahead++;
        }
        assertTrue(behind > 0 && ahead > 0, "one side of the origin had no portals at all");
        assertTrue(Math.abs(behind - ahead) < Math.max(behind, ahead) * 0.35,
            "portal density differed either side of the origin: " + behind + " behind, " + ahead + " ahead");
    }

    /**
     * {@code /dungeontrain portal rarity 1} is the dev escape hatch — every group becomes a portal
     * group, so every first and last carriage carries a corridor.
     */
    @Test
    @DisplayName("rarity 1 makes every group a portal group")
    void rarityOneAcceptsEveryGroup() {
        for (int i = -200; i <= 200; i++) {
            int slot = Math.floorMod(i, GROUP_SIZE);
            boolean endOfGroup = slot == 0 || slot == GROUP_SIZE - 1;
            assertEquals(endOfGroup,
                PortalCarriageSelection.isPortalCarriage(SEED, i, true, 1, GROUP_SIZE),
                "index " + i);
        }
    }
}
