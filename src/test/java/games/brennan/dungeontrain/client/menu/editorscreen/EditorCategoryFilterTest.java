package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The category strip: its order, its All sentinel, and where each category's templates browse. */
@ExtendWith(MenuTestLanguage.class)
final class EditorCategoryFilterTest {

    @Test
    @DisplayName("All leads, then Whole first, then the four categories in the order the old tabs had")
    void order() {
        assertArrayEquals(new EditorCategoryFilter[] {
            EditorCategoryFilter.ALL, EditorCategoryFilter.WHOLE, EditorCategoryFilter.CARRIAGES,
            EditorCategoryFilter.CONTENTS, EditorCategoryFilter.TRACKS, EditorCategoryFilter.DIMENSIONS,
        }, EditorCategoryFilter.values());
        assertEquals(PlotCategory.WHOLE, EditorCategoryFilter.WHOLE.category());
        assertNull(EditorCategoryFilter.ALL.category());
        assertEquals(PlotCategory.PORTALS, EditorCategoryFilter.DIMENSIONS.category());
    }

    @Test
    @DisplayName("parts browse under Carriages, portals under Dimensions, architecture nowhere")
    void forCategory() {
        assertEquals(EditorCategoryFilter.CARRIAGES, EditorCategoryFilter.forCategory(PlotCategory.CARRIAGES));
        assertEquals(EditorCategoryFilter.CARRIAGES, EditorCategoryFilter.forCategory(PlotCategory.PARTS));
        assertEquals(EditorCategoryFilter.WHOLE, EditorCategoryFilter.forCategory(PlotCategory.WHOLE));
        assertEquals(EditorCategoryFilter.WHOLE, EditorCategoryFilter.forCategory(PlotCategory.WHOLE_GROUP));
        assertEquals(EditorCategoryFilter.CONTENTS, EditorCategoryFilter.forCategory(PlotCategory.CONTENTS));
        assertEquals(EditorCategoryFilter.TRACKS, EditorCategoryFilter.forCategory(PlotCategory.TRACKS));
        assertEquals(EditorCategoryFilter.DIMENSIONS, EditorCategoryFilter.forCategory(PlotCategory.PORTALS));
        assertNull(EditorCategoryFilter.forCategory(PlotCategory.ARCHITECTURE));
        assertNull(EditorCategoryFilter.forCategory(null));
    }
}
