package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.builder.BuilderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which modes build on a platform, and which build in open space. */
final class BuilderEnvironmentTest {

    @Test
    @DisplayName("Only Train Dimensions builds in void")
    void onlyTrainDimensionsIsVoid() {
        // A portal room *is* the whole scene and stands in the space behind a tunnel portal.
        // Everything else is a train being looked at from somewhere, and needs a floor.
        assertEquals(BuilderEnvironment.VOID,
                BuilderEnvironment.forMode(BuilderMode.TRAIN_DIMENSIONS));
        for (BuilderMode mode : BuilderMode.values()) {
            if (mode == BuilderMode.TRAIN_DIMENSIONS) {
                continue;
            }
            assertEquals(BuilderEnvironment.FLATGRASS, BuilderEnvironment.forMode(mode),
                    mode.id() + " needs somewhere to stand");
        }
    }

    @Test
    @DisplayName("An unknown mode gets a floor rather than open void")
    void nullDegradesToAPlatform() {
        // Dropping somebody through the bottom of the world is the worse failure of the two.
        assertEquals(BuilderEnvironment.FLATGRASS, BuilderEnvironment.forMode(null));
    }

    @Test
    @DisplayName("Only the flatgrass environment lays a platform")
    void onlyFlatgrassHasAPlatform() {
        assertTrue(BuilderEnvironment.FLATGRASS.hasPlatform());
        assertFalse(BuilderEnvironment.VOID.hasPlatform());
    }
}
