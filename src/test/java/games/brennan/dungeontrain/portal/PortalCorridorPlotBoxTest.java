package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.EditorMirror;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the editor treating a portal corridor as carriage-sized.
 *
 * <p>Lengthening the corridor made the portal variant's box bigger than {@link CarriageDims}, and
 * several places went on using {@code dims} as "the box this variant occupies". Two of them were
 * reported from the editor: edits appeared not to save (the template lookup ran at carriage dims,
 * failed the size gate and poisoned {@code CarriageTemplateStore}'s id-keyed cache), and mirroring
 * put blocks in the wrong place (the plot footprint was carriage-sized, so the reflection ran about
 * the wrong axis and edits past the carriage's length were rejected outright).</p>
 *
 * <p>The cache and plot-resolution paths need a live level, so what is pinned here is the arithmetic
 * underneath the visible symptom: which axis a corridor-sized box mirrors about, and that it is not
 * the axis a carriage-sized one would use.</p>
 */
class PortalCorridorPlotBoxTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    /** The corridor's box, in the {@code (length, height, width)} order footprints use. */
    private static Vec3i corridorFootprint() {
        CarriageDims box = PortalCorridorSize.corridorDims(DIMS, PortalCorridorKind.LONG);
        return new Vec3i(box.length(), box.height(), box.width());
    }

    @Test
    @DisplayName("A corridor mirrors about its own centre — x <-> 12, not the carriage's x <-> 8")
    void mirrorsAboutTheCorridorCentre() {
        int corridor = PortalCorridorSize.corridorLength(DIMS, PortalCorridorKind.LONG);   // 13
        int carriage = DIMS.length();                             // 9

        // The bug in one line: same edit, two boxes, two different destinations.
        assertEquals(12, EditorMirror.target(0, corridor));
        assertEquals(8, EditorMirror.target(0, carriage));
        assertNotEquals(EditorMirror.target(0, carriage), EditorMirror.target(0, corridor));

        for (int x = 0; x <= 12; x++) {
            assertEquals(12 - x, EditorMirror.target(x, corridor), "x=" + x);
        }
        // Odd length, so the centre column is its own reflection and nothing lands off the end.
        assertEquals(6, EditorMirror.target(6, corridor));
    }

    @Test
    @DisplayName("An edit past a carriage's length still produces a reflection")
    void farHalfEditsStillMirror() {
        Vec3i footprint = corridorFootprint();

        // x = 10 is inside the corridor but outside a carriage — under the carriage-sized footprint
        // this cell was rejected as out of bounds and produced nothing at all.
        List<EditorMirror.Image> images =
            EditorMirror.imagesOf(new BlockPos(10, 1, 3), footprint, true, false, false);

        assertEquals(1, images.size());
        assertEquals(new BlockPos(2, 1, 3), images.get(0).local());
        assertTrue(images.get(0).flipX());
    }

    @Test
    @DisplayName("Every cell of a corridor reflects to another cell of the same corridor")
    void reflectionsStayInsideTheBox() {
        Vec3i footprint = corridorFootprint();

        for (int x = 0; x < footprint.getX(); x++) {
            for (int z = 0; z < footprint.getZ(); z++) {
                for (EditorMirror.Image image :
                    EditorMirror.imagesOf(new BlockPos(x, 1, z), footprint, true, false, true)) {
                    BlockPos p = image.local();
                    assertTrue(p.getX() >= 0 && p.getX() < footprint.getX(),
                        "x " + x + " reflected out of the corridor to " + p);
                    assertTrue(p.getZ() >= 0 && p.getZ() < footprint.getZ(),
                        "z " + z + " reflected out of the corridor to " + p);
                }
            }
        }
    }

    @Test
    @DisplayName("The corridor's box is the carriage's in every axis but length")
    void onlyTheLengthChanges() {
        CarriageDims box = PortalCorridorSize.corridorDims(DIMS, PortalCorridorKind.LONG);

        assertEquals(PortalCorridorSize.corridorLength(DIMS, PortalCorridorKind.LONG), box.length());
        assertEquals(DIMS.width(), box.width());
        assertEquals(DIMS.height(), box.height());
    }
}
