package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalTestSessionTributeStyleTest {

    private static PortalTestSession.Session session(String room, boolean tubes) {
        return new PortalTestSession.Session(Level.OVERWORLD, Vec3.ZERO, 0f, 0f, GameType.CREATIVE, null, room, BlockPos.ZERO, tubes);
    }

    @Test
    void firstPressOfTributeIsTheTubes() {
        assertTrue(PortalTestSession.tributeTubesFor("tribute", null));
        assertTrue(PortalTestSession.tributeTubesFor("tribute", session("beam", false)), "coming from another room counts as a first press");
    }

    @Test
    void repeatPressesAlternate() {
        assertFalse(PortalTestSession.tributeTubesFor("tribute", session("tribute", true)));
        assertTrue(PortalTestSession.tributeTubesFor("tribute", session("tribute", false)));
    }

    @Test
    void otherRoomsNeverTube() {
        assertFalse(PortalTestSession.tributeTubesFor("beam", null));
        assertFalse(PortalTestSession.tributeTubesFor("beam", session("tribute", true)));
    }
}
