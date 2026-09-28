package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalCarriageLayout} — the in-carriage corridor's shape.
 *
 * <p>Mostly here for the walkability invariant, which is the one property of this layout that a
 * player can be physically blocked by and that nothing else checks.</p>
 */
final class PortalCarriageLayoutTest {

    private static PortalCarriageLayout layout(int length) {
        return new PortalCarriageLayout(length, 7, 7);
    }

    /**
     * The bug this pins: a baffle directly behind a door plane blocks the doorway's own column, so
     * the only route from the doorway to the open side is a diagonal step between two solid corners.
     * Minecraft refuses that — the blocks touch at an edge, leaving a zero-width gap a 0.6-wide
     * player cannot pass — which made the corridor's far door unreachable and the pocket room
     * unenterable on foot. A clear column between door and baffle keeps every step orthogonal.
     */
    @Test
    @DisplayName("each baffle leaves a clear column between itself and its door, or the corridor is unwalkable")
    void bafflesLeaveRoomToStepAside() {
        for (int length : new int[] {9, 12, 16, 32}) {
            PortalCarriageLayout l = layout(length);
            assertTrue(l.nearBaffleX() - l.nearDoorX() >= 2,
                "near baffle is flush against its door at length " + length);
            assertTrue(l.farDoorX() - l.farBaffleX() >= 2,
                "far baffle is flush against its door at length " + length);
        }
    }

    @Test
    @DisplayName("the crossing zone lies between the baffles and contains the midpoint")
    void crossingZoneHoldsTheMidpoint() {
        PortalCarriageLayout l = layout(9);
        assertTrue(l.isCrossingZone((int) Math.floor(l.midX())),
            "the midpoint column must be in the lit crossing zone");
        assertTrue(l.isCrossingZone(l.nearBaffleX() + 1));
        assertTrue(l.isCrossingZone(l.farBaffleX() - 1));

        // The baffles themselves and everything outside them are not the crossing zone.
        assertTrue(!l.isCrossingZone(l.nearBaffleX()));
        assertTrue(!l.isCrossingZone(l.farBaffleX()));
        assertTrue(!l.isCrossingZone(l.nearDoorX()));
        assertTrue(!l.isCrossingZone(l.farDoorX()));
    }

    /**
     * The doorway sits on the walkway centre, and the baffles block up to and including that column
     * — so no straight run down the doorway's own line survives to the far end.
     */
    @Test
    @DisplayName("both baffles interrupt the doorway's column, so neither door is visible from the crossing zone")
    void bafflesBreakTheSightLine() {
        PortalCarriageLayout l = layout(9);
        assertEquals(l.doorZ(), l.baffleZ());
        assertTrue(l.detourZ() > l.baffleZ(), "the detour must be on the open side of the baffle");
        assertTrue(l.detourZ() <= l.interiorMaxZ(), "the detour must stay inside the corridor");
    }

    @Test
    @DisplayName("copyForLocalX splits at the midpoint, with the tie going to the near half")
    void copySplit() {
        PortalCarriageLayout l = layout(9);
        assertEquals(PortalGeometry.COPY_NEAR, l.copyForLocalX(l.midX() - 0.1));
        assertEquals(PortalGeometry.COPY_NEAR, l.copyForLocalX(l.midX()));
        assertEquals(PortalGeometry.COPY_FAR, l.copyForLocalX(l.midX() + 0.1));
    }

    /**
     * The puppet pass asks the level for every entity in a corridor, which needs the volume as a box
     * rather than as a predicate. The search box is the containment rule everywhere except the two
     * end-face slabs, where it is deliberately the larger of the two: over-collecting costs a
     * candidate that {@code PortalPuppets.describe} then drops, while a smaller box would lose
     * entities standing at the walls.
     */
    @Test
    @DisplayName("localBounds and insideCorridor describe the same volume, face by face")
    void boundsAgreeWithContainment() {
        double nudge = 1e-6;
        for (int length : new int[] {7, 9, 16}) {
            PortalCarriageLayout l = layout(length);
            PortalCarriageLayout.Bounds b = l.localBounds();

            // Dead centre is in, every face is in, and a hair outside each face is out.
            double midY = (b.minY() + b.maxY()) / 2, midZ = (b.minZ() + b.maxZ()) / 2;
            double midX = (b.minX() + b.maxX()) / 2;
            assertTrue(l.insideCorridor(midX, midY, midZ), "centre at length " + length);

            // The X faces lie past the end planes, so they are inside only in the doorway column.
            double doorY = l.floorY() + 1, doorLineZ = l.doorZ() + 0.5;
            assertTrue(l.insideCorridor(b.minX(), doorY, doorLineZ));
            assertTrue(l.insideCorridor(b.maxX(), doorY, doorLineZ));

            assertTrue(l.insideCorridor(midX, b.minY(), midZ));
            assertTrue(l.insideCorridor(midX, b.maxY(), midZ));
            assertTrue(l.insideCorridor(midX, midY, b.minZ()));
            assertTrue(l.insideCorridor(midX, midY, b.maxZ()));

            assertTrue(!l.insideCorridor(b.minX() - nudge, doorY, doorLineZ));
            assertTrue(!l.insideCorridor(b.maxX() + nudge, doorY, doorLineZ));
            assertTrue(!l.insideCorridor(midX, b.minY() - nudge, midZ));
            assertTrue(!l.insideCorridor(midX, b.maxY() + nudge, midZ));
            assertTrue(!l.insideCorridor(midX, midY, b.minZ() - nudge));
            assertTrue(!l.insideCorridor(midX, midY, b.maxZ() + nudge));
        }
    }

    /**
     * The clip this pins: a player standing <b>outside</b> the carriage, pressed against the solid
     * part of an end wall, sat inside the padded box at local X {@code -0.3} — so the facing rule
     * teleported them the moment they looked the wrong way, and they crossed through the shell
     * instead of through the door. Only the doorway column may reach past an end plane.
     */
    @Test
    @DisplayName("past an end plane, only the doorway column is inside the corridor")
    void outsideAnEndWallIsNotInside() {
        for (int length : new int[] {7, 9, 16}) {
            PortalCarriageLayout l = layout(length);
            double standingY = l.floorY() + 1;
            double onTheDoorLine = l.doorZ() + 0.5;
            double offTheDoorLine = l.doorZ() - 0.5;   // one block over: solid end plane

            for (double x : new double[] {-0.3, length + 0.3}) {
                assertTrue(!l.insideCorridor(x, standingY, offTheDoorLine),
                    "outside the end wall at x=" + x + ", length " + length);
                assertTrue(l.insideCorridor(x, standingY, onTheDoorLine),
                    "in the doorway at x=" + x + ", length " + length);
            }

            // On the door line but at roof height is still outside — that is the ceiling, not a door.
            assertTrue(!l.insideCorridor(-0.3, l.ceilingY(), onTheDoorLine));

            // And nothing about the corridor's own footprint changed: just inside each end plane,
            // off the door line, is still inside.
            assertTrue(l.insideCorridor(0.3, standingY, offTheDoorLine));
            assertTrue(l.insideCorridor(length - 0.3, standingY, offTheDoorLine));
        }
    }
}
