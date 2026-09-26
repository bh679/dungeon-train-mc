package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WwooTagFilterTest {

    private static final String WWOO = "wwoo:resources/wwoo_main";
    private static final Function<String, String> PACK_ID = Function.identity();

    private static ResourceLocation file(String path) {
        return ResourceLocation.withDefaultNamespace("tags/" + path + ".json");
    }

    @Test
    void dropsWwooCopyOfUndoneTagButKeepsOtherPacks() {
        ResourceLocation snow = file("block/snow_layer_cannot_survive_on");
        Map<ResourceLocation, List<String>> in = Map.of(snow, List.of("vanilla", "mod_resources", WWOO));

        Map<ResourceLocation, List<String>> out = WwooTagFilter.filter(in, PACK_ID);

        assertEquals(List.of("vanilla", "mod_resources"), out.get(snow));
        assertEquals(List.of("vanilla", "mod_resources", WWOO), in.get(snow), "input untouched");
    }

    @Test
    void keepsWwooCopyOfTagsItsFeaturesNeed() {
        Map<ResourceLocation, List<String>> in = Map.of(
                file("block/sand"), List.of("vanilla", WWOO),
                file("block/mushroom_grow_block"), List.of("vanilla", WWOO),
                file("block/mangrove_roots_can_grow_through"), List.of("vanilla", WWOO),
                file("block/mangrove_logs_can_grow_through"), List.of("vanilla", WWOO),
                file("block/mangrove_logs"), List.of("vanilla", WWOO));

        assertSame(in, WwooTagFilter.filter(in, PACK_ID));
    }

    @Test
    void coversAllFourUndoneTags() {
        for (String path : List.of("block/snow_layer_cannot_survive_on", "worldgen/biome/spawns_warm_variant_frogs",
                "worldgen/biome/spawns_cold_variant_frogs", "block/deepslate_ore_replaceables")) {
            assertTrue(WwooTagFilter.shouldDrop(file(path), WWOO), path);
        }
    }

    @Test
    void ignoresNonWwooPacksAndOtherNamespaces() {
        assertFalse(WwooTagFilter.shouldDrop(file("block/snow_layer_cannot_survive_on"), "vanilla"));
        assertFalse(WwooTagFilter.shouldDrop(file("block/snow_layer_cannot_survive_on"), null));
        assertFalse(WwooTagFilter.shouldDrop(
                ResourceLocation.fromNamespaceAndPath("wythers", "tags/block/dirt.json"), WWOO));
    }
}
