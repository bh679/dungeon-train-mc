package games.brennan.dungeontrain.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which placeholders the Workbench fades: those inside a staged build's shell box, edges included. */
final class WorkbenchShellFadeTest {

    @Test
    @DisplayName("a position on or inside a shell box fades; one beside it, or with no boxes, does not")
    void containsIsInclusive() {
        List<BoundingBox> shells = List.of(new BoundingBox(9, 149, -1, 19, 157, 5));
        assertTrue(WorkbenchShellFade.contains(shells, new BlockPos(9, 149, -1)));
        assertTrue(WorkbenchShellFade.contains(shells, new BlockPos(19, 157, 5)));
        assertTrue(WorkbenchShellFade.contains(shells, new BlockPos(14, 153, 2)));
        assertFalse(WorkbenchShellFade.contains(shells, new BlockPos(20, 153, 2)));
        assertFalse(WorkbenchShellFade.contains(shells, new BlockPos(14, 148, 2)));
        assertFalse(WorkbenchShellFade.contains(List.of(), new BlockPos(14, 153, 2)));
    }
}
