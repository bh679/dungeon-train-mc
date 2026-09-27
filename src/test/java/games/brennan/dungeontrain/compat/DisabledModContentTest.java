package games.brennan.dungeontrain.compat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DisabledModContent} id rules, pinned against the real ids shipped in the BetterNether,
 * BetterEnd and BoP jars DT builds against.
 */
final class DisabledModContentTest {

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    @Test
    @DisplayName("every BetterNether / BetterEnd ore placed feature is vetoed")
    void oreFeaturesVetoed() {
        List<String> ores = List.of(
            "betternether:cincinnasite_ore", "betternether:nether_lapis_ore", "betternether:nether_redstone_ore",
            "betternether:nether_ruby_ore", "betternether:nether_ruby_rare_ore",
            "betternether:nether_ruby_large_ore", "betternether:nether_ruby_soul_ore",
            "betterend:amber_ore", "betterend:dragon_bone_ore", "betterend:ender_ore", "betterend:thallasium_ore");
        for (String ore : ores) {
            assertTrue(DisabledModContent.isDisabledOreFeature(id(ore)), ore);
        }
    }

    @Test
    @DisplayName("forests, rose quartz and vanilla ores are not vetoed")
    void nonOreFeaturesKept() {
        List<String> kept = List.of(
            "betternether:forest_litter", "betternether:vegetation_wart_forest", "betterend:chorus_forest_structures",
            "betterend:purple_polypore", "biomesoplenty:trees_redwood_forest", "biomesoplenty:large_rose_quartz",
            "minecraft:ore_diamond", "minecraft:ore_ancient_debris_large", "dungeontrain:track_bed");
        for (String k : kept) {
            assertFalse(DisabledModContent.isDisabledOreFeature(id(k)), k);
        }
        assertFalse(DisabledModContent.isDisabledOreFeature(null));
    }

    @Test
    @DisplayName("gear-class items in a covered namespace are disabled; vanilla gear is not")
    void gearByClass() {
        assertTrue(DisabledModContent.isDisabledItem(id("betternether:cincinnasite_excavator"), true));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:elytra_armored"), true));
        assertTrue(DisabledModContent.isDisabledItem(id("biomesoplenty:some_future_sword"), true));
        assertFalse(DisabledModContent.isDisabledItem(id("minecraft:diamond_sword"), true));
        assertFalse(DisabledModContent.isDisabledItem(id("dungeontrain:variant_clipboard"), true));
    }

    @Test
    @DisplayName("ore blocks and BetterEnd tool parts are disabled by id; materials are kept")
    void idRules() {
        assertTrue(DisabledModContent.isDisabledItem(id("betternether:nether_ruby_ore"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:thallasium_ore"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:terminite_axe_head"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:aeternium_hammer_head"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:thallasium_sword_blade"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:terminite_sword_handle"), false));

        assertFalse(DisabledModContent.isDisabledItem(id("betternether:cincinnasite_ingot"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("betternether:nether_ruby"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("betterend:thallasium_ingot"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("betterend:amber_gem"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("biomesoplenty:rose_quartz_chunk"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("minecraft:iron_ore"), false));
        assertFalse(DisabledModContent.isDisabledItem(null, true));
    }

    @Test
    @DisplayName("gear smithing templates are disabled; the fire-bowl template is kept")
    void smithingTemplates() {
        assertTrue(DisabledModContent.isDisabledItem(id("betternether:flaming_ruby_upgrade_smithing_template"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betternether:cincinnasite_diamond_upgrade_smithing_template"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:thallasium_upgrade_smithing_template"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:tool_assembly_smithing_template"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("betternether:bowl_upgrade_smithing_template"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("minecraft:netherite_upgrade_smithing_template"), false));
    }
}
