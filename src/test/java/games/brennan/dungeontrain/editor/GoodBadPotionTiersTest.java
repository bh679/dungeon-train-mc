package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code random_good_potion} / {@code random_bad_potion} tier tables keep the same
 * promises as the shared potion table: no {@code long_*} duration variants (they render with
 * the base potion's name) and no id shared between adjacent tiers, so crossing a 50-carriage
 * boundary always visibly changes the potion. Plus the unscaled pick space is every id once.
 */
final class GoodBadPotionTiersTest {

    private static void assertTierInvariants(List<List<ResourceLocation>> tiers) {
        assertFalse(tiers.isEmpty());
        for (int t = 0; t < tiers.size(); t++) {
            List<ResourceLocation> tier = tiers.get(t);
            assertFalse(tier.isEmpty(), "tier " + t + " is empty");
            for (ResourceLocation id : tier) {
                assertFalse(id.getPath().startsWith("long_"), id + " is a long_* duration variant");
            }
            if (t > 0) {
                Set<ResourceLocation> prev = new HashSet<>(tiers.get(t - 1));
                for (ResourceLocation id : tier) {
                    assertFalse(prev.contains(id), id + " repeats across adjacent tiers " + (t - 1) + "/" + t);
                }
            }
        }
    }

    @Test
    void goodTiers_holdInvariants() {
        assertTierInvariants(ContainerContentsRoller.goodPotionTiersView());
    }

    @Test
    void badTiers_holdInvariants() {
        assertTierInvariants(ContainerContentsRoller.badPotionTiersView());
    }

    @Test
    void flattenedTiers_listEveryIdOnceInOrder() {
        List<List<ResourceLocation>> tiers = ContainerContentsRoller.goodPotionTiersView();
        List<ResourceLocation> flat = ContainerContentsRoller.flattenTiers(tiers);
        int expected = tiers.stream().mapToInt(List::size).sum();
        assertEquals(expected, flat.size());
        assertEquals(expected, new HashSet<>(flat).size(), "duplicate id in flattened good tiers");
        assertEquals(tiers.get(0).get(0), flat.get(0));
        assertTrue(flat.containsAll(tiers.get(tiers.size() - 1)));
    }
}
