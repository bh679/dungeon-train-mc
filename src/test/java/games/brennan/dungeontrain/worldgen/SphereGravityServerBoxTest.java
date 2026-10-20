package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The per-player entity query box only reaches as high and low as a sphere can. */
final class SphereGravityServerBoxTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("X/Z grow by the full half-size; Y is clamped to the sphere layer plus the half-size")
    void clampsY() {
        AABB player = new AABB(100, 100, 200, 100.6, 101.8, 200.6);
        AABB box = SphereGravityServer.queryBox(player, 60, 120, 140);
        assertNotNull(box);
        assertEquals(40, box.minX, EPS);
        assertEquals(160.6, box.maxX, EPS);
        assertEquals(140, box.minZ, EPS);
        assertEquals(260.6, box.maxZ, EPS);
        assertEquals(60, box.minY, EPS, "layer floor 120 - 60 is higher than the player's 100 - 60");
        assertEquals(161.8, box.maxY, EPS, "player's 101.8 + 60 is lower than the layer roof 140 + 60");
    }

    @Test
    @DisplayName("a player inside the layer keeps the plain inflated box")
    void insideLayerUnchanged() {
        AABB player = new AABB(0, 130, 0, 1, 132, 1);
        AABB box = SphereGravityServer.queryBox(player, 20, 100, 160);
        assertEquals(player.inflate(20), box);
    }

    @Test
    @DisplayName("a player whose reach misses the layer gets no query")
    void outsideLayerSkipped() {
        assertNull(SphereGravityServer.queryBox(new AABB(0, 0, 0, 1, 2, 1), 20, 100, 160), "far below");
        assertNull(SphereGravityServer.queryBox(new AABB(0, 300, 0, 1, 302, 1), 20, 100, 160), "far above");
        assertNotNull(SphereGravityServer.queryBox(new AABB(0, 60, 0, 1, 62, 1), 20, 100, 160),
                "62 + 20 reaches the floor 100 - 20: still queried");
    }
}
