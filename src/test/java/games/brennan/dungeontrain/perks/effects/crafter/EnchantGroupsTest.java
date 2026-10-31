package games.brennan.dungeontrain.perks.effects.crafter;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantGroupsTest {

    private static List<String> group(int n) throws Exception {
        String path = "/data/dungeontrain/tags/enchantment/perk_group_" + n + ".json";
        try (var in = EnchantGroupsTest.class.getResourceAsStream(path)) {
            JsonArray values = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("values");
            return values.asList().stream().map(v -> v.getAsString()).toList();
        }
    }

    @Test
    @DisplayName("ten groups, protection first and melee damage second, no enchantment in two groups")
    void groupsInOrder() throws Exception {
        assertEquals(CrafterPerks.GROUPS, EnchantGroups.TAGS.size());
        assertTrue(group(1).contains("minecraft:protection"));
        assertTrue(group(2).contains("minecraft:sharpness"));
        assertEquals(List.of("minecraft:efficiency"), group(3));
        assertEquals(List.of("minecraft:unbreaking"), group(4));
        assertTrue(group(10).contains("minecraft:riptide"));
        Set<String> seen = new HashSet<>();
        for (int n = 1; n <= CrafterPerks.GROUPS; n++) {
            for (String id : group(n)) assertTrue(seen.add(id), id + " is in two groups");
        }
        assertTrue(!seen.contains("minecraft:mending") && !seen.contains("minecraft:silk_touch"),
            "single-level enchantments are in no group");
    }

    @Test
    @DisplayName("a tooltip line names its enchantment by its description key; level and other lines name none")
    void tooltipLineEnchantment() {
        assertEquals(ResourceLocation.withDefaultNamespace("sharpness"),
            CrafterTooltipBonus.enchantmentOf(Component.translatable("enchantment.minecraft.sharpness")));
        assertNull(CrafterTooltipBonus.enchantmentOf(Component.translatable("enchantment.level.3")));
        assertNull(CrafterTooltipBonus.enchantmentOf(Component.translatable("item.minecraft.diamond_sword")));
        assertNull(CrafterTooltipBonus.enchantmentOf(Component.literal("Sharpness III")));
    }
}
