package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the folder and file name the editor screen's Open-files button looks in, per roster key. */
final class TemplateFileLocatorTest {

    private static TemplateFileLocator.Location at(PlotCategory c, String modelId, String modelName) {
        return TemplateFileLocator.of(c, modelId, modelName).orElseThrow();
    }

    @Test
    @DisplayName("id-keyed kinds use their store's folder and the id")
    void idKeyed() {
        assertEquals(new TemplateFileLocator.Location("wholecarriages", "hall.nbt"),
            at(PlotCategory.WHOLE, "hall", "hall"));
        assertEquals(new TemplateFileLocator.Location("carriagegroups", "pair.nbt"),
            at(PlotCategory.WHOLE_GROUP, "pair", "pair"));
        assertEquals(new TemplateFileLocator.Location("templates", "windowed.nbt"),
            at(PlotCategory.CARRIAGES, "windowed", "windowed"));
        assertEquals(new TemplateFileLocator.Location("contents", "library.nbt"),
            at(PlotCategory.CONTENTS, "library", "library"));
    }

    @Test
    @DisplayName("kind-keyed kinds nest under the kind and use the variant name")
    void nameKeyed() {
        assertEquals(new TemplateFileLocator.Location("parts/floor", "planks.nbt"),
            at(PlotCategory.PARTS, "floor", "planks"));
        assertEquals(new TemplateFileLocator.Location("portals/room", "vault.nbt"),
            at(PlotCategory.PORTALS, "portal_room", "vault"));
        assertEquals(new TemplateFileLocator.Location("chunk_frames", "ring.nbt"),
            at(PlotCategory.CHUNK_FRAMES, "ring", "ring"));
    }

    @Test
    @DisplayName("every track-side token the roster sends finds its kind's folder")
    void trackTokens() {
        assertEquals("tracks", at(PlotCategory.TRACKS, "track", "default").subdir());
        assertEquals("tracks", at(PlotCategory.TRACKS, "tile", "default").subdir());
        for (TrackKind k : TrackKind.values()) {
            if (k == TrackKind.PORTAL_ROOM) continue;
            assertEquals(k.subdir(), at(PlotCategory.TRACKS, k.id(), "x").subdir(), k.id());
        }
    }

    @Test
    @DisplayName("nothing to open for architecture, a blank key or an unknown track kind")
    void empties() {
        assertTrue(TemplateFileLocator.of(PlotCategory.ARCHITECTURE, "walls", "walls").isEmpty());
        assertTrue(TemplateFileLocator.of(PlotCategory.WHOLE, "", "").isEmpty());
        assertTrue(TemplateFileLocator.of(PlotCategory.PARTS, "", "planks").isEmpty());
        assertTrue(TemplateFileLocator.of(PlotCategory.TRACKS, "nope", "x").isEmpty());
        assertEquals(Optional.empty(), TemplateFileLocator.of(null, "a", "b"));
    }
}
