package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which carriage backs the saved template.
 *
 * <p>A mode parks the same variant more than once, so "which one is the build?" has to be
 * answered somehow. Getting it wrong writes the wrong carriage over the template — silently, and
 * only noticed the next time the world is re-stamped.</p>
 */
final class BuilderSaveTest {

    @Test
    @DisplayName("The edited carriage is the one saved")
    void dirtyCarriageWins() {
        assertEquals(Optional.of(2), BuilderSave.saveTarget(3, List.of(2)));
    }

    @Test
    @DisplayName("With several edited, the first one is saved — deterministic, not arbitrary")
    void firstDirtyWinsWhenSeveral() {
        assertEquals(Optional.of(1), BuilderSave.saveTarget(3, List.of(1, 2)));
    }

    @Test
    @DisplayName("Nothing edited still saves the first carriage rather than looking broken")
    void cleanSavesTheFirst() {
        assertEquals(Optional.of(0), BuilderSave.saveTarget(3, List.of()));
        assertEquals(Optional.of(0), BuilderSave.saveTarget(1, List.of()));
    }

    @Test
    @DisplayName("No carriages means nothing to save")
    void noVolumesMeansNoTarget() {
        assertTrue(BuilderSave.saveTarget(0, List.of()).isEmpty());
        assertTrue(BuilderSave.saveTarget(0, List.of(0)).isEmpty(),
                "a stale dirty index can't conjure a volume");
    }

    @Test
    @DisplayName("A dirty index outside the current volumes is ignored, not trusted")
    void staleDirtyIndexIsIgnored() {
        // Switching modes shrinks the train; a leftover index from the old shape must not be used
        // to look up a volume that no longer exists.
        assertEquals(Optional.of(0), BuilderSave.saveTarget(1, List.of(2)));
    }
}
