package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Where a downloaded build is edited, and how the editor is told to go there.
 *
 * <p>Worth pinning because the failure is silent in the worst way: a wrong category sends the player
 * through a destructive clear-and-restamp to somewhere their build isn't, and a wrong command simply
 * does nothing after they pressed a button that said it would.</p>
 */
final class EditorTemplateJumpTest {

    @Test
    @DisplayName("each kind names the category that edits it")
    void categories() {
        assertEquals(PlotCategory.CARRIAGES.id(),
                EditorTemplateJump.categoryIdFor(BuilderPhotoPaths.Kind.CARRIAGE, ""));
        assertEquals(PlotCategory.CONTENTS.id(),
                EditorTemplateJump.categoryIdFor(BuilderPhotoPaths.Kind.CONTENTS, ""));
        assertEquals(PlotCategory.PORTALS.id(),
                EditorTemplateJump.categoryIdFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, ""));
        assertEquals(PlotCategory.TRACKS.id(),
                EditorTemplateJump.categoryIdFor(BuilderPhotoPaths.Kind.TRACK, TrackKind.TILE.id()));
        // A part is stamped as part of the carriages plots, so that is where the player is sent.
        assertEquals(PlotCategory.CARRIAGES.id(),
                EditorTemplateJump.categoryIdFor(BuilderPhotoPaths.Kind.PART, "door"));
    }

    @Test
    @DisplayName("a portal room filed as a track kind still routes to Portals")
    void portalRoomAsTrackKind() {
        assertEquals(PlotCategory.PORTALS.id(),
                EditorTemplateJump.categoryIdFor(BuilderPhotoPaths.Kind.TRACK,
                        TrackKind.PORTAL_ROOM.id()));
    }

    @Test
    @DisplayName("a carriage group has no editor home, and says so rather than guessing")
    void groupHasNoCategory() {
        assertNull(EditorTemplateJump.categoryIdFor(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, ""));
        assertNull(EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "run", ""));
        assertNull(EditorTemplateJump.categoryIdFor(null, ""));
    }

    @Test
    @DisplayName("the enter commands are the ones the editor's own template list dispatches")
    void enterCommands() {
        assertEquals("dungeontrain editor enter brick_cabin",
                EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.CARRIAGE, "brick_cabin", ""));
        assertEquals("dungeontrain editor contents enter maze",
                EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.CONTENTS, "maze", ""));
        assertEquals("dungeontrain editor portals enter library",
                EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, "library", ""));
        assertEquals("dungeontrain editor track enter",
                EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.TRACK, "default",
                        TrackKind.TILE.id()));
        assertEquals("dungeontrain editor enter tunnel_portal",
                EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.TRACK, "default",
                        TrackKind.TUNNEL_PORTAL.id()));
        assertEquals("dungeontrain editor pillar enter middle",
                EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.TRACK, "default",
                        TrackKind.PILLAR_MIDDLE.id()));
        assertEquals("dungeontrain editor pillar enter stairs_entrance",
                EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.TRACK, "default",
                        TrackKind.ADJUNCT_STAIRS_ENTRANCE.id()));
    }

    @Test
    @DisplayName("every track kind has a command — a new one fails here, not in front of a player")
    void everyTrackKindRoutes() {
        for (TrackKind kind : TrackKind.values()) {
            assertNotNull(EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.TRACK,
                    "default", kind.id()), kind + " has no way into the editor");
        }
    }

    @Test
    @DisplayName("a part stops at its category: the editor shows parts as a grid, not a plot each")
    void partHasNoEnterCommand() {
        assertNull(EditorTemplateJump.enterCommandFor(BuilderPhotoPaths.Kind.PART, "standard", "door"));
    }
}
