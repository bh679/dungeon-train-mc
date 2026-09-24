package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** BetterNether/BetterEnd display advancements leave the map; everything else stays, in order. */
final class ForeignAdvancementFilterTest {

    private static ResourceLocation rl(String id) {
        return ResourceLocation.parse(id);
    }

    private static Map<ResourceLocation, JsonElement> mapOf(String... ids) {
        Map<ResourceLocation, JsonElement> m = new LinkedHashMap<>();
        for (String id : ids) m.put(rl(id), new JsonObject());
        return m;
    }

    @Test
    @DisplayName("blocked tabs removed, recipes and other namespaces kept in order")
    void removesBlockedTabs() {
        Map<ResourceLocation, JsonElement> loaded = mapOf(
            "dungeontrain:dungeon_train/root",
            "betternether:root",
            "betternether:recipes/misc/cincinnasite_forge",
            "minecraft:story/root",
            "betterend:enter_end",
            "betterend:recipes/tools/aeternium_hammer",
            "bclib:recipes/combat/tag_shield",
            "betternether:all_the_biomes");

        Map<ResourceLocation, JsonElement> out = ForeignAdvancementFilter.removeBlocked(loaded);

        assertEquals(List.of(
            rl("dungeontrain:dungeon_train/root"),
            rl("betternether:recipes/misc/cincinnasite_forge"),
            rl("minecraft:story/root"),
            rl("betterend:recipes/tools/aeternium_hammer"),
            rl("bclib:recipes/combat/tag_shield")), List.copyOf(out.keySet()));
        assertEquals(8, loaded.size(), "input must not be mutated");
    }

    @Test
    @DisplayName("nothing blocked returns the same instance")
    void nothingBlockedIsIdentity() {
        Map<ResourceLocation, JsonElement> loaded = mapOf("minecraft:story/root", "betterend:recipes/x");
        assertSame(loaded, ForeignAdvancementFilter.removeBlocked(loaded));
    }
}
