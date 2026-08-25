package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins when the title-screen slot button reveals the Train Builder instead of the editor.
 *
 * <p>Shift is the whole gate. The build the jar came from used to be half of it, which left the
 * Builder unreachable from any release — the case {@link #everyBuildRevealsOnShift} exists to keep
 * from coming back.</p>
 */
final class TrainBuilderMenuButtonTest {

    @Test
    @DisplayName("Shift reveals the builder, on every build")
    void everyBuildRevealsOnShift() {
        assertTrue(TrainBuilderMenuButton.shouldRevealBuilder(true));
    }

    @Test
    @DisplayName("Without Shift the slot is the editor — the finished tool is the default")
    void defaultIsTheEditor() {
        assertFalse(TrainBuilderMenuButton.shouldRevealBuilder(false));
    }
}
