package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.PoolMovePickerScreen;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.editor.pool.Pool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TemplateDataSheetPoolLineTest {

    @Test
    @DisplayName("each pool category maps to its pool; the rest have none")
    void poolOfCategory() {
        assertEquals(Pool.CARRIAGE, TemplateDataSheet.poolOf(PlotCategory.CARRIAGES));
        assertEquals(Pool.WHOLE, TemplateDataSheet.poolOf(PlotCategory.WHOLE));
        assertEquals(Pool.WHOLE, TemplateDataSheet.poolOf(PlotCategory.WHOLE_HALF));
        assertEquals(Pool.WHOLE, TemplateDataSheet.poolOf(PlotCategory.WHOLE_GROUP));
        assertEquals(Pool.CONTENTS, TemplateDataSheet.poolOf(PlotCategory.CONTENTS));
        assertNull(TemplateDataSheet.poolOf(PlotCategory.PORTALS));
        assertNull(TemplateDataSheet.poolOf(PlotCategory.WORKBENCH));
    }

    @Test
    @DisplayName("the cell command is the pool-move command under the template's own name")
    void cellCommand() {
        assertEquals("dungeontrain editor pool-move whole bunk contents",
            TemplateDataSheet.poolMoveCommand(Pool.WHOLE, "bunk", Pool.CONTENTS));
    }

    @Test
    @DisplayName("the Move icon opens the pool picker for shells and whole carriages only")
    void pickerSupport() {
        assertTrue(PoolMovePickerScreen.supports(VariantKey.of(PlotCategory.CARRIAGES, "cargo", "cargo")));
        assertTrue(PoolMovePickerScreen.supports(VariantKey.of(PlotCategory.WHOLE_HALF, "bunk", "bunk")));
        assertFalse(PoolMovePickerScreen.supports(VariantKey.of(PlotCategory.CONTENTS, "x", "x")),
            "contents keep the sub-variant picker on the icon");
        assertFalse(PoolMovePickerScreen.supports(new VariantKey(PlotCategory.CARRIAGES, "x", "x", "parent")));
        assertFalse(PoolMovePickerScreen.supports(null));
    }
}
