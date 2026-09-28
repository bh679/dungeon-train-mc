package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.portal.PortalChunkTerrain.Source;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link VanillaOnlySample}: the vanilla End and Nether carriages keep only
 * {@code minecraft:} features and structures, and the Better rooms are not held to that at all.
 */
final class VanillaOnlySampleTest {

    @Test
    @DisplayName("vanilla features and structures are allowed")
    void allowsVanilla() {
        assertTrue(VanillaOnlySample.allows(ResourceLocation.parse("minecraft:end_city")));
        assertTrue(VanillaOnlySample.allows(ResourceLocation.parse("minecraft:chorus_plant")));
        assertTrue(VanillaOnlySample.allows(ResourceLocation.parse("minecraft:fortress")));
    }

    @Test
    @DisplayName("BetterEnd's structures and the features it injects into vanilla End biomes are refused")
    void refusesBetterEnd() {
        assertFalse(VanillaOnlySample.allows(ResourceLocation.parse("betterend:eternal_portal")));
        assertFalse(VanillaOnlySample.allows(ResourceLocation.parse("betterend:end_bridge")));
        assertFalse(VanillaOnlySample.allows(ResourceLocation.parse("betterend:ender_ore")));
        assertFalse(VanillaOnlySample.allows(ResourceLocation.parse("betterend:crashed_ship")));
    }

    @Test
    @DisplayName("other mods, Dungeon Train's own, and unregistered ids are refused")
    void refusesEverythingElse() {
        assertFalse(VanillaOnlySample.allows(ResourceLocation.parse("betternether:cincinnasite_ore")));
        assertFalse(VanillaOnlySample.allows(ResourceLocation.parse("dungeontrain:track_bed")));
        assertFalse(VanillaOnlySample.allows(null));
    }

    @Test
    @DisplayName("only the vanilla End and Nether rooms are vanilla-only")
    void vanillaOnlySources() {
        assertTrue(Source.END.vanillaOnly());
        assertTrue(Source.NETHER.vanillaOnly());
        assertFalse(Source.END_BETTER.vanillaOnly());
        assertFalse(Source.NETHER_BETTER.vanillaOnly());
        assertFalse(Source.OVERWORLD.vanillaOnly());
        assertFalse(Source.OVERWORLD_WWOO.vanillaOnly());
        assertFalse(Source.OVERWORLD_BOP.vanillaOnly());
    }

    @Test
    @DisplayName("the decoration flag is off outside a vanilla-only pass")
    void flagDefaultsOff() {
        assertFalse(VanillaOnlySample.isActive());
    }
}
