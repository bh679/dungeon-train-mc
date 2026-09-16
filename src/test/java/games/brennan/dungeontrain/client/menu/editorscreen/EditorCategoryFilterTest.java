package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The category strip: its order, its All sentinel, and where each category's templates browse. */
final class EditorCategoryFilterTest {

    @Test
    @DisplayName("All leads, then the categories in the order the old tabs had, Prefabs last")
    void order() {
        assertArrayEquals(new EditorCategoryFilter[] {
            EditorCategoryFilter.ALL, EditorCategoryFilter.CARRIAGES, EditorCategoryFilter.CONTENTS,
            EditorCategoryFilter.TRACKS, EditorCategoryFilter.DIMENSIONS, EditorCategoryFilter.PREFABS,
        }, EditorCategoryFilter.values());
        assertEquals(PlotCategory.PREFABS, EditorCategoryFilter.PREFABS.category());
        assertEquals(EditorCategoryFilter.PREFABS, EditorCategoryFilter.forCategory(PlotCategory.PREFABS));
        assertNull(EditorCategoryFilter.ALL.category());
        assertEquals(PlotCategory.PORTALS, EditorCategoryFilter.DIMENSIONS.category());
    }

    @Test
    @DisplayName("parts browse under Carriages, portals under Dimensions, architecture nowhere")
    void forCategory() {
        assertEquals(EditorCategoryFilter.CARRIAGES, EditorCategoryFilter.forCategory(PlotCategory.CARRIAGES));
        assertEquals(EditorCategoryFilter.CARRIAGES, EditorCategoryFilter.forCategory(PlotCategory.PARTS));
        assertEquals(EditorCategoryFilter.CONTENTS, EditorCategoryFilter.forCategory(PlotCategory.CONTENTS));
        assertEquals(EditorCategoryFilter.TRACKS, EditorCategoryFilter.forCategory(PlotCategory.TRACKS));
        assertEquals(EditorCategoryFilter.DIMENSIONS, EditorCategoryFilter.forCategory(PlotCategory.PORTALS));
        assertNull(EditorCategoryFilter.forCategory(PlotCategory.ARCHITECTURE));
        assertNull(EditorCategoryFilter.forCategory(null));
    }
}
