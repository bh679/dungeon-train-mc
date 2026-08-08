package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math tests for {@link PortalCarriageRole} — which carriage of a portal group is the way in and
 * which is the way out, and how the two find each other.
 */
final class PortalCarriageRoleTest {

    private static final int GROUP_SIZE = 3;

    @Test
    @DisplayName("a group's first carriage is the entry and its last is the exit")
    void firstAndLastOfTheGroup() {
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(0, GROUP_SIZE));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(2, GROUP_SIZE));
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(3, GROUP_SIZE));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(5, GROUP_SIZE));
    }

    @Test
    @DisplayName("a pair spans its group, first slot to last")
    void spanIsTheGroup() {
        assertEquals(2, PortalCarriageRole.span(3));
        assertEquals(1, PortalCarriageRole.span(2));
        assertEquals(5, PortalCarriageRole.span(6));
    }

    @Test
    @DisplayName("a pair's two carriages point at each other")
    void partnersAreMutual() {
        assertEquals(2, PortalCarriageRole.partnerIndex(0, GROUP_SIZE));   // entry → its exit
        assertEquals(0, PortalCarriageRole.partnerIndex(2, GROUP_SIZE));   // exit → its entry
        assertEquals(5, PortalCarriageRole.partnerIndex(3, GROUP_SIZE));
        assertEquals(3, PortalCarriageRole.partnerIndex(5, GROUP_SIZE));
    }

    @Test
    @DisplayName("both carriages of a pair resolve to the same entry index — their group's anchor")
    void pairKeyIsTheGroupAnchor() {
        assertEquals(0, PortalCarriageRole.entryIndexOf(0, GROUP_SIZE));
        assertEquals(0, PortalCarriageRole.entryIndexOf(2, GROUP_SIZE));
        assertEquals(3, PortalCarriageRole.entryIndexOf(3, GROUP_SIZE));
        assertEquals(3, PortalCarriageRole.entryIndexOf(5, GROUP_SIZE));
    }

    /**
     * Carriage indices go negative when the train extends backwards. {@code %} and {@code /} keep the
     * sign of the left operand, which would put slot 0 in the wrong place and make two groups either
     * side of the origin share an anchor — {@code -3/3} and {@code 0/3} both give 0.
     */
    @Test
    @DisplayName("groups either side of the origin stay distinct")
    void negativeIndicesKeepTheirGroup() {
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(-3, GROUP_SIZE));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(-1, GROUP_SIZE));

        assertEquals(-3, PortalCarriageRole.entryIndexOf(-3, GROUP_SIZE));
        assertEquals(-3, PortalCarriageRole.entryIndexOf(-1, GROUP_SIZE));
        assertEquals(-6, PortalCarriageRole.entryIndexOf(-6, GROUP_SIZE));
        assertEquals(-6, PortalCarriageRole.entryIndexOf(-4, GROUP_SIZE));
    }

    @Test
    @DisplayName("a pair's key is always its own entry carriage, at any group size")
    void keyIsAlwaysAnEntry() {
        for (int groupSize : new int[] {2, 3, 4, 7}) {
            for (int i = -40; i <= 40; i++) {
                int entry = PortalCarriageRole.entryIndexOf(i, groupSize);
                assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(entry, groupSize),
                    "pair key " + entry + " for index " + i + " at groupSize=" + groupSize
                        + " was not an ENTRY");
            }
        }
    }

    /**
     * The invariant the group-scoped design exists for: an entry and its exit must be in the same
     * group, because a group is one Sable sub-level and the pair's room is anchored to the entry.
     */
    @Test
    @DisplayName("an entry and its exit are always in the same group")
    void pairsNeverSpanAGroup() {
        for (int groupSize : new int[] {2, 3, 4, 7}) {
            for (int i = -40; i <= 40; i++) {
                int entry = PortalCarriageRole.entryIndexOf(i, groupSize);
                int exit = PortalCarriageRole.partnerIndex(entry, groupSize);
                assertEquals(entry, PortalCarriageRole.entryIndexOf(exit, groupSize),
                    "exit " + exit + " left the group anchored at " + entry
                        + " (groupSize=" + groupSize + ")");
            }
        }
    }
}
