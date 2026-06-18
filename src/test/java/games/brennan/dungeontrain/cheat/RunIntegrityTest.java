package games.brennan.dungeontrain.cheat;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor-advancement carve-out that {@link RunIntegrity#persistsAdvancement}
 * uses: in a cheated run, {@code dungeontrain:editor/*} authoring advancements
 * still persist; everything else (DT gameplay, vanilla, other mods) does not.
 * The game-mode-dependent half is verified in-game (it needs a real player
 * attachment); this pins the pure classification.
 */
class RunIntegrityTest {

    @Test
    @DisplayName("dungeontrain:editor/* advancements are editor-authoring (persist when cheated)")
    void editorAdvancementsRecognised() {
        assertTrue(RunIntegrity.isEditorAdvancement(
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "editor/made_track")));
        assertTrue(RunIntegrity.isEditorAdvancement(
            ResourceLocation.parse("dungeontrain:editor/saved_package")));
    }

    @Test
    @DisplayName("DT gameplay, vanilla, and other-mod advancements are NOT editor")
    void nonEditorAdvancementsRejected() {
        assertFalse(RunIntegrity.isEditorAdvancement(
            ResourceLocation.parse("dungeontrain:dungeon_train/chests_100_unique")));
        assertFalse(RunIntegrity.isEditorAdvancement(
            ResourceLocation.parse("minecraft:story/mine_stone")));
        assertFalse(RunIntegrity.isEditorAdvancement(
            ResourceLocation.fromNamespaceAndPath("someothermod", "editor/thing")));
    }

    @Test
    @DisplayName("The 'editor/' prefix needs the slash — 'editor' alone or 'editorish' is gameplay")
    void prefixRequiresSlash() {
        assertFalse(RunIntegrity.isEditorAdvancement(
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "editor")));
        assertFalse(RunIntegrity.isEditorAdvancement(
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "editorish/thing")));
    }
}
