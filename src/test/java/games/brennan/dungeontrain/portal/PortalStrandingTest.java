package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalStrandingTest {

    // Pair -105's dead room (lane -54) and pair -138's working one a lane above, same X — the
    // 2026-10-21 layout, with the old eight-row headroom so the dead box still reaches the live room.
    private static final AABB DEAD = new AABB(110, -55, -8, 142, -39, 15);
    private static final AABB LIVE = new AABB(106, -47, -8, 138, -31, 15);

    private static final Function<Vec3, Vec3> SELF = v -> v;

    @Test
    @DisplayName("A player in the dead room alone is stranded")
    void deadRoomOnly_isStranded() {
        Vec3 inDeadRoom = new Vec3(139.5, -53, 3.5);
        assertEquals(List.of(inDeadRoom),
            PortalStranding.strandedIn(List.of(inDeadRoom), SELF, DEAD, List.of(LIVE)));
    }

    @Test
    @DisplayName("A player inside a live pair's room is that pair's, even inside a dead box too")
    void insideLiveRoom_isNotStranded() {
        // Where AshSeven was carried out from, at the mouth of -138's exit corridor.
        Vec3 inLiveRoom = new Vec3(122.6, -45, -1.6);
        assertTrue(PortalStranding.strandedIn(List.of(inLiveRoom), SELF, DEAD, List.of(LIVE)).isEmpty());
    }

    @Test
    @DisplayName("A player outside the dead box is never stranded by it")
    void outsideDeadBox_isNotStranded() {
        Vec3 onTheTrain = new Vec3(126.2, 79.2, 3.5);
        assertTrue(PortalStranding.strandedIn(List.of(onTheTrain), SELF, DEAD, List.of()).isEmpty());
    }

    @Test
    @DisplayName("With no live pairs, everyone in the dead box is stranded, in order")
    void noLivePairs_keepsOrder() {
        Vec3 a = new Vec3(120, -50, 0);
        Vec3 b = new Vec3(130, -50, 0);
        assertEquals(List.of(a, b),
            PortalStranding.strandedIn(List.of(a, new Vec3(0, 0, 0), b), SELF, DEAD, List.of()));
    }
}
