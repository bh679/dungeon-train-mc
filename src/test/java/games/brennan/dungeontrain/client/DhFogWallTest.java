package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DhFogWall}: opaque exactly at the cap, never looser than the player's own fog. */
final class DhFogWallTest {

    private static final double RADIUS = 256 * 16.0;          // DH at 256 chunks
    /** DH's shipped far-fog defaults: start 40%, end 100%, not fully opaque. */
    private static final float START = 0.4f;
    private static final float END = 1.0f;
    private static final float MAX = 0.5f;

    @Test
    @DisplayName("a cap at or past DH's radius needs no wall")
    void noWallPastRadius() {
        assertNull(DhFogWall.wall(RADIUS, RADIUS, START, END, MAX));
        assertNull(DhFogWall.wall(RADIUS * 3, RADIUS, START, END, MAX));
    }

    @Test
    @DisplayName("the wall is opaque exactly at the cap and thickens over the last 192 blocks")
    void wallAtCap() {
        DhFogWall.Fog fog = DhFogWall.wall(2048, RADIUS, 0.9f, END, MAX);
        assertNotNull(fog);
        assertEquals(2048 / RADIUS, fog.endPercent(), 1e-6);
        assertEquals((2048 - 192) / RADIUS, fog.startPercent(), 1e-6);
    }

    @Test
    @DisplayName("the player's own earlier fog start is kept")
    void keepsEarlierUserStart() {
        DhFogWall.Fog fog = DhFogWall.wall(3000, RADIUS, 0.1f, END, MAX);
        assertNotNull(fog);
        assertEquals(0.1f, fog.startPercent(), 1e-6);
    }

    @Test
    @DisplayName("a short cap fades over half its length, and a zero cap still has start below end")
    void shortCaps() {
        DhFogWall.Fog fog = DhFogWall.wall(100, RADIUS, START, END, MAX);
        assertNotNull(fog);
        assertEquals(50 / RADIUS, fog.startPercent(), 1e-6);
        DhFogWall.Fog zero = DhFogWall.wall(0, RADIUS, START, END, MAX);
        assertNotNull(zero);
        assertTrue(zero.startPercent() < zero.endPercent());
        assertTrue(zero.endPercent() > 0f);
    }

    @Test
    @DisplayName("a player whose own fog is already opaque before the cap is left alone")
    void userFogAlreadyStricter() {
        assertNull(DhFogWall.wall(3000, RADIUS, 0.2f, 0.5f, 1.0f));   // opaque at 2048 < 3000
        assertNotNull(DhFogWall.wall(3000, RADIUS, 0.2f, 0.5f, 0.8f)); // not opaque: wall still needed
    }
}
