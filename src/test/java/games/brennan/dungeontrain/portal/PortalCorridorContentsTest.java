package games.brennan.dungeontrain.portal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What makes a portal's two corridors — and their two twins — agree on one contents sub-variant.
 *
 * <p>A corridor is stamped twice by two unrelated calls: as a carriage when the train spawns, and as
 * a static twin from the portal tick handler. They must land on the same member or the crossing shows
 * a seam. Nothing is passed between the two sites; they agree because both derive the same
 * {@code pairKey} from the carriage index, and the draw is seeded off that. These tests pin both
 * halves of that argument.</p>
 */
class PortalCorridorContentsTest {

    @Test
    @DisplayName("Every carriage of a portal group resolves to one pair key — entry, middle and exit alike")
    void everyCarriageInAGroupSharesThePairKey() {
        for (int groupSize : new int[]{3, 4, 6, 8, 12}) {
            for (int anchor = 0; anchor < 40; anchor += groupSize) {
                int expected = PortalCarriageRole.entryIndexOf(anchor, groupSize);
                for (int slot = 0; slot < groupSize; slot++) {
                    assertEquals(expected,
                        PortalCarriageRole.entryIndexOf(anchor + slot, groupSize),
                        "groupSize " + groupSize + ", carriage " + (anchor + slot)
                            + ": every carriage of a group must name the same portal, or a corridor "
                            + "and its twin can draw different contents");
                }
            }
        }
    }

    @Test
    @DisplayName("A pair key seeds the same draw every time — the carriage and its twin ask once each")
    void seedIsStableForAPair() {
        long worldSeed = -8_123_456_789L;
        for (int pairKey : new int[]{0, 3, 6, 99, -12}) {
            assertEquals(PortalCorridorContents.seedFor(worldSeed, pairKey),
                PortalCorridorContents.seedFor(worldSeed, pairKey),
                "pair " + pairKey + " must seed identically on every call");
        }
    }

    @Test
    @DisplayName("Neighbouring portals separate — consecutive pair keys do not walk in step")
    void neighbouringPairsGetDifferentSeeds() {
        long worldSeed = 1234L;
        Set<Long> seen = new HashSet<>();
        // Pair keys advance by the group size, so step by a realistic stride rather than by 1.
        for (int pairKey = 0; pairKey < 60; pairKey += 3) {
            assertTrue(seen.add(PortalCorridorContents.seedFor(worldSeed, pairKey)),
                "pair " + pairKey + " collided with an earlier one — every portal on the train "
                    + "would draw the same corridor");
        }
    }

    @Test
    @DisplayName("Two worlds do not share a portal's corridor")
    void differentWorldsGetDifferentSeeds() {
        assertNotEquals(PortalCorridorContents.seedFor(1L, 6),
            PortalCorridorContents.seedFor(2L, 6));
    }

    @Test
    @DisplayName("The shipped portal group really does declare sub-variants to draw from")
    void shippedPortalGroupHasMembers() throws Exception {
        // The bug this fixes was silent: the group was parsed and indexed, and simply never drawn
        // from. If the data file ever loses its members the roll goes quiet again in exactly the
        // same way, with every corridor falling back to `portal` and nothing in the log to say so.
        try (InputStream in = PortalCorridorContentsTest.class
                .getResourceAsStream("/data/dungeontrain/contents/portal.group.json")) {
            assertNotNull(in, "contents/portal.group.json must ship — the corridor has no "
                + "sub-variants without it");
            JsonObject json = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            CarriageContentsGroup group = CarriageContentsGroup.fromJson(json);

            assertFalse(group.isEmpty(), "the portal group must declare at least one member");
            assertTrue(group.selfWeight() > 0,
                "`portal` itself must stay drawable, or its own template stops appearing");
            Set<String> ids = new HashSet<>();
            for (CarriageContentsGroup.Member m : group.members()) {
                assertTrue(m.weight() > 0, "member '" + m.id() + "' at weight 0 can never be drawn");
                assertTrue(ids.add(m.id()), "duplicate member '" + m.id() + "'");
            }
        }
    }
}
