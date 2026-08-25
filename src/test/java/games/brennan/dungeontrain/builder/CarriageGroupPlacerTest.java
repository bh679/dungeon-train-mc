package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The box a carriage group occupies, and what may be called one.
 *
 * <p>The size is the one number a group carries about itself — its carriage count lives in its
 * footprint rather than beside it — so capture, placement and the store's gate all have to agree on
 * it. They agree because they all ask here.</p>
 */
final class CarriageGroupPlacerTest {

    private static final CarriageDims DIMS = new CarriageDims(9, 7, 7);

    @Test
    @DisplayName("A group's box is its carriages laid end to end")
    void sizeIsTheRun() {
        assertEquals(new Vec3i(27, 7, 7), CarriageGroupPlacer.sizeOf(DIMS, 3));
        assertEquals(new Vec3i(9, 7, 7), CarriageGroupPlacer.sizeOf(DIMS, 1));
    }

    @Test
    @DisplayName("A nonsensical count is one carriage, not a zero-width box")
    void degenerateCountsAreOneCarriage() {
        // A zero-length box would capture nothing and erase nothing, which reads as a successful
        // save of an empty build.
        assertEquals(new Vec3i(9, 7, 7), CarriageGroupPlacer.sizeOf(DIMS, 0));
        assertEquals(new Vec3i(9, 7, 7), CarriageGroupPlacer.sizeOf(DIMS, -2));
    }

    @Test
    @DisplayName("Group names follow the same shape every template id does")
    void namesAreValidated() {
        assertTrue(CarriageGroup.isValidName("my_run_2"));
        assertFalse(CarriageGroup.isValidName("My Run"));
        assertFalse(CarriageGroup.isValidName(""));
        assertEquals("my_run", CarriageGroup.of("MY_RUN").id(), "ids are canonically lower case");
        assertThrows(IllegalArgumentException.class, () -> CarriageGroup.of("nope!"));
    }
}
