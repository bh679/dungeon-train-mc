package games.brennan.dungeontrain.portal;

import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRoomMobFitTest {

    private static final Vec3i ROOM = new Vec3i(16, 32, 16);

    @Test
    void straightUpComesFirst() {
        List<Vec3i> c = PortalRoomMobFit.candidates(8, 10, 8, ROOM, 3);
        assertEquals(new Vec3i(0, 1, 0), c.get(0));
        assertEquals(new Vec3i(0, 2, 0), c.get(1));
    }

    @Test
    void downFollowsUpBeforeAnySidewaysStep() {
        List<Vec3i> c = PortalRoomMobFit.candidates(8, 10, 8, ROOM, 3);
        int firstDown = c.indexOf(new Vec3i(0, -1, 0));
        int firstSide = -1;
        for (int i = 0; i < c.size(); i++) {
            if (c.get(i).getX() != 0 || c.get(i).getZ() != 0) {
                firstSide = i;
                break;
            }
        }
        assertTrue(firstDown > 0 && firstDown < firstSide);
    }

    @Test
    void everyCandidateStaysInsideTheRoomBox() {
        for (int[] at : new int[][] {{0, 1, 0}, {15, 30, 15}, {0, 15, 15}, {7, 1, 7}}) {
            for (Vec3i o : PortalRoomMobFit.candidates(at[0], at[1], at[2], ROOM, 3)) {
                int x = at[0] + o.getX();
                int y = at[1] + o.getY();
                int z = at[2] + o.getZ();
                assertTrue(x >= 0 && x < 16 && z >= 0 && z < 16, "x/z out of room: " + o);
                assertTrue(y >= 1 && y <= 30, "y on floor or roof row: " + o);
            }
        }
    }

    @Test
    void noOffsetIsTriedTwiceAndRingsStopAtTheLimit() {
        List<Vec3i> c = PortalRoomMobFit.candidates(8, 10, 8, ROOM, 2);
        Set<Vec3i> seen = new HashSet<>(c);
        assertEquals(c.size(), seen.size());
        assertFalse(c.contains(new Vec3i(0, 0, 0)));
        for (Vec3i o : c) {
            assertTrue(Math.abs(o.getX()) <= 2 && Math.abs(o.getZ()) <= 2);
        }
    }

    @Test
    void nearerRingsComeBeforeFartherOnes() {
        List<Vec3i> c = PortalRoomMobFit.candidates(8, 10, 8, ROOM, 3);
        int lastRing = 0;
        for (Vec3i o : c) {
            int ring = Math.max(Math.abs(o.getX()), Math.abs(o.getZ()));
            assertTrue(ring >= lastRing, "ring went backwards at " + o);
            lastRing = ring;
        }
    }
}
