package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VanillaBackport's biome keys, and the one TerraBlender region DT keeps out of the layout. */
final class BackportBiomesTest {

    @Test
    @DisplayName("only VanillaBackport's own overworld region is vetoed")
    void onlyTheBackportRegionIsVetoed() {
        assertTrue(BackportBiomes.isVetoedRegion(ResourceLocation.fromNamespaceAndPath("vanillabackport", "overworld")));
        assertFalse(BackportBiomes.isVetoedRegion(ResourceLocation.fromNamespaceAndPath("biomesoplenty", "overworld_primary")));
        assertFalse(BackportBiomes.isVetoedRegion(ResourceLocation.fromNamespaceAndPath("vanillabackport", "nether")));
        assertFalse(BackportBiomes.isVetoedRegion(ResourceLocation.withDefaultNamespace("overworld")));
        assertFalse(BackportBiomes.isVetoedRegion(null));
    }

    @Test
    @DisplayName("the backport biomes are minecraft-namespaced, sulfur caves among them")
    void keysAreMinecraftNamespaced() {
        assertEquals(3, BackportBiomes.OVERWORLD.size());
        assertTrue(BackportBiomes.OVERWORLD.stream().allMatch(k -> "minecraft".equals(k.location().getNamespace())));
        assertTrue(BackportBiomes.OVERWORLD.contains(NetherBandBiomes.SULFUR_CAVES));
    }
}
