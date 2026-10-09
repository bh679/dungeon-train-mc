package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TabOrderTest {

    private static final String DT = "dungeontrain:dungeon_train/";

    @Test
    @DisplayName("listed tabs in their order, then other Dungeon Train tabs, then everyone else in arrival order")
    void sorts() {
        List<String> order = List.of(DT + "root", DT + "tab_train_explorer", DT + "tab_challenges", DT + "tab_others");
        List<ResourceLocation> arrived = List.of(
            ResourceLocation.parse("minecraft:story/root"), ResourceLocation.parse(DT + "tab_others"),
            ResourceLocation.parse("othermod:root"), ResourceLocation.parse(DT + "tab_new"),
            ResourceLocation.parse(DT + "root"), ResourceLocation.parse(DT + "tab_challenges"),
            ResourceLocation.parse("minecraft:nether/root"));
        assertEquals(List.of(DT + "root", DT + "tab_challenges", DT + "tab_others", DT + "tab_new",
                "minecraft:story/root", "othermod:root", "minecraft:nether/root"),
            TabOrder.sorted(order, arrived, r -> r).stream().map(ResourceLocation::toString).toList());
    }

    @Test
    @DisplayName("the shipped order: Dungeon Train, Train Explorer, Challenges, Others, Enchiridion, Darkroom, Secret Menu")
    void shippedOrder() {
        assertEquals(List.of(DT + "root", DT + "tab_train_explorer", DT + "tab_challenges", DT + "tab_others",
                DT + "the_enchiridion", "dungeontrain:enchiridion/darkroom", "dungeontrain:secrete_menu/root",
                "dungeontrain:editor/root"),
            TabGateways.layout().order());
    }
}
