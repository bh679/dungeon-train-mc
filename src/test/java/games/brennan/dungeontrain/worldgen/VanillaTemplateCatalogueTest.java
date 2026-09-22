package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for {@link VanillaTemplateCatalogue}'s exclusion filter and pick. Building the catalogue
 * needs a live server (it walks the resource manager) and is covered in-game; the pick is just the
 * seed-stable {@link StacksBand#pickIndex} over a list.
 */
final class VanillaTemplateCatalogueTest {

    private static final long SEED = 42L;

    @Test
    @DisplayName("excluded prefixes drop trial chambers and keep everything else")
    void exclusions() {
        assertTrue(VanillaTemplateCatalogue.excluded("trial_chambers/corridor/end_1"));
        assertFalse(VanillaTemplateCatalogue.excluded("village/plains/houses/plains_armorer_house_1"));
        assertFalse(VanillaTemplateCatalogue.excluded("bastion/units/center_pieces/center_0"));
        assertFalse(VanillaTemplateCatalogue.excluded("igloo/top"));
    }

    @Test
    @DisplayName("pick is deterministic, in range, null on an empty catalogue")
    void pick() {
        List<ResourceLocation> ids = List.of(
                ResourceLocation.withDefaultNamespace("a"),
                ResourceLocation.withDefaultNamespace("b"),
                ResourceLocation.withDefaultNamespace("c"));
        for (int cx = -20; cx < 20; cx++) {
            ResourceLocation p = VanillaTemplateCatalogue.pick(ids, SEED, cx, -cx, 0);
            assertEquals(p, VanillaTemplateCatalogue.pick(ids, SEED, cx, -cx, 0));
            assertTrue(ids.contains(p));
        }
        assertNull(VanillaTemplateCatalogue.pick(List.of(), SEED, 1, 1, 0));
    }
}
