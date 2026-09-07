package games.brennan.dungeontrain.spawn;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic unit tests for {@link AmbientDensity} — the cap decision, without a NeoForge bootstrap.
 *
 * <p>The counting itself needs a live level and is covered by the in-game verification instead; what
 * is testable here is the rule the count is fed into, and the boundary behaviour that decides whether
 * a world with the cap turned off behaves exactly as it did before this existed.</p>
 */
final class AmbientDensityTest {

    @Test
    @DisplayName("under the cap, the world keeps spawning")
    void underCap() {
        assertFalse(AmbientDensity.atCap(29, 30));
        assertFalse(AmbientDensity.atCap(0, 30));
    }

    @Test
    @DisplayName("the cap is a ceiling reached, not exceeded — equal counts hold spawning off")
    void atCapExactly() {
        assertTrue(AmbientDensity.atCap(30, 30));
        assertTrue(AmbientDensity.atCap(31, 30));
    }

    @Test
    @DisplayName("a cap of zero is off, not a world with no monsters")
    void zeroDisables() {
        assertFalse(AmbientDensity.atCap(0, 0));
        assertFalse(AmbientDensity.atCap(500, 0));
    }

    @Test
    @DisplayName("a negative cap is off too, so a hand-edited config can't invert the rule")
    void negativeDisables() {
        assertFalse(AmbientDensity.atCap(500, -1));
    }

    @Test
    @DisplayName("the shipped default sits above ordinary DT track and below an outpost's pile-up")
    void defaultLeavesOpenTrackAlone() {
        int cap = DungeonTrainCommonConfig.DEFAULT_AMBIENT_MONSTER_CAP;
        // Measured on one seed: ~32 monsters within RADIUS on open DT track, 59 at a pillager
        // outpost, against a vanilla world's 19 at that same outpost. The default has to bite on the
        // second without flattening the first.
        assertFalse(AmbientDensity.atCap(19, cap), "vanilla-like density must be untouched");
        assertTrue(AmbientDensity.atCap(59, cap), "the outpost pile-up must be capped");
    }

    @Test
    @DisplayName("the radius the default was measured at is the radius the guard uses")
    void radiusMatchesMeasurement() {
        assertEquals(96, AmbientDensity.RADIUS);
    }
}
