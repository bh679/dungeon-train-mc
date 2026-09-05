package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VariantKeyTest {

    @Test
    @DisplayName("the status HUD's fields map straight through for carriages and track-side kinds")
    void fromStatusPlain() {
        VariantKey k = VariantKey.fromStatus("Carriages", "windowed", "windowed");
        assertEquals(PlotCategory.CARRIAGES, k.category());
        assertEquals("windowed", k.displayName());
        VariantKey t = VariantKey.fromStatus("tracks", "pillar_top", "tuff");
        assertEquals("pillar_top", t.modelId());
        assertEquals("tuff", t.displayName());
    }

    @Test
    @DisplayName("a part's kind:name model splits into the kind token and the part name")
    void fromStatusParts() {
        VariantKey k = VariantKey.fromStatus("Parts", "walls:quartzwindow2", "quartzwindow2");
        assertEquals(PlotCategory.PARTS, k.category());
        assertEquals("walls", k.modelId());
        assertEquals("quartzwindow2", k.modelName());
    }

    @Test
    @DisplayName("no category or no model means no key")
    void fromStatusAbsent() {
        assertNull(VariantKey.fromStatus("", "x", "x"));
        assertNull(VariantKey.fromStatus("stages", "x", "x"));
        assertNull(VariantKey.fromStatus("carriages", "", ""));
    }

    @Test
    @DisplayName("sameTemplate ignores the parent link; equals does not")
    void sameTemplate() {
        VariantKey top = VariantKey.of(PlotCategory.CONTENTS, "armor5", "armor5");
        VariantKey member = new VariantKey(PlotCategory.CONTENTS, "armor5", "armor5", "armor");
        assertTrue(top.sameTemplate(member));
        assertFalse(top.equals(member));
        assertFalse(top.sameTemplate(VariantKey.of(PlotCategory.CARRIAGES, "armor5", "armor5")));
        assertFalse(top.sameTemplate(null));
        assertTrue(member.isSubVariant());
    }
}
