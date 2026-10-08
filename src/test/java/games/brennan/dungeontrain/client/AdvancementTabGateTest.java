package games.brennan.dungeontrain.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementTabGateTest {

    private static ResourceLocation rl(String id) {
        return ResourceLocation.parse(id);
    }

    @Test
    @DisplayName("with the option on, every tab outside dungeontrain is hidden — vanilla and siblings alike")
    void hidesOtherNamespaces() {
        assertTrue(AdvancementTabGate.isHiddenOtherTab(rl("minecraft:story/root"), true));
        assertTrue(AdvancementTabGate.isHiddenOtherTab(rl("adventureitemnames:root"), true));
        assertFalse(AdvancementTabGate.isHiddenOtherTab(rl("dungeontrain:dungeon_train/root"), true));
        assertFalse(AdvancementTabGate.isHiddenOtherTab(rl("dungeontrain:enchiridion/darkroom"), true));
    }

    @Test
    @DisplayName("with the option off, nothing is hidden by namespace")
    void showsEverythingWhenOff() {
        assertFalse(AdvancementTabGate.isHiddenOtherTab(rl("minecraft:story/root"), false));
    }

    @Test
    @DisplayName("the editor tree is recognised by its path")
    void editorTree() {
        assertTrue(AdvancementTabGate.isEditorAdvancement(rl("dungeontrain:editor/root")));
        assertFalse(AdvancementTabGate.isEditorAdvancement(rl("dungeontrain:dungeon_train/root")));
    }
}
