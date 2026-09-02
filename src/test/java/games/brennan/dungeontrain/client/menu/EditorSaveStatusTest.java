package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the key shapes and colour rules the header Save icon depends on. */
final class EditorSaveStatusTest {

    private static EditorDirtyCheck.DirtyEntry row(String cat, String id, boolean unsaved, boolean unpromoted) {
        return new EditorDirtyCheck.DirtyEntry(cat, id, id, unsaved, unpromoted);
    }

    @Test
    @DisplayName("Carriages and contents key on the bare model id")
    void key_carriagesContents() {
        assertEquals("standard", EditorSaveStatus.dirtyKey(PlotCategory.CARRIAGES, "standard", "standard"));
        assertEquals("library", EditorSaveStatus.dirtyKey(PlotCategory.CONTENTS, "library", "library"));
    }

    @Test
    @DisplayName("Track-side and portal plots key on id.variant, as EditorDirtyCheck.dirtyKeyFor does")
    void key_tracksPortals() {
        assertEquals("track.track2", EditorSaveStatus.dirtyKey(PlotCategory.TRACKS, "track", "track2"));
        assertEquals("pillar_top.default", EditorSaveStatus.dirtyKey(PlotCategory.TRACKS, "pillar_top", "default"));
        assertEquals("portal_room.hall", EditorSaveStatus.dirtyKey(PlotCategory.PORTALS, "portal_room", "hall"));
    }

    @Test
    @DisplayName("Parts and architecture have no scan rows, so no key")
    void key_uncoveredCategories() {
        assertNull(EditorSaveStatus.dirtyKey(PlotCategory.PARTS, "roof", "roof"));
        assertNull(EditorSaveStatus.dirtyKey(PlotCategory.ARCHITECTURE, "x", "y"));
        assertNull(EditorSaveStatus.dirtyKey(PlotCategory.CARRIAGES, "", "y"));
    }

    @Test
    @DisplayName("Dirty needs an unsaved row for that category and key; unpromoted-only is clean")
    void isDirty_matching() {
        List<EditorDirtyCheck.DirtyEntry> rows = List.of(
            row("carriages", "standard", true, false),
            row("tracks", "track.track2", false, true));
        assertTrue(EditorSaveStatus.isDirty(rows, "carriages", "standard"));
        assertFalse(EditorSaveStatus.isDirty(rows, "contents", "standard"), "same key, other category");
        assertFalse(EditorSaveStatus.isDirty(rows, "tracks", "track.track2"), "unpromoted only");
        assertFalse(EditorSaveStatus.isDirty(rows, "carriages", "other"));
    }

    @Test
    @DisplayName("No reply yet, or no key, reads as clean rather than alarming")
    void isDirty_nullSafe() {
        assertFalse(EditorSaveStatus.isDirty(null, "carriages", "standard"));
        assertFalse(EditorSaveStatus.isDirty(List.of(), "carriages", "standard"));
        assertFalse(EditorSaveStatus.isDirty(List.of(row("carriages", "standard", true, false)), "carriages", null));
    }

    @Test
    @DisplayName("The pulse stays inside its brightness band and peaks a quarter-period in")
    void pulse_band() {
        for (long t = 0; t < 2000; t += 37) {
            float f = EditorSaveStatus.pulse(t);
            assertTrue(f >= EditorSaveStatus.PULSE_MIN - 1e-6 && f <= EditorSaveStatus.PULSE_MAX + 1e-6, "t=" + t);
        }
        assertEquals(EditorSaveStatus.PULSE_MAX, EditorSaveStatus.pulse(250L), 1e-6);
        assertEquals(EditorSaveStatus.PULSE_MIN, EditorSaveStatus.pulse(750L), 1e-6);
    }

    @Test
    @DisplayName("Scaling darkens RGB and leaves alpha alone")
    void scale_keepsAlpha() {
        assertEquals(0x80000000, EditorSaveStatus.scale(0x80FFFFFF, 0f));
        assertEquals(0xFF808080, EditorSaveStatus.scale(0xFFFFFFFF, 0.5f) & 0xFFFFFFFF);
        assertEquals(EditorSaveStatus.CLEAN_TINT, EditorSaveStatus.tint(false, 999L));
    }
}
