package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives a category switch.
 *
 * <p>Tearing the plots down invalidates any step that placed blocks, but a menu change is a file on
 * disk — it undoes the same whether its template is stamped in the world or not, and wiping those
 * made every weight, gate and stage edit un-undoable the moment the author looked elsewhere.</p>
 */
final class EditorEditHistoryTest {

    private static final UUID PLAYER = UUID.nameUUIDFromBytes("editor-history-test".getBytes());

    @AfterEach
    void tearDown() {
        EditorEditHistory.clearAll();
    }

    /** A step that only rewrote authored config — no world cells. */
    private static EditorEditHistory.Step menuStep(String label) {
        return new EditorEditHistory.Step("editor menu", label, List.of(), List.of(),
            List.of(new EditorEditHistory.FileSnapshot("weights.json", "{\"a\":1}", "{\"a\":2}")));
    }

    /** A step that placed a block, so the plot has to still be standing for it to mean anything. */
    private static EditorEditHistory.Step blockStep(String label) {
        return new EditorEditHistory.Step("carriages/pen", label,
            List.of(new EditorEditHistory.Cell(BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), null)),
            List.of(), List.of());
    }

    @Test
    @DisplayName("a category switch keeps menu changes and drops the ones that placed blocks")
    void keepsMenuStepsAcrossACategorySwitch() {
        EditorEditHistory.push(PLAYER, menuStep("editor weight"));
        EditorEditHistory.push(PLAYER, blockStep("Place"));
        EditorEditHistory.push(PLAYER, menuStep("editor phase"));
        assertEquals(3, EditorEditHistory.undoDepth(PLAYER));

        EditorEditHistory.clearWorldBackedSteps();

        assertEquals(2, EditorEditHistory.undoDepth(PLAYER), "both menu steps must survive");
        assertEquals("editor phase", EditorEditHistory.peekUndoLabel(PLAYER),
            "and the newest of them is still what Undo would reverse");
    }

    @Test
    @DisplayName("the redo stack is filtered the same way")
    void filtersRedoToo() {
        EditorEditHistory.pushRedo(PLAYER, blockStep("Place"));
        EditorEditHistory.pushRedo(PLAYER, menuStep("editor stage"));
        EditorEditHistory.clearWorldBackedSteps();
        assertEquals(1, EditorEditHistory.redoDepth(PLAYER));
        assertEquals("editor stage", EditorEditHistory.peekRedoLabel(PLAYER));
    }

    @Test
    @DisplayName("a player left with nothing keeps no stacks, and clearAll still clears everything")
    void emptiesAndClears() {
        EditorEditHistory.push(PLAYER, blockStep("Place"));
        EditorEditHistory.clearWorldBackedSteps();
        assertEquals(0, EditorEditHistory.undoDepth(PLAYER));
        assertEquals("", EditorEditHistory.peekUndoLabel(PLAYER));

        EditorEditHistory.push(PLAYER, menuStep("editor weight"));
        EditorEditHistory.clearAll();
        assertEquals(0, EditorEditHistory.undoDepth(PLAYER));
        assertTrue(EditorEditHistory.peekRedoLabel(PLAYER).isEmpty());
    }
}
