package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.TabGateways;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TabCompletionTest {

    private static ResourceLocation rl(String s) { return ResourceLocation.parse(s); }

    @Test
    @DisplayName("each tab is complete with its tab-complete advancement; Dungeon Train with the Everything Burrito")
    void capstones() {
        TabGateways.Layout l = TabGateways.layout();
        assertEquals(rl("dungeontrain:dungeon_train/completionist"), TabCompletion.capstoneFor(l, rl("dungeontrain:dungeon_train/root")));
        assertEquals(rl("dungeontrain:dungeon_train/train_explored"), TabCompletion.capstoneFor(l, rl("dungeontrain:dungeon_train/tab_train_explorer")));
        assertEquals(rl("dungeontrain:dungeon_train/challenge_complete"), TabCompletion.capstoneFor(l, rl("dungeontrain:dungeon_train/tab_challenges")));
        assertEquals(rl("dungeontrain:dungeon_train/all_others"), TabCompletion.capstoneFor(l, rl("dungeontrain:dungeon_train/tab_others")));
        assertEquals(rl("dungeontrain:dungeon_train/heros_handbook"), TabCompletion.capstoneFor(l, rl("dungeontrain:dungeon_train/the_enchiridion")));
        assertEquals(rl("dungeontrain:dungeon_train/fully_developed"), TabCompletion.capstoneFor(l, rl("dungeontrain:enchiridion/darkroom")));
        assertEquals(rl("dungeontrain:dungeon_train/all_out_of_secrets"), TabCompletion.capstoneFor(l, rl("dungeontrain:secrete_menu/root")));
        assertNull(TabCompletion.capstoneFor(l, rl("dungeontrain:editor/root")), "the Editor tab never goes gold");
        assertNull(TabCompletion.capstoneFor(l, rl("minecraft:story/root")));
    }
}
