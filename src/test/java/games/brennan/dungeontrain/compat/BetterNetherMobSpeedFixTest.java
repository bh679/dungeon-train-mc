package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link BetterNetherMobSpeedFix} targets the real BetterNether id at vanilla skeleton speed. */
final class BetterNetherMobSpeedFixTest {

    @Test
    @DisplayName("targets BetterNether's jungle skeleton")
    void targetsJungleSkeleton() {
        assertEquals("betternether:jungle_skeleton", BetterNetherMobSpeedFix.JUNGLE_SKELETON_ID.toString());
    }

    @Test
    @DisplayName("uses vanilla skeleton speed, not the 0.7 attribute default")
    void usesSkeletonSpeed() {
        assertEquals(0.25, BetterNetherMobSpeedFix.SKELETON_MOVEMENT_SPEED);
    }
}
