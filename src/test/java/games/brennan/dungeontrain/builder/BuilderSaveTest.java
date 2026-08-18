package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which carriages back the saved templates.
 *
 * <p>Train Outside parks a whole group and every carriage in it is editable, so "which one is the
 * build?" was the wrong question: the answer used to be one carriage, and two thirds of a group build
 * went silently unwritten. It is now every carriage actually worked in — but not the untouched ones,
 * or a save would put copies of the stamped shell over its siblings' templates.</p>
 */
final class BuilderSaveTest {

    @Test
    @DisplayName("Every edited carriage is saved, in slot order")
    void everyDirtyCarriageIsSaved() {
        assertEquals(List.of(0, 1, 2), BuilderSave.volumesToSave(3, List.of(2, 0, 1)));
        assertEquals(List.of(1, 2), BuilderSave.volumesToSave(3, List.of(1, 2)));
    }

    @Test
    @DisplayName("Only the edited ones — an untouched carriage keeps its own template")
    void untouchedCarriagesAreLeftAlone() {
        assertEquals(List.of(1), BuilderSave.volumesToSave(3, List.of(1)),
                "writing 0 and 2 as well would overwrite them with the stamped shell");
    }

    @Test
    @DisplayName("Nothing edited still saves the first carriage rather than looking broken")
    void cleanSavesTheFirst() {
        assertEquals(List.of(0), BuilderSave.volumesToSave(3, List.of()));
        assertEquals(List.of(0), BuilderSave.volumesToSave(1, List.of()));
    }

    @Test
    @DisplayName("No carriages means nothing to save")
    void noVolumesMeansNothingToSave() {
        assertTrue(BuilderSave.volumesToSave(0, List.of()).isEmpty());
        assertTrue(BuilderSave.volumesToSave(0, List.of(0)).isEmpty(),
                "a stale dirty index can't conjure a volume");
    }

    @Test
    @DisplayName("A dirty index outside the current volumes is ignored, not trusted")
    void staleDirtyIndexIsIgnored() {
        // Switching modes shrinks the train; a leftover index from the old shape must not be used
        // to look up a volume that no longer exists.
        assertEquals(List.of(0), BuilderSave.volumesToSave(1, List.of(2)));
        assertEquals(List.of(0), BuilderSave.volumesToSave(2, List.of(0, 5)));
    }

    @Test
    @DisplayName("A repeated index is one carriage, not two saves of it")
    void duplicatesCollapse() {
        assertEquals(List.of(1), BuilderSave.volumesToSave(3, List.of(1, 1)));
    }
}
