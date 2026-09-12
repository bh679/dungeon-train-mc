package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.PlotCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The build on the platform as the roster key the screen selects by. Pins the key shapes the
 * roster rows carry (see {@code EditorTypeMenus}): carriages and contents keyed by id twice, parts
 * and tracks by (kind token, name), rooms under the one portal kind.
 */
final class BuilderStandingTest {

    @Test
    @DisplayName("a whole carriage is the carriages row keyed by its id")
    void carriage() {
        assertEquals(VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed"),
            BuilderStanding.key("train_outside", "whole_carriage", "", "", "windowed", 1));
    }

    @Test
    @DisplayName("a draft and a carriage group have no row to stand in")
    void draftAndGroup() {
        assertNull(BuilderStanding.key("train_outside", "whole_carriage", "", "", "", 1));
        assertNull(BuilderStanding.key("train_outside", "whole_carriage", "", "", "run", 3));
    }

    @Test
    @DisplayName("a carriage room from inside is contents; from outside it is the carriage")
    void room() {
        assertEquals(VariantKey.of(PlotCategory.CONTENTS, "armor", "armor"),
            BuilderStanding.key("inside_carriage", "carriage_room", "", "", "armor", 1));
        assertEquals(VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen"),
            BuilderStanding.key("train_outside", "carriage_room", "", "", "pen", 1));
    }

    @Test
    @DisplayName("a part is keyed by its kind token and name")
    void part() {
        assertEquals(VariantKey.of(PlotCategory.PARTS, "floor", "oak"),
            BuilderStanding.key("inside_carriage", "parts", "floor", "", "oak", 1));
        assertNull(BuilderStanding.keyOf(BuilderPhotoPaths.Kind.PART, "", "oak"), "no kind, no id-space");
    }

    @Test
    @DisplayName("tracks are keyed by TrackKind id; a portal room lands under Dimensions")
    void tracks() {
        assertEquals(VariantKey.of(PlotCategory.TRACKS, "pillar_top", "stone"),
            BuilderStanding.key("tracks_tunnels", "", "", "pillar_top", "stone", 0));
        assertEquals(VariantKey.of(PlotCategory.PORTALS, "portal_room", "house"),
            BuilderStanding.key("train_dimensions", "portal_room", "", "", "house", 0));
        assertEquals(VariantKey.of(PlotCategory.PORTALS, "portal_room", "house"),
            BuilderStanding.keyOf(BuilderPhotoPaths.Kind.TRACK, "portal_room", "house"));
        assertNull(BuilderStanding.keyOf(BuilderPhotoPaths.Kind.TRACK, "not_a_kind", "x"));
    }
}
