package games.brennan.dungeontrain.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("ore blocks, gear metals and BetterEnd tool parts are disabled by id; gems and blocks are kept")
    void idRules() {
        assertTrue(DisabledModContent.isDisabledItem(id("betternether:nether_ruby_ore"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:thallasium_ore"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:terminite_axe_head"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:aeternium_hammer_head"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:thallasium_sword_blade"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:terminite_sword_handle"), false));

        assertTrue(DisabledModContent.isDisabledItem(id("betternether:cincinnasite_ingot"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:thallasium_ingot"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:terminite_ingot"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:aeternium_ingot"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:thallasium_nugget"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:terminite_nugget"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("betterend:raw_amber"), false));

        assertFalse(DisabledModContent.isDisabledItem(id("betternether:nether_ruby"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("betternether:cincinnasite_block"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("betterend:thallasium_block"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("minecraft:iron_ingot"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("betterend:amber_gem"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("biomesoplenty:rose_quartz_chunk"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("minecraft:iron_ore"), false));
        assertFalse(DisabledModContent.isDisabledItem(null, true));
    }

    @Test
    @DisplayName("Exposure's camera, film, lightroom and the instant slides are hidden; the instant camera and photographs are kept")
    void exposureFilmWorkflowHidden() {
        for (String hidden : new String[] {"camera", "black_and_white_film", "color_film",
                "high_sensitivity_black_and_white_film", "high_sensitivity_color_film",
                "developed_black_and_white_film", "developed_color_film", "lightroom", "chromatic_sheet"}) {
            assertTrue(DisabledModContent.isDisabledItem(id("exposure:" + hidden), false), hidden);
        }
        assertFalse(DisabledModContent.isDisabledItem(id("exposure:photograph"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("exposure:album"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("exposure:photograph_frame"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("exposure:camera_stand"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("exposure_polaroid:instant_camera"), false));
        for (String slide : new String[] {"instant_color_slide", "instant_black_and_white_slide",
                "high_sensitivity_instant_color_slide", "high_sensitivity_instant_black_and_white_slide"}) {
            assertTrue(DisabledModContent.isDisabledItem(id("exposure_polaroid:" + slide), false), slide);
        }
    }

    @Test
    @DisplayName("Vista's viewfinder, wave gate and cassettes are hidden; the TV, mirror, picture tape and DT's live cassette stay")
    void vistaContentHidden() {
        for (String hidden : new String[] {"viewfinder", "wave_gate", "cassette", "hollow_cassette"}) {
            assertTrue(DisabledModContent.isDisabledItem(id("vista:" + hidden), false), hidden);
        }
        for (String kept : new String[] {"vista:television", "vista:mirror", "vista:picture_tape",
                "vista:music_disc_sojourn", "vista:crystalline", "dungeontrain:live_cassette"}) {
            assertFalse(DisabledModContent.isDisabledItem(id(kept), false), kept);
        }
    }

    @Test
    @DisplayName("Vista's picture tape recipe is turned off by id; its other recipes stay")
    void pictureTapeRecipeDisabled() {
        assertTrue(DisabledModContent.isDisabledRecipe(id("vista:picture_tape")));
        assertFalse(DisabledModContent.isDisabledRecipe(id("vista:mirror")));
        assertFalse(DisabledModContent.isDisabledRecipe(id("vista:television")));
        assertFalse(DisabledModContent.isDisabledRecipe(null));
    }

    @Test
    @DisplayName("the reloadable instant camera is hidden; the disposable camera on the same item is kept")
    void plainInstantCameraHidden() {
        assertTrue(DisabledModContent.isPlainInstantCamera(id("exposure_polaroid:instant_camera"), false));
        assertFalse(DisabledModContent.isPlainInstantCamera(id("exposure_polaroid:instant_camera"), true));
        assertFalse(DisabledModContent.isPlainInstantCamera(id("exposure_polaroid:instant_color_slide"), false));
        assertFalse(DisabledModContent.isPlainInstantCamera(null, false));
    }

    @Test
    @DisplayName("mob gear swaps to iron, or diamond for diamond variants; non-gear is removed")
    void mobGearReplacement() {
        assertEquals("iron_helmet", DisabledModMobGear.vanillaReplacementId("cincinnasite_helmet", "helmet"));
        assertEquals("diamond_sword", DisabledModMobGear.vanillaReplacementId("cincinnasite_sword_diamond", "sword"));
        assertEquals("iron_axe", DisabledModMobGear.vanillaReplacementId("thallasium_hammer", "axe"));
        assertEquals("bow", DisabledModMobGear.vanillaReplacementId("some_bow", "bow"));
        assertNull(DisabledModMobGear.vanillaReplacementId("cincinnasite_ingot", null));
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

    @Test
    @DisplayName("the biome mods' own creative tabs are recognised; vanilla and DT tabs are not")
    void creativeTabs() {
        List<String> hidden = List.of(
            "betternether:blocks_tab", "betternether:items_tab", "betternether:nature_tab",
            "betterend:blocks_tab", "betterend:items_tab", "betterend:nature_tab",
            "biomesoplenty:main", "vanillabackport:vanilla_backport", "exposure:exposure");
        for (String tab : hidden) {
            assertTrue(DisabledModContent.isBiomeModCreativeTab(id(tab)), tab);
        }
        assertFalse(DisabledModContent.isBiomeModCreativeTab(id("minecraft:building_blocks")));
        assertFalse(DisabledModContent.isBiomeModCreativeTab(id("minecraft:search")));
        assertFalse(DisabledModContent.isBiomeModCreativeTab(id("dungeontrain:prefab_variants")));
        assertFalse(DisabledModContent.isBiomeModCreativeTab(null));
    }

    @Test
    @DisplayName("those tabs and their search entries ship hidden")
    void creativeSettingsDefaultOff() {
        assertFalse(games.brennan.dungeontrain.config.ClientDisplayConfig.DEFAULT_CREATIVE_MOD_BLOCK_TABS);
        assertFalse(games.brennan.dungeontrain.config.ClientDisplayConfig.DEFAULT_CREATIVE_MOD_BLOCKS_IN_SEARCH);
    }

    /** Every non-vanilla enchantment in the jars DT builds against (BetterNether + BetterEnd). */
    private static final List<String> MOD_ENCHANTMENTS = List.of(
        "betterend:resonance", "betterend:end_veil", "betternether:obsidian_breaker", "betternether:ruby_fire",
        "deeperdarker:volume");

    @Test
    @DisplayName("every mod enchantment is disabled; vanilla ones are kept")
    void modEnchantmentsDisabled() {
        for (String e : MOD_ENCHANTMENTS) {
            assertTrue(DisabledModContent.isDisabledEnchantment(id(e)), e);
        }
        assertTrue(DisabledModContent.isDisabledEnchantment(id("biomesoplenty:some_future_enchantment")));
        assertFalse(DisabledModContent.isDisabledEnchantment(id("minecraft:sharpness")));
        assertFalse(DisabledModContent.isDisabledEnchantment(id("minecraft:binding_curse")));
        assertFalse(DisabledModContent.isDisabledEnchantment(null));
    }

    @Test
    @DisplayName("the enchanting table and villager tags remove every mod enchantment, optionally")
    void enchantmentTagsRemoveModEnchantments() throws Exception {
        for (String tag : List.of("in_enchanting_table", "tradeable")) {
            String path = "/data/minecraft/tags/enchantment/" + tag + ".json";
            try (InputStream in = DisabledModContentTest.class.getResourceAsStream(path)) {
                assertTrue(in != null, path);
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                assertFalse(json.get("replace").getAsBoolean(), tag + " must not replace vanilla's list");
                Set<String> removed = new HashSet<>();
                for (JsonElement entry : json.getAsJsonArray("remove")) {
                    JsonObject o = entry.getAsJsonObject();
                    assertFalse(o.get("required").getAsBoolean(), tag + ": a missing mod must not break the tag");
                    removed.add(o.get("id").getAsString());
                }
                assertEquals(Set.copyOf(MOD_ENCHANTMENTS), removed, tag);
            }
        }
    }

    private static final List<String> DISABLED_BLOCKS = List.of(
        "betternether:pig_statue_respawner", "betterend:respawn_obelisk", "betterend:infusion_pedestal",
        "betterend:aeternium_anvil", "betterend:terminite_anvil", "betterend:thallasium_anvil",
        "betterend:end_stone_smelter");

    @Test
    @DisplayName("respawn statues, the infusion pedestal, BetterEnd's anvils and smelter are off as blocks and items")
    void disabledBlocks() {
        for (String b : DISABLED_BLOCKS) {
            assertTrue(DisabledModContent.isDisabledBlock(id(b)), b);
            assertTrue(DisabledModContent.isDisabledItem(id(b), false), b);
        }
        for (String kept : List.of("betterend:purpur_pedestal", "betterend:flavolite_pedestal",
                "betternether:cincinnasite_anvil", "betternether:cincinnasite_forge", "minecraft:anvil",
                "minecraft:respawn_anchor")) {
            assertFalse(DisabledModContent.isDisabledBlock(id(kept)), kept);
            assertFalse(DisabledModContent.isDisabledItem(id(kept), false), kept);
        }
        assertFalse(DisabledModContent.isDisabledBlock(null));
    }

    @Test
    @DisplayName("BetterEnd's elytras, smith hammers and assembly templates are off")
    void betterEndElytrasHammersTemplates() {
        for (String gear : List.of("betterend:elytra_armored", "betterend:elytra_crystalite",
                "betterend:iron_hammer", "betterend:golden_hammer", "betterend:diamond_hammer",
                "betterend:netherite_hammer")) {
            assertTrue(DisabledModContent.isDisabledItem(id(gear), true), gear);
        }
        for (String t : List.of("betterend:plate_upgrade_smithing_template",
                "betterend:handle_attachment_smithing_template",
                "betterend:leather_handle_attachment_smithing_template",
                "betterend:tool_assembly_smithing_template", "betterend:netherite_upgrade_smithing_template")) {
            assertTrue(DisabledModContent.isDisabledItem(id(t), false), t);
        }

    @Test
    @DisplayName("Deeper Darker: its gear and upgrade templates are off, its ores and materials stay")
    void deeperDarkerGearOnly() {
        assertTrue(DisabledModContent.isDisabledItem(id("deeperdarker:warden_sword"), true));
        assertTrue(DisabledModContent.isDisabledItem(id("deeperdarker:resonarium_chestplate"), true));
        assertTrue(DisabledModContent.isDisabledItem(id("deeperdarker:soul_elytra"), true));
        assertTrue(DisabledModContent.isDisabledItem(id("deeperdarker:warden_upgrade_smithing_template"), false));
        assertTrue(DisabledModContent.isDisabledItem(id("deeperdarker:sonorous_staff"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("deeperdarker:sculk_stone_diamond_ore"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("deeperdarker:warden_carapace"), false));
        assertFalse(DisabledModContent.isDisabledItem(id("deeperdarker:heart_of_the_deep"), false));
    }

    @Test
    @DisplayName("Deeper Darker's Sculk Smite, Catalysis and Reverberation are kept; Volume is off")
    void deeperDarkerEnchantments() {
        assertFalse(DisabledModContent.isDisabledEnchantment(id("deeperdarker:sculk_smite")));
        assertFalse(DisabledModContent.isDisabledEnchantment(id("deeperdarker:catalysis")));
        assertFalse(DisabledModContent.isDisabledEnchantment(id("deeperdarker:reverberation")));
        assertTrue(DisabledModContent.isDisabledEnchantment(id("deeperdarker:volume")));
    }
}
