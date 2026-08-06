package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math tests for {@link PortalCarriageRole} — the alternation that pairs portal carriages into
 * an entry and an exit sharing one pocket room.
 */
final class PortalCarriageRoleTest {

    private static final int EVERY = 4;

    @Test
    @DisplayName("roles alternate along the train, so consecutive portal carriages pair up")
    void rolesAlternate() {
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(0, EVERY));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(4, EVERY));
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(8, EVERY));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(12, EVERY));
    }

    @Test
    @DisplayName("a pair's two carriages point at each other")
    void partnersAreMutual() {
        assertEquals(4, PortalCarriageRole.partnerIndex(0, EVERY));   // entry → its exit
        assertEquals(0, PortalCarriageRole.partnerIndex(4, EVERY));   // exit → its entry
        assertEquals(12, PortalCarriageRole.partnerIndex(8, EVERY));
    }

    @Test
    @DisplayName("both carriages of a pair resolve to the same entry index — the key their structure is stored under")
    void pairKeyIsShared() {
        assertEquals(0, PortalCarriageRole.entryIndexOf(0, EVERY));
        assertEquals(0, PortalCarriageRole.entryIndexOf(4, EVERY));
        assertEquals(8, PortalCarriageRole.entryIndexOf(8, EVERY));
        assertEquals(8, PortalCarriageRole.entryIndexOf(12, EVERY));
    }

    /**
     * Carriage indices go negative when the train extends backwards. Integer division truncates
     * toward zero, which would repeat a role either side of the origin — {@code -4/4} and
     * {@code 0/4} both give 0 — and break the pairing exactly there.
     */
    @Test
    @DisplayName("alternation continues cleanly through negative indices")
    void negativeIndicesKeepAlternating() {
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(-4, EVERY));
        assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(-8, EVERY));
        assertEquals(PortalCarriageRole.EXIT, PortalCarriageRole.roleFor(-12, EVERY));

        // ...and a negative pair still shares one key.
        assertEquals(-8, PortalCarriageRole.entryIndexOf(-8, EVERY));
        assertEquals(-8, PortalCarriageRole.entryIndexOf(-4, EVERY));
    }

    @Test
    @DisplayName("no carriage index is ever both roles, at any spacing")
    void rolesArePartitioned() {
        for (int every : new int[] {3, 4, 7}) {
            for (int i = -40; i <= 40; i++) {
                PortalCarriageRole role = PortalCarriageRole.roleFor(i, every);
                int entry = PortalCarriageRole.entryIndexOf(i, every);
                // Whichever role a carriage has, its pair key is an ENTRY carriage.
                assertEquals(PortalCarriageRole.ENTRY, PortalCarriageRole.roleFor(entry, every),
                    "pair key " + entry + " for index " + i + " at every=" + every + " was not an ENTRY");
                assertEquals(role == PortalCarriageRole.ENTRY ? i : i - every, entry);
            }
        }
    }
}
