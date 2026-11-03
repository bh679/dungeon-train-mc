package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.compat.AdvancementTileDecor;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabCompleteTileTest {

    private static final String DT = "dungeontrain:dungeon_train/";
    private static final TabGateways.Layout LAYOUT = new TabGateways.Layout(
        Map.of(DT + "train_explored_tab", DT + "train_explored", DT + "tab_train_explorer", DT + "carts_100"),
        Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of(DT + "train_explored", DT + "tab_train_explorer"));

    private static boolean shaded(String path) {
        return AdvancementTileDecor.hasStarFrame(LAYOUT, ResourceLocation.parse(DT + path));
    }

    @Test
    @DisplayName("the Everything Burrito and It's Not That Simple wear it too")
    void burritoAndStartAgain() {
        assertTrue(shaded("completionist"));
        assertTrue(shaded("start_again"));
    }

    @Test
    @DisplayName("a tab-complete advancement and its in-tab copy wear the star frame")
    void tabCompleteAndCopy() {
        assertTrue(shaded("train_explored"));
        assertTrue(shaded("train_explored_tab"));
    }

    @Test
    @DisplayName("other advancements, and copies of non-tab-complete ones, are not")
    void others() {
        assertFalse(shaded("carts_100"));
        assertFalse(shaded("tab_train_explorer"));
        assertFalse(shaded("reached_nether"));
    }
}
