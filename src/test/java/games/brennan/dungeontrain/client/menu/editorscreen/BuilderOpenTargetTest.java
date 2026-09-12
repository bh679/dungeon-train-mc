package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a roster tile asks the Train Builder to open — mode, store kind, id and id-space. */
final class BuilderOpenTargetTest {

    @Test
    @DisplayName("a carriage opens from outside, contents from inside, by id")
    void carriageAndContents() {
        BuilderOpenTarget c = BuilderOpenTarget.of(VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed"));
        assertEquals("train_outside", c.modeId());
        assertEquals("carriage", c.kindId());
        assertEquals("windowed", c.id());
        assertFalse(c.isTrack());

        BuilderOpenTarget k = BuilderOpenTarget.of(VariantKey.of(PlotCategory.CONTENTS, "armor", "armor"));
        assertEquals("inside_carriage", k.modeId());
        assertEquals("contents", k.kindId());
    }

    @Test
    @DisplayName("a sub-variant opens by its own id; the parent link is not part of the request")
    void subVariant() {
        BuilderOpenTarget t = BuilderOpenTarget.of(new VariantKey(PlotCategory.CONTENTS, "armor_gold", "armor_gold", "armor"));
        assertEquals("armor_gold", t.id());
    }

    @Test
    @DisplayName("a part carries its kind as the id-space")
    void part() {
        BuilderOpenTarget t = BuilderOpenTarget.of(VariantKey.of(PlotCategory.PARTS, "floor", "oak"));
        assertEquals("inside_carriage", t.modeId());
        assertEquals("part", t.kindId());
        assertEquals("oak", t.id());
        assertEquals("floor", t.partKindId());
        assertNull(BuilderOpenTarget.of(VariantKey.of(PlotCategory.PARTS, "floor", "")));
    }

    @Test
    @DisplayName("tracks carry their TrackKind; a room is its own kind under Dimensions")
    void tracksAndRooms() {
        BuilderOpenTarget t = BuilderOpenTarget.of(VariantKey.of(PlotCategory.TRACKS, "tunnel_section", "brick"));
        assertEquals("tracks_tunnels", t.modeId());
        assertTrue(t.isTrack());
        assertEquals(TrackKind.TUNNEL_SECTION, t.trackKind());
        assertEquals("brick", t.id());
        assertNull(BuilderOpenTarget.of(VariantKey.of(PlotCategory.TRACKS, "nope", "brick")));

        BuilderOpenTarget r = BuilderOpenTarget.of(VariantKey.of(PlotCategory.PORTALS, "portal_room", "house"));
        assertEquals("train_dimensions", r.modeId());
        assertEquals("portal_room", r.kindId());
        assertEquals("house", r.id());
        assertFalse(r.isTrack());
    }

    @Test
    @DisplayName("nothing to open for architecture or an empty key")
    void nothing() {
        assertNull(BuilderOpenTarget.of(VariantKey.of(PlotCategory.ARCHITECTURE, "x", "x")));
        assertNull(BuilderOpenTarget.of(null));
    }
}
