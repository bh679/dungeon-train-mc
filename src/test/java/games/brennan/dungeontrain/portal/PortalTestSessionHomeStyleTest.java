package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalTestSessionHomeStyleTest {

    private static PortalTestSession.Session session(String room, boolean tubes) {
        return new PortalTestSession.Session(Level.OVERWORLD, Vec3.ZERO, 0f, 0f, GameType.CREATIVE, null, room, BlockPos.ZERO, tubes);
    }

    @Test
    void firstPressOfHomeIsTheTubes() {
        assertTrue(PortalTestSession.homeTubesFor("home", null));
        assertTrue(PortalTestSession.homeTubesFor("home", session("beam", false)), "coming from another room counts as a first press");
    }

    @Test
    void repeatPressesAlternate() {
        assertFalse(PortalTestSession.homeTubesFor("home", session("home", true)));
        assertTrue(PortalTestSession.homeTubesFor("home", session("home", false)));
    }

    @Test
    void otherRoomsNeverTube() {
        assertFalse(PortalTestSession.homeTubesFor("beam", null));
        assertFalse(PortalTestSession.homeTubesFor("beam", session("home", true)));
    }
}
