package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedPlot;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a staged build's doors are measured from — the box its own kind's editor would cut them in
 * ({@link EditorDoorGhosts#carriageBoxOf}, {@link WorkbenchStagedPlot#shellOrigin}).
 */
final class WorkbenchStagedDoorsTest {

    private static final BlockPos ORIGIN = new BlockPos(10, 150, 0);

    private static WorkbenchStagedPlot plot(BuilderPhotoPaths.Kind kind, Vec3i size) {
        return new WorkbenchStagedPlot("b-1", kind, "", ORIGIN, size);
    }

    @Test
    @DisplayName("a staged carriage's door box is its own size, read as (length, width, height)")
    void carriageBox() {
        CarriageDims box = EditorDoorGhosts.carriageBoxOf(plot(BuilderPhotoPaths.Kind.CARRIAGE, new Vec3i(9, 7, 5)));
        assertEquals(new CarriageDims(9, 5, 7), box);
    }

    @Test
    @DisplayName("a staged contents build stands interior-only, so its doors are cut in the shell one block out")
    void contentsShell() {
        WorkbenchStagedPlot contents = plot(BuilderPhotoPaths.Kind.CONTENTS, new Vec3i(7, 5, 3));
        assertEquals(ORIGIN.offset(-1, -1, -1), contents.shellOrigin());
        assertEquals(new Vec3i(9, 7, 5), contents.shellSize());
        assertEquals(new CarriageDims(9, 5, 7), EditorDoorGhosts.carriageBoxOf(contents));
    }

    @Test
    @DisplayName("a carriage no carriage could be has no door line, rather than throwing")
    void outOfRangeHasNoDoors() {
        assertNull(EditorDoorGhosts.carriageBoxOf(plot(BuilderPhotoPaths.Kind.CARRIAGE, new Vec3i(200, 7, 5))));
    }

    @Test
    @DisplayName("kinds with no two ends to name get no door box; only a room is a room")
    void otherKinds() {
        assertNull(EditorDoorGhosts.carriageBoxOf(plot(BuilderPhotoPaths.Kind.PART, new Vec3i(9, 7, 5))));
        assertNull(EditorDoorGhosts.carriageBoxOf(plot(BuilderPhotoPaths.Kind.PORTAL_ROOM, new Vec3i(11, 7, 13))));
        assertTrue(plot(BuilderPhotoPaths.Kind.PORTAL_ROOM, new Vec3i(11, 7, 13)).isRoom());
        assertFalse(plot(BuilderPhotoPaths.Kind.CARRIAGE, new Vec3i(9, 7, 5)).isRoom());
        assertEquals(ORIGIN, plot(BuilderPhotoPaths.Kind.PORTAL_ROOM, new Vec3i(11, 7, 13)).shellOrigin());
    }
}
