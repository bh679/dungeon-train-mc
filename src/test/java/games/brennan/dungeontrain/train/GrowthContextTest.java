package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coverage for {@link GrowthContext}'s scoping. */
class GrowthContextTest {

    @Test
    @DisplayName("active only while the growth runs")
    void activeOnlyDuringRun() {
        assertFalse(GrowthContext.isActive());
        assertTrue(GrowthContext.run(GrowthContext::isActive));
        assertFalse(GrowthContext.isActive());
    }

    @Test
    @DisplayName("a nested growth does not end the outer one")
    void nestedKeepsOuterActive() {
        boolean outerStillActive = GrowthContext.run(() -> {
            GrowthContext.run(() -> true);
            return GrowthContext.isActive();
        });
        assertTrue(outerStillActive);
        assertFalse(GrowthContext.isActive());
    }

    @Test
    @DisplayName("a growth that throws still clears the context")
    void throwingGrowthClears() {
        assertThrows(IllegalStateException.class, () -> GrowthContext.run(() -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(GrowthContext.isActive());
    }
}
