package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pull on an orb towards a mirrored player is vanilla's pull, no stronger and no wider.
 *
 * <p>{@code ExperienceOrb.tick}: within eight blocks, {@code d = 1 - dist/8}, add
 * {@code dir * d² * 0.1}. An orb four blocks off therefore gains {@code 0.025} a tick, one right at
 * the edge nothing, and one on top of the target nothing either (no direction to go). Pinned so a
 * mirrored orb cannot be told from a real one by how it moves.</p>
 */
final class PortalOrbPullTest {

    @Test
    @DisplayName("Four blocks away: 0.025 a tick, straight at the target")
    void midRange() {
        Vec3 v = PortalOrbPull.impulse(new Vec3(0, 0, 0), new Vec3(4, 0, 0));
        assertEquals(0.025, v.x, 1e-12);
        assertEquals(0, v.y, 1e-12);
        assertEquals(0, v.z, 1e-12);
    }

    @Test
    @DisplayName("Adjacent: nearly the full 0.1, along the offset")
    void closeRange() {
        Vec3 v = PortalOrbPull.impulse(new Vec3(0, 0, 0), new Vec3(0, 0.6, 0.8));
        double d = 1.0 - 1.0 / 8.0;
        assertEquals(d * d * 0.1 * 0.6, v.y, 1e-12);
        assertEquals(d * d * 0.1 * 0.8, v.z, 1e-12);
    }

    @Test
    @DisplayName("At or past eight blocks, and at zero distance, no pull at all")
    void outOfRangeOrCoincident() {
        assertEquals(Vec3.ZERO, PortalOrbPull.impulse(new Vec3(0, 0, 0), new Vec3(8, 0, 0)));
        assertEquals(Vec3.ZERO, PortalOrbPull.impulse(new Vec3(0, 0, 0), new Vec3(0, 12, 0)));
        assertEquals(Vec3.ZERO, PortalOrbPull.impulse(new Vec3(1, 2, 3), new Vec3(1, 2, 3)));
    }

    @Test
    @DisplayName("The pull weakens with distance, never the other way")
    void monotone() {
        double prev = Double.MAX_VALUE;
        for (double dist = 0.5; dist < 8; dist += 0.5) {
            double mag = PortalOrbPull.impulse(Vec3.ZERO, new Vec3(dist, 0, 0)).length();
            assertTrue(mag < prev, "pull grew between " + (dist - 0.5) + " and " + dist);
            prev = mag;
        }
    }
}
