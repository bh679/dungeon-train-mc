package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins when the title-screen slot button reveals the unfinished Train Builder instead of the
 * editor. Same shape as {@code EditorMenuScreenTest}'s DevMode-visibility tests, and the same
 * fail-open direction on an unknown branch.
 */
final class TrainBuilderMenuButtonTest {

    @Test
    @DisplayName("Release build (branch == main) never reveals the builder, even with Shift held")
    void mainBranchNeverRevealsBuilder() {
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder("main", true));
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder("main", false));
    }

    @Test
    @DisplayName("Dev build reveals the builder only while Shift is held")
    void devBranchRevealsBuilderOnShift() {
        assertTrue(TrainBuilderMenuButton.shouldRevealBuilder("dev/train-builder-mode", true));
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder("dev/train-builder-mode", false));
    }

    @Test
    @DisplayName("Unknown branch fails open — hiding a dev affordance from a dev is the worse error")
    void unknownBranchFailsOpen() {
        assertTrue(TrainBuilderMenuButton.shouldRevealBuilder("?", true));
        assertTrue(TrainBuilderMenuButton.shouldRevealBuilder(null, true));
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder("?", false));
    }

    @Test
    @DisplayName("Without Shift the slot is always the editor — the finished tool is the default")
    void defaultIsTheEditor() {
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder("main", false));
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder("dev/anything", false));
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder(null, false));
    }
}
