package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The carriage and Whole editors keep the keys and sizes their static classes answered with. */
final class PlotEditorsTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;

    @Test
    @DisplayName("carriage plots file under the dirty scan's carriages key, sized by the variant's own box")
    void carriageKeyAndSize() {
        Template.Carriage standard = new Template.Carriage(CarriageVariant.custom("mine"));
        assertEquals(EditorDirtyCheck.snapshotKeyFor("carriages", "mine"),
            CarriagePlotEditor.get().snapshotKey(standard));
        assertEquals(standard.plotSize(DIMS), CarriagePlotEditor.get().size(standard, DIMS));
        assertSame(CarriagePlotEditor.get(), PlotEditors.editorFor(standard).orElseThrow());
    }

    @Test
    @DisplayName("whole plots keep WholeCarriageEditor's keys and sizes, one editor per kind")
    void wholeKeysAndSizes() {
        Template.WholeCarriage room = new Template.WholeCarriage(new WholeCarriage("r"));
        Template.WholeHalf half = new Template.WholeHalf(new WholeCarriage("h"));
        Template.CarriageGroup group = new Template.CarriageGroup(new CarriageGroup("g"));
        assertEquals(WholeCarriageEditor.snapshotKey(WholeKind.ROOM, "r"), WholePlotEditor.room().snapshotKey(room));
        assertEquals(WholeCarriageEditor.snapshotKey(WholeKind.HALF, "h"), WholePlotEditor.half().snapshotKey(half));
        assertEquals(WholeCarriageEditor.snapshotKey(WholeKind.GROUP, "g"), WholePlotEditor.group().snapshotKey(group));
        assertEquals(WholeCarriageEditor.plotSize(WholeKind.HALF, DIMS), WholePlotEditor.half().size(half, DIMS));
        assertEquals(group.plotSize(DIMS), WholePlotEditor.group().size(group, DIMS));
        assertSame(WholePlotEditor.room(), PlotEditors.editorFor(room).orElseThrow());
        assertSame(WholePlotEditor.half(), PlotEditors.editorFor(half).orElseThrow());
        assertSame(WholePlotEditor.group(), PlotEditors.editorFor(group).orElseThrow());
        for (WholeKind kind : WholeKind.values()) assertSame(kind, ((WholePlotEditor<?>) WholePlotEditor.forKind(kind)).kind);
    }

    @Test
    @DisplayName("kinds still on a static editor have no instance yet")
    void staticKindsHaveNoEditor() {
        assertTrue(PlotEditors.editorFor(new Template.PortalRoom("x")).isEmpty());
    }
}
