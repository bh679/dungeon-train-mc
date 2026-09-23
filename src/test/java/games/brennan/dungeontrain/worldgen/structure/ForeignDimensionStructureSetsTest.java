package games.brennan.dungeontrain.worldgen.structure;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for {@link ForeignDimensionStructureSets} — which third-party sets the overworld generator drops. */
final class ForeignDimensionStructureSetsTest {

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    @Test
    @DisplayName("a set made only of BetterEnd structures is blocked on the overworld")
    void allBetterEndBlocked() {
        assertTrue(ForeignDimensionStructureSets.blockedOnOverworld(List.of(id("betterend", "eternal_portal"))));
        assertTrue(ForeignDimensionStructureSets.blockedOnOverworld(
                List.of(id("betterend", "end_lake"), id("betterend", "end_village"))));
    }

    @Test
    @DisplayName("a mixed set is left to vanilla's own biome filter")
    void mixedSetNotBlocked() {
        assertFalse(ForeignDimensionStructureSets.blockedOnOverworld(
                List.of(id("betterend", "eternal_portal"), id("minecraft", "village_plains"))));
    }

    @Test
    @DisplayName("vanilla, DT and BetterNether sets are never blocked")
    void otherNamespacesNotBlocked() {
        assertFalse(ForeignDimensionStructureSets.blockedOnOverworld(List.of(id("minecraft", "fortress"))));
        assertFalse(ForeignDimensionStructureSets.blockedOnOverworld(List.of(id("dungeontrain", "end_city"))));
        assertFalse(ForeignDimensionStructureSets.blockedOnOverworld(List.of(id("betternether", "nether_city"))));
    }

    @Test
    @DisplayName("an empty or null list is never blocked")
    void emptyNotBlocked() {
        assertFalse(ForeignDimensionStructureSets.blockedOnOverworld(List.of()));
        assertFalse(ForeignDimensionStructureSets.blockedOnOverworld(null));
    }
}
