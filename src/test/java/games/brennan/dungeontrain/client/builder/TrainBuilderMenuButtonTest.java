package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins when the title-screen slot button reveals the technical Train Editor instead of the
 * builder. Same shape as {@code EditorMenuScreenTest}'s DevMode-visibility tests, and the same
 * fail-open direction on an unknown branch.
 */
final class TrainBuilderMenuButtonTest {

    @Test
    @DisplayName("Release build (branch == main) never reveals the editor, even with Shift held")
    void mainBranchNeverRevealsEditor() {
        assertFalse(TrainBuilderMenuButton.shouldRevealEditor("main", true));
        assertFalse(TrainBuilderMenuButton.shouldRevealEditor("main", false));
    }

    @Test
    @DisplayName("Dev build reveals the editor only while Shift is held")
    void devBranchRevealsEditorOnShift() {
        assertTrue(TrainBuilderMenuButton.shouldRevealEditor("dev/train-builder-mode", true));
        assertFalse(TrainBuilderMenuButton.shouldRevealEditor("dev/train-builder-mode", false));
    }

    @Test
    @DisplayName("Unknown branch fails open — hiding a dev affordance from a dev is the worse error")
    void unknownBranchFailsOpen() {
        assertTrue(TrainBuilderMenuButton.shouldRevealEditor("?", true));
        assertTrue(TrainBuilderMenuButton.shouldRevealEditor(null, true));
        assertFalse(TrainBuilderMenuButton.shouldRevealEditor("?", false));
    }
}
